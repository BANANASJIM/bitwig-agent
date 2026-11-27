package dev.bitwigagent.render;

import com.bitwig.extension.controller.api.Project;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostModule;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Params;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Refs;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.core.RpcException;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.core.Verification;
import dev.bitwigagent.host.NativeRuntime;
import dev.bitwigagent.host.TrackEpochs;
import dev.bitwigagent.host.TrackRefs;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import dev.bitwigagent.nativehost.NativeHost;
import dev.bitwigagent.nativehost.NativeRenderAccess;
import dev.bitwigagent.project.ProjectSaveAsPlan;
import dev.bitwigagent.recovery.JobsModule;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * export.audio and tracks.bounce: Bitwig 6.1.4's own render jobs (the Export Audio dialog's call and Bounce in
 * Place), dispatched once without a dialog. Completion is the job's native latch; success is the files' headers
 * (export) or the arranger readback (bounce). A job that outlives its request is followed with jobs.get.
 */
public final class RenderModule implements HostModule, JobsModule.Source {
    static final String EXPORT = "export.audio", BOUNCE = "tracks.bounce";
    static final Set<String> METHODS = Set.of(EXPORT, BOUNCE);
    static final String MASTER = "master";
    static final double MAX_LENGTH = 65536, EPS = 1e-6;
    /** After the latch, how long the bounce's undoable content replacement may take to show in the lane. */
    long applyGraceNanos = TimeUnit.SECONDS.toNanos(2);

    public static final class Export {
        public final String fileType, tap;
        public final int bitDepth, sampleRate;
        public final boolean dither, overwrite;
        public final double start, end;

        Export(
                String fileType,
                int bitDepth,
                int sampleRate,
                String tap,
                boolean dither,
                boolean overwrite,
                double start,
                double end) {
            this.fileType = fileType;
            this.bitDepth = bitDepth;
            this.sampleRate = sampleRate;
            this.tap = tap;
            this.dither = dither;
            this.overwrite = overwrite;
            this.start = start;
            this.end = end;
        }
    }

    public interface Handle {
        boolean done();
    }
    /** One lane read: native clip objects (compared by identity) and their arranger.list rows, same order. */
    public static final class Clips {
        final List<Object> natives;
        final JsonArray rows;

        public Clips(List<Object> natives, JsonArray rows) {
            this.natives = natives;
            this.rows = rows;
        }
    }

    /**
     * What the launcher reports for the track in a bank slot, each null when unobserved. A track renders what it is set to
     * play: the arranger only while it follows it; a launcher clip it plays (or queues) overrides the arranger, and a track
     * stopped from the launcher renders silent until playback is switched back to the arranger.
     */
    public interface LauncherContent {
        /** True when the track holds any launcher clip. */
        Boolean hasContent(int slot);

        /** True when one of its launcher clips is playing or queued, so it overrides the arranger. */
        default Boolean playing(int slot) {
            return null;
        }

        /** True when the track was stopped from the launcher (Track.isStopped), so it overrides the arranger with silence. */
        default Boolean stopped(int slot) {
            return null;
        }
    }

    static final String SWITCH_FIX =
            "actions.invoke actionId=switch_playback_to_arranger_for_all_tracks (every track follows the arranger again)";
    static final String COPY_FIX = "clips.copy its launcher clip onto the arranger (to {track, start})";

    public interface Access {
        void execute(Runnable work);

        void provenance(JsonObject out);
        /** Document thread: refuse what the native job would refuse or ask about, then dispatch once. null source = master. */
        Handle export(
                Object projectProxy,
                List<Object> sources,
                List<String> ids,
                String[] basePaths,
                Export options,
                Runnable check,
                Runnable beforeDispatch);

        Handle bounce(
                Object projectProxy,
                List<Object> tracks,
                List<String> ids,
                double start,
                double end,
                String tap,
                Runnable check,
                Runnable beforeDispatch);
        /** Document thread: the track's main clip lane. */
        Clips clips(Object trackProxy, String trackId, Runnable check);
    }

    private final class Job {
        final String id = JobsModule.id(kind(), ++sequence), method;
        final long started = scheduler.now();
        final List<String> ids;
        final List<JsonElement> sources;
        final List<Object> proxies;
        final double start, end;
        final String tap;
        final RenderPlan plan;
        final Export export;
        final Map<String, Clips> before = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        final Set<String> fixes = new LinkedHashSet<>();
        final Map<String, String> typesBefore = new LinkedHashMap<>();
        volatile boolean dispatched, settled, reported, returned, abandoned;
        volatile Handle handle;
        volatile long doneAt;

        Job(
                String method,
                List<String> ids,
                List<JsonElement> sources,
                List<Object> proxies,
                double start,
                double end,
                String tap,
                RenderPlan plan,
                Export export) {
            this.method = method;
            this.ids = ids;
            this.sources = sources;
            this.proxies = proxies;
            this.start = start;
            this.end = end;
            this.tap = tap;
            this.plan = plan;
            this.export = export;
        }

        boolean done() {
            Handle h = handle;
            if (h != null && h.done()) {
                if (!settled) doneAt = scheduler.now();
                settled = true;
            }
            return settled;
        }

        String describe() {
            return method + " " + id + " of " + ids + " " + start + ".." + end;
        }

        boolean bounce() {
            return method.equals(BOUNCE);
        }
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TrackRefs trackRefs;
    private final Project project;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final TrackEpochs epochs;

    private Access access;
    private final LauncherContent launcher;
    private volatile Job job;
    private int sequence;
    private static final String JOB = "render.job", QUARANTINE = "render.quarantine", FINISHED = "render.finished";

    public RenderModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            Project project,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds) {
        this(scheduler, window, trackRefs, project, transportSafety, natives, holds, null, null);
    }

    public RenderModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            Project project,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this(scheduler, window, trackRefs, project, transportSafety, natives, holds, access, null);
    }

    public RenderModule(
            Scheduler scheduler,
            TrackWindow window,
            TrackRefs trackRefs,
            Project project,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access,
            LauncherContent launcher) {
        this.launcher = launcher;
        this.scheduler = scheduler;
        this.window = window;
        this.trackRefs = trackRefs;
        this.project = project;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        holds.job(JOB, this::rendering, this::returned, this::abandon);
        holds.job(FINISHED, this::finishedUnverified, this::returned, this::abandon);
        this.access = access;
        this.epochs = new TrackEpochs(window);
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    private static final String QUARANTINED =
            "A tracks.bounce has an unknown outcome; inspect arranger.list on its tracks.";

    private String rendering() {
        Job current = job;
        return current != null && current.dispatched && !current.done() && !current.abandoned
                ? "A " + current.describe() + " is still rendering natively; follow it with jobs.get " + current.id
                        + "."
                : null;
    }

    private String finishedUnverified() {
        Job current = job;
        return !holds.held(QUARANTINE) && current != null && unverified(current)
                ? "A tracks.bounce finished after its request returned and is not verified yet; call jobs.get "
                        + current.id + "."
                : null;
    }

    private boolean returned() {
        Job current = job;
        return current != null && current.returned;
    }

    /**
     * After ack: further renders are admitted; the native job may still finish, jobs.get keeps reporting it. A bounce
     * that finishes later still has to be verified (the finished hold), so only a finished job counts as reported.
     */
    private void abandon() {
        Job current = job;
        current.abandoned = true;
        if (current.done()) current.reported = true;
    }

    private static boolean unverified(Job current) {
        return current.bounce() && current.dispatched && current.done() && !current.reported;
    }

    /** What the schema cannot say: distinct sources, the range, the file type's bit depths and the file names. */
    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case EXPORT: {
                distinct(p.getAsJsonArray("sources"));
                Params.absoluteFilePath(p, "directory");
                RenderPlan.baseName(Params.string(p, "baseName"));
                range(p);
                if (p.has("bitDepth")
                        && p.get("fileType").getAsString().equals("flac")
                        && p.get("bitDepth").getAsInt() == 32)
                    throw Errors.invalid("bitDepth must be 16 or 24 for flac.");
                return;
            }
            case BOUNCE:
                distinct(p.getAsJsonArray("targets"));
                range(p);
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown render method.");
        }
    }

    private static boolean master(JsonElement item) {
        return item.isJsonPrimitive();
    }

    private static String id(JsonElement item) {
        return master(item) ? MASTER : item.getAsJsonObject().get("id").getAsString();
    }

    private static void distinct(JsonArray items) {
        Set<String> seen = new HashSet<>();
        for (JsonElement item : items)
            if (!seen.add(id(item).toLowerCase(Locale.ROOT))) throw Errors.invalid("Entries must be distinct.");
    }

    private static void range(JsonObject p) {
        double start = p.get("start").getAsDouble(), end = p.get("end").getAsDouble();
        if (end <= start || end - start > MAX_LENGTH)
            throw Errors.invalid("end must be after start, at most " + (int) MAX_LENGTH + " beats later.");
    }

    @Override
    public void status(HostOperation op, JsonObject out, boolean full) {
        if (full) out.add("render", capability(op));
    }

    /** Binding availability for status detail full; binds on first use, never dispatches. */
    JsonObject capability(HostOperation op) {
        JsonObject result = new JsonObject();
        JsonArray methods = new JsonArray();
        for (String m : List.of(EXPORT, BOUNCE)) methods.add(m);
        result.add("methods", methods);
        try {
            access(op);
            result.addProperty("available", true);
        } catch (RpcException error) {
            if (!error.is(Reason.NATIVE_UNAVAILABLE) && !error.is(Reason.HOST_MISMATCH)) throw error;
            result.addProperty("available", false);
            result.addProperty("reason", error.getMessage());
        }
        result.addProperty(
                "binding",
                "exact_" + NativeHost.SUPPORTED
                        + "_hash_gated_export_audio_and_bounce_in_place_jobs_with_native_latch");
        return result;
    }

    private Access access(HostOperation op) {
        if (access == null) access = NativeRenderAccess.bind(natives.get(op).access());
        return access;
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        Job previous = job;
        if (previous != null && !previous.done() && !previous.abandoned)
            throw Errors.notApplied(
                    Reason.BUSY,
                    "A " + previous.describe() + " is still rendering natively; no request was queued.",
                    "Follow it with jobs.get " + previous.id + ".");
        if (method.equals(BOUNCE) && holds.held(QUARANTINE))
            throw holds.blocked(
                    QUARANTINE,
                    "An earlier tracks.bounce has an unknown outcome; bounces are refused.",
                    "Inspect arranger.list on its tracks");
        if (previous != null && unverified(previous) && !holds.held(QUARANTINE))
            throw Errors.notApplied(
                    Reason.BUSY,
                    "The previous tracks.bounce returned before it was verified; no request was queued.",
                    "Verify it with jobs.get " + previous.id + ".");
        SafetyObservation safety = transportSafety.stopped(op);
        boolean export = method.equals(EXPORT);
        List<JsonElement> sources = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Object> proxies = new ArrayList<>();
        JsonArray requested = p.getAsJsonArray(export ? "sources" : "targets");
        int[] slots = new int[requested.size()];
        long[] seen = new long[slots.length];
        for (int i = 0; i < slots.length; i++) {
            JsonElement item = requested.get(i);
            boolean master = master(item);
            slots[i] = master ? -1 : trackRefs.resolve(item.getAsJsonObject(), !export);
            ids.add(id(item));
            sources.add(master ? item : trackRefs.ref(slots[i]));
            proxies.add(master ? null : window.bank().getItemAt(slots[i]));
            if (!master) seen[i] = epochs.get(slots[i]);
        }
        double start = p.get("start").getAsDouble(), end = p.get("end").getAsDouble();
        String tap = p.has("tap") ? p.get("tap").getAsString() : "post_fader";
        Job next;
        if (export) {
            String fileType = p.get("fileType").getAsString();
            Export options = new Export(
                    fileType,
                    p.has("bitDepth") ? p.get("bitDepth").getAsInt() : 24,
                    p.has("sampleRate") ? p.get("sampleRate").getAsInt() : 0,
                    tap,
                    p.has("dither") && p.get("dither").getAsBoolean(),
                    p.has("overwrite") && p.get("overwrite").getAsBoolean(),
                    start,
                    end);
            RenderPlan plan = RenderPlan.export(
                    Path.of(p.get("directory").getAsString()),
                    p.get("baseName").getAsString(),
                    ids.size(),
                    fileType,
                    options.overwrite);
            next = new Job(EXPORT, ids, sources, proxies, start, end, tap, plan, options);
        } else {
            next = new Job(BOUNCE, ids, sources, proxies, start, end, tap, null, null);
            for (int i = 0; i < ids.size(); i++)
                next.typesBefore.put(
                        ids.get(i), ((Track) proxies.get(i)).trackType().get());
        }
        Access bridge = access(op);
        Runnable check = () -> {
            scheduler.check(op);
            transportSafety.guardStopped(safety);
            for (int i = 0; i < ids.size(); i++)
                if (slots[i] >= 0 && epochs.get(slots[i]) != seen[i])
                    throw Errors.notApplied(
                            Reason.TARGET_CHANGED,
                            "Track bank changed while the render was queued (" + ids.get(i) + ").");
        };
        job = next;
        op.result.whenComplete((result, error) -> next.returned = true);
        try {
            bridge.execute(() -> {
                try {
                    check.run();
                    Runnable before = () -> {
                        check.run();
                        op.markDispatched();
                        op.job = next.id;
                        next.dispatched = true;
                    };
                    if (export) {
                        for (int i = 0; i < ids.size(); i++)
                            if (slots[i] >= 0 && launcher != null) warnOverride(next, bridge, check, i, slots[i]);
                        next.plan.refresh(next.export.overwrite);
                        next.handle =
                                bridge.export(project, proxies, ids, next.plan.basePaths(), next.export, check, before);
                    } else {
                        for (int i = 0; i < ids.size(); i++)
                            next.before.put(ids.get(i), bridge.clips(proxies.get(i), ids.get(i), check));
                        next.handle = bridge.bounce(project, proxies, ids, start, end, tap, check, before);
                    }
                    scheduler.schedule(op, () -> await(op, next, bridge, scheduler.deadlineUntil(op)), 0);
                } catch (RuntimeException | Error error) {
                    settle(next, previous);
                    op.fail(error);
                }
            });
        } catch (RuntimeException | Error error) {
            settle(next, previous);
            throw error;
        }
    }

    /**
     * Warns, per requested track, when its render differs from the arranger content in range or is silent: it plays a
     * launcher clip (renders that), it was stopped from the launcher (silent), or it follows an arranger holding no clip in
     * range while only the launcher has clips (silent).
     */
    private void warnOverride(Job job, Access bridge, Runnable check, int i, int slot) {
        String id = job.ids.get(i);
        boolean playing = Boolean.TRUE.equals(launcher.playing(slot)),
                stopped = Boolean.TRUE.equals(launcher.stopped(slot)),
                content = Boolean.TRUE.equals(launcher.hasContent(slot));
        if (!playing && !stopped && !content) return;
        boolean empty = !overlaps(bridge.clips(job.proxies.get(i), id, check).rows, job.start, job.end);
        if (playing) {
            job.fixes.add("switch");
            if (empty) job.fixes.add("copy");
            job.warnings.add(id
                    + " is playing a launcher clip, which overrides the arranger, so it renders that clip, not the arranger"
                    + (empty ? " (the arranger holds no clip in the exported range)" : " clips in the exported range")
                    + ". Fix: " + SWITCH_FIX + " and export again"
                    + (empty ? "; with the arranger empty there, " + COPY_FIX + " first, or it renders silent" : ""));
        } else if (stopped) {
            job.fixes.add("switch");
            if (empty && content) job.fixes.add("copy");
            job.warnings.add(id
                    + " was stopped from the launcher, which overrides the arranger, so it renders silent whatever the arranger holds. Fix: "
                    + SWITCH_FIX + " and export again"
                    + (empty && content ? "; the arranger holds no clip in the range, so " + COPY_FIX + " first" : ""));
        } else if (empty) {
            job.fixes.add("copy");
            job.warnings.add(id
                    + " follows the arranger, which holds no clip in the exported range, so it renders silent (launcher clips are rendered only while it plays one). Fix: "
                    + COPY_FIX + ", then export again");
        }
    }

    /** What to do after a warned export; the files are already written. */
    private static String fixNext(Set<String> fixes) {
        List<String> steps = new ArrayList<>();
        if (fixes.contains("switch")) steps.add(SWITCH_FIX);
        if (fixes.contains("copy")) steps.add(COPY_FIX + " where the arranger is empty in range");
        return "The files are written as warned. To render what you intend: " + String.join("; ", steps)
                + ", then export again with overwrite.";
    }

    /** Whether any arranger.list row of a lane overlaps [start, end). */
    static boolean overlaps(JsonArray rows, double start, double end) {
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            double from = row.get("start").getAsDouble();
            if (from < end && from + row.get("length").getAsDouble() > start) return true;
        }
        return false;
    }

    /** A job that failed before dispatch gives the status slot back; after dispatch without a handle it is unknown. */
    private void settle(Job next, Job previous) {
        next.settled = true;
        if (!next.dispatched) {
            if (job == next) job = previous;
        } else if (next.bounce()) {
            next.reported = true;
            holds.place(QUARANTINE, QUARANTINED);
        }
    }

    private void await(HostOperation op, Job current, Access bridge, long until) {
        scheduler.poll(
                op,
                until,
                current::done,
                () -> evaluateFinished(op, current, bridge, until),
                () -> op.result.complete(deadline(current, bridge)));
    }

    private void evaluateFinished(HostOperation op, Job current, Access bridge, long until) {
        bridge.execute(() -> {
            try {
                scheduler.check(op);
                JsonObject evaluation = evaluate(current, bridge, () -> scheduler.check(op));
                scheduler.schedule(
                        op,
                        () -> {
                            boolean waiting = current.bounce()
                                    && !evaluation.get("verified").getAsBoolean()
                                    && !evaluation.get("changed").getAsBoolean()
                                    && scheduler.now() - current.doneAt < applyGraceNanos;
                            if (waiting && scheduler.now() < until) {
                                scheduler.schedule(op, () -> await(op, current, bridge, until), Scheduler.POLL_MS);
                                return;
                            }
                            if (waiting) {
                                op.result.complete(deadline(current, bridge));
                                return;
                            }
                            complete(op, current, bridge, evaluation);
                        },
                        0);
            } catch (RuntimeException | Error error) {
                op.fail(error);
            }
        });
    }

    /** Document thread for bounce (arranger readback); export reads only files. */
    private JsonObject evaluate(Job current, Access bridge, Runnable check) {
        JsonObject out = new JsonObject();
        boolean verified = true, changed = false;
        JsonArray rows = new JsonArray();
        if (!current.bounce()) {
            for (int i = 0; i < current.ids.size(); i++) {
                JsonObject row = RenderPlan.inspect(
                        current.plan.files.get(i),
                        current.plan.before.get(i),
                        current.export.fileType,
                        current.export.bitDepth,
                        current.export.sampleRate);
                row.add("source", current.sources.get(i));
                verified &= row.get("valid").getAsBoolean();
                ProjectSaveAsPlan.Stat was = current.plan.before.get(i),
                        now = ProjectSaveAsPlan.Stat.of(current.plan.files.get(i));
                changed |= was.exists != now.exists || was.size != now.size || was.modified != now.modified;
                rows.add(row);
            }
            out.add("files", rows);
        } else {
            for (int i = 0; i < current.ids.size(); i++) {
                String id = current.ids.get(i);
                JsonObject row = new JsonObject();
                row.add("track", current.sources.get(i));
                Clips before = current.before.get(id), after;
                try {
                    after = bridge.clips(current.proxies.get(i), id, check);
                } catch (RpcException error) {
                    row.addProperty("problem", "readback_failed: " + error.getMessage());
                    rows.add(row);
                    verified = false;
                    changed = true;
                    continue;
                }
                row.addProperty("clipsBefore", before.rows.size());
                row.addProperty("clipsAfter", after.rows.size());
                // Identity diff: the range is cleared natively, so clips cut at its edges may come back as new objects.
                boolean trackChanged = after.natives.size() != before.natives.size();
                JsonObject added = null;
                int inRange = 0;
                String problem = null;
                for (int k = 0; k < after.natives.size(); k++) {
                    JsonObject c = after.rows.get(k).getAsJsonObject();
                    int old = indexOf(before.natives, after.natives.get(k));
                    if (old < 0 || !sameSpan(before.rows.get(old).getAsJsonObject(), c)) trackChanged = true;
                    boolean overlaps = c.get("start").getAsDouble() < current.end - EPS
                            && current.start < c.get("end").getAsDouble() - EPS;
                    if (!overlaps) continue;
                    inRange++;
                    if (old < 0
                            && "audio".equals(str(c, "kind"))
                            && near(c.get("start").getAsDouble(), current.start)
                            && near(c.get("end").getAsDouble(), current.end)) added = c;
                }
                changed |= trackChanged;
                if (added == null) problem = trackChanged ? "no_new_audio_clip_at_requested_range" : "lane_unchanged";
                else if (inRange != 1) problem = "range_still_overlapped_by_another_clip";
                if (added != null) {
                    row.add("clip", added);
                    row.add(
                            "clipRef",
                            Refs.arrangerClip(
                                    current.sources.get(i).getAsJsonObject(),
                                    added.get("start").getAsDouble(),
                                    str(added, "name") == null ? "" : str(added, "name")));
                }
                if (problem != null) {
                    row.addProperty("problem", problem);
                    verified = false;
                }
                rows.add(row);
            }
            out.add("tracks", rows);
        }
        out.addProperty("verified", verified);
        out.addProperty("changed", changed);
        return out;
    }

    private static int indexOf(List<Object> natives, Object clip) {
        for (int i = 0; i < natives.size(); i++) if (natives.get(i) == clip) return i;
        return -1;
    }

    private static boolean sameSpan(JsonObject a, JsonObject b) {
        return near(a.get("start").getAsDouble(), b.get("start").getAsDouble())
                && near(a.get("length").getAsDouble(), b.get("length").getAsDouble());
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= EPS;
    }

    /** applied when verified; otherwise not_applied if nothing changed, else unknown. */
    private static Outcome outcome(JsonObject evaluation) {
        if (evaluation.get("verified").getAsBoolean()) return Outcome.APPLIED;
        return evaluation.get("changed").getAsBoolean() ? Outcome.UNKNOWN : Outcome.NOT_APPLIED;
    }

    private static Reason failure(Job current, Outcome outcome) {
        return current.bounce() && outcome == Outcome.UNKNOWN ? Reason.READBACK_MISMATCH : Reason.JOB_FAILED;
    }

    /** Only a job's first verification decides the bounce quarantine; later lane edits must not quarantine. */
    private void report(Job current, Outcome outcome) {
        if (!current.reported && current.bounce() && outcome == Outcome.UNKNOWN) holds.place(QUARANTINE, QUARANTINED);
        current.reported = true;
    }

    private void complete(HostOperation op, Job current, Access bridge, JsonObject evaluation) {
        Outcome outcome = outcome(evaluation);
        report(current, outcome);
        boolean bounce = current.bounce();
        if (outcome != Outcome.APPLIED) {
            String message = bounce
                    ? outcome == Outcome.UNKNOWN
                            ? "Bitwig finished the bounce but the arranger readback differs from one new audio clip over the range; inspect arranger.list on its tracks, then ack "
                                    + QUARANTINE + ". Never retry: "
                            : "Bitwig finished the bounce job without changing any lane (Bitwig reports the reason as a notification): "
                    : "Bitwig finished the export job but the files did not verify"
                            + (outcome == Outcome.NOT_APPLIED
                                    ? "; nothing was written (Bitwig reports the reason as a notification)"
                                    : "; inspect them, never retry")
                            + ": ";
            String detail = message + evaluation.get(bounce ? "tracks" : "files");
            Reason reason = failure(current, outcome);
            if (outcome == Outcome.NOT_APPLIED) op.result.completeExceptionally(Errors.notApplied(reason, detail));
            else op.fail(Errors.unknown(reason, detail));
            return;
        }
        JsonObject result = Results.outcome(Outcome.APPLIED);
        common(result, current, bridge);
        if (bounce) {
            result.add("tracks", evaluation.get("tracks"));
            trackTypes(current, evaluation.getAsJsonArray("tracks"));
            result.addProperty("nativeUndo", true);
            Verification.NATIVE_READBACK.put(result);
        } else {
            result.add("files", evaluation.get("files"));
            result.addProperty("mayOpenFileManager", true);
            if (!current.warnings.isEmpty()) {
                JsonArray warnings = new JsonArray();
                for (String warning : current.warnings) warnings.add(warning);
                result.add("warnings", warnings);
                result.addProperty("next", fixNext(current.fixes));
            }
            Verification.FILE_READBACK.put(result);
        }
        op.result.complete(result);
    }

    /**
     * Bouncing notes to audio in place turns an instrument track into a Hybrid track (audio clips on a note track);
     * deleting the bounced clip does not turn it back, Undo does. The public type can lag the job by a flush.
     */
    private static void trackTypes(Job current, JsonArray rows) {
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            String before = current.typesBefore.get(current.ids.get(i)),
                    after = ((Track) current.proxies.get(i)).trackType().get();
            row.addProperty("trackTypeBefore", before);
            row.addProperty("trackTypeAfter", after);
            if (before != null && !before.equals(after))
                row.addProperty(
                        "warning",
                        "track type changed from " + before + " to " + after
                                + "; Undo reverts it, deleting the bounced clip does not");
        }
    }

    private JsonObject deadline(Job current, Access bridge) {
        JsonObject result = JobsModule.deadline(
                current.id,
                current.done()
                        ? "Bitwig finished the job but it is not verified yet; verify it with jobs.get, or ack its hold after inspecting."
                        : "The native job is still running; further renders are busy until it completes, or until you ack its job hold after inspecting.");
        result.addProperty("nativeCompletion", current.done() ? "completed" : "pending");
        common(result, current, bridge);
        if (current.plan != null) {
            JsonArray files = new JsonArray();
            for (Path file : current.plan.files) files.add(file.toString());
            result.add("plannedFiles", files);
        }
        return result;
    }

    private void common(JsonObject result, Job current, Access bridge) {
        result.addProperty("method", current.method);
        result.addProperty("start", current.start);
        result.addProperty("end", current.end);
        result.addProperty("tap", current.tap);
        if (current.export != null) {
            result.addProperty("fileType", current.export.fileType);
            result.addProperty("bitDepth", current.export.bitDepth);
            result.addProperty(
                    "sampleRate",
                    current.export.sampleRate == 0 ? "current" : String.valueOf(current.export.sampleRate));
            result.addProperty("directory", current.plan.directory.toString());
        }
        current.done();
        long end = current.handle != null && current.settled ? current.doneAt : scheduler.now();
        result.addProperty("elapsedMs", TimeUnit.NANOSECONDS.toMillis(end - current.started));
        bridge.provenance(result);
    }

    @Override
    public String kind() {
        return "render";
    }

    /** The current job: running, or completed with its verification evaluated now. */
    @Override
    public void job(HostOperation op, String id) {
        scheduler.check(op);
        Job current = job;
        if (current == null || !current.dispatched || !current.id.equals(id)) throw JobsModule.unknownJob(id);
        Access bridge = access(op);
        JsonObject row = new JsonObject();
        row.addProperty("id", current.id);
        if (current.abandoned) row.addProperty("acknowledged", true);
        common(row, current, bridge);
        if (current.handle == null) {
            row.addProperty("state", current.settled ? "unknown" : "dispatching");
            if (current.settled)
                row.addProperty(
                        "hint", "Dispatched but no native job was observed; inspect the files or arranger.list.");
        } else if (!current.done()) row.addProperty("state", "running");
        else {
            bridge.execute(() -> {
                try {
                    scheduler.check(op);
                    JsonObject evaluation = evaluate(current, bridge, () -> scheduler.check(op));
                    scheduler.schedule(
                            op,
                            () -> {
                                Outcome outcome = outcome(evaluation);
                                report(current, outcome);
                                row.addProperty("state", "completed");
                                row.addProperty("outcome", outcome.wire());
                                if (outcome != Outcome.APPLIED)
                                    row.addProperty(
                                            "reason", failure(current, outcome).wire());
                                String key = current.bounce() ? "tracks" : "files";
                                row.add(key, evaluation.get(key));
                                op.result.complete(JobsModule.job(row));
                            },
                            0);
                } catch (RuntimeException | Error error) {
                    op.fail(error);
                }
            });
            return;
        }
        op.result.complete(JobsModule.job(row));
    }
}
