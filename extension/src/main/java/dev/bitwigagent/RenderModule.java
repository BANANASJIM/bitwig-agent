package dev.bitwigagent;

import com.bitwig.extension.controller.api.Project;
import com.bitwig.extension.controller.api.Track;
import com.google.gson.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * export.audio and bounce.inPlace: Bitwig 6.1.4's own render jobs (the Export Audio dialog's call and Bounce in
 * Place), dispatched once without a dialog. Completion is the job's native latch; success is the files' headers
 * (export) or the arranger readback (bounce). A job that outlives its request is followed with jobs.get.
 */
final class RenderModule implements HostModule, JobsModule.Source {
    static final String EXPORT = "export.audio", BOUNCE = "bounce.inPlace";
    static final Set<String> METHODS = Set.of(EXPORT, BOUNCE);
    static final String MASTER = "master";
    static final double MAX_BEATS = 4194304, MAX_LENGTH = 65536, EPS = 1e-6;
    static final Set<String> EXPORT_TAPS = Set.of("post_fader", "pre_fader"),
            BOUNCE_TAPS = Set.of("pre_fx", "pre_fader", "post_fader");
    /** After the latch, how long the bounce's undoable content replacement may take to show in the lane. */
    long applyGraceNanos = TimeUnit.SECONDS.toNanos(2);

    static final class Export {
        final String format, tap;
        final int bitDepth, sampleRate;
        final boolean dither, overwrite;
        final double start, end;

        Export(
                String format,
                int bitDepth,
                int sampleRate,
                String tap,
                boolean dither,
                boolean overwrite,
                double start,
                double end) {
            this.format = format;
            this.bitDepth = bitDepth;
            this.sampleRate = sampleRate;
            this.tap = tap;
            this.dither = dither;
            this.overwrite = overwrite;
            this.start = start;
            this.end = end;
        }
    }

    interface Handle {
        boolean done();
    }
    /** One lane read: native clip objects (compared by identity) and their arranger.clips.list rows, same order. */
    static final class Clips {
        final List<Object> natives;
        final JsonArray rows;

        Clips(List<Object> natives, JsonArray rows) {
            this.natives = natives;
            this.rows = rows;
        }
    }

    interface Access {
        void execute(Runnable work);

        String nativeJarSha256();
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
        final List<Object> proxies;
        final double start, end;
        final String tap;
        final RenderPlan plan;
        final Export export;
        final Map<String, Clips> before = new LinkedHashMap<>();
        final Map<String, String> typesBefore = new LinkedHashMap<>();
        volatile boolean dispatched, settled, reported, returned, abandoned;
        volatile Handle handle;
        volatile long doneAt;

        Job(
                String method,
                List<String> ids,
                List<Object> proxies,
                double start,
                double end,
                String tap,
                RenderPlan plan,
                Export export) {
            this.method = method;
            this.ids = ids;
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
                if (doneAt == 0) doneAt = scheduler.now();
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
    private final Project project;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final TrackEpochs epochs;

    private Access access;
    private volatile Job job;
    private int sequence;
    private static final String JOB = "render.job", QUARANTINE = "render.quarantine", FINISHED = "render.finished";

    RenderModule(
            Scheduler scheduler,
            TrackWindow window,
            Project project,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds) {
        this(scheduler, window, project, transportSafety, natives, holds, null);
    }

    RenderModule(
            Scheduler scheduler,
            TrackWindow window,
            Project project,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
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
            "A bounce.inPlace has an unknown outcome; inspect arranger.clips.list on its tracks.";

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
                ? "A bounce.inPlace finished after its request returned and is not verified yet; call jobs.get "
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

    static void validateParams(String method, JsonObject p) {
        switch (method) {
            case EXPORT: {
                Params.fields(
                        p,
                        Set.of("sources", "directory", "baseName", "startBeats", "endBeats", "format"),
                        Set.of("bitDepth", "sampleRate", "tap", "dither", "overwrite"));
                ids(p, "sources", true);
                Params.absoluteFilePath(p, "directory");
                RenderPlan.baseName(Params.string(p, "baseName"));
                range(p);
                String format = choice(p, "format", Set.of("wav", "flac"));
                if (p.has("bitDepth")) {
                    int depth = Params.integer(p, "bitDepth", 16, 32);
                    if (!(format.equals("wav") ? Set.of(16, 24, 32) : Set.of(16, 24)).contains(depth))
                        throw Errors.invalid("bitDepth must be 16, 24 or 32 (float) for wav, 16 or 24 for flac.");
                }
                if (p.has("sampleRate")
                        && !Set.of(44100, 48000).contains(Params.integer(p, "sampleRate", 44100, 48000)))
                    throw Errors.invalid("sampleRate must be 44100 or 48000 (omit for the current rate).");
                if (p.has("tap")) choice(p, "tap", EXPORT_TAPS);
                if (p.has("dither")) Params.bool(p, "dither");
                if (p.has("overwrite")) Params.bool(p, "overwrite");
                return;
            }
            case BOUNCE:
                Params.fields(p, Set.of("trackIds", "startBeats", "endBeats", "tap"), Set.of());
                ids(p, "trackIds", false);
                range(p);
                choice(p, "tap", BOUNCE_TAPS);
                return;
            default:
                throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown render method.");
        }
    }

    private static List<String> ids(JsonObject p, String key, boolean allowMaster) {
        JsonElement value = p.get(key);
        if (!value.isJsonArray()
                || value.getAsJsonArray().size() < 1
                || value.getAsJsonArray().size() > HostLimits.TRACKS)
            throw Errors.invalid(key + " must be an array of 1.." + HostLimits.TRACKS + " entries.");
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement item : value.getAsJsonArray()) {
            String id = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? item.getAsString() : "";
            if (!(allowMaster && id.equals(MASTER)) && !DeviceSources.canonicalUuid(id))
                throw Errors.invalid(
                        key + " entries must be canonical track UUIDs" + (allowMaster ? " or \"master\"." : "."));
            if (!seen.add(id.toLowerCase(Locale.ROOT))) throw Errors.invalid(key + " entries must be distinct.");
            out.add(id);
        }
        return out;
    }

    private static void range(JsonObject p) {
        double start = Params.number(p, "startBeats", 0, MAX_BEATS),
                end = Params.number(p, "endBeats", 0, MAX_BEATS + MAX_LENGTH);
        if (end <= start || end - start > MAX_LENGTH)
            throw Errors.invalid("endBeats must be after startBeats, at most " + (int) MAX_LENGTH + " beats later.");
    }

    private static String choice(JsonObject p, String key, Set<String> allowed) {
        String value = Params.string(p, key);
        if (!allowed.contains(value)) throw Errors.invalid(key + " must be one of " + new TreeSet<>(allowed) + ".");
        return value;
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
                "exact_" + Bitwig614Runtime.VERSION
                        + "_hash_gated_export_audio_and_bounce_in_place_jobs_with_native_latch");
        result.addProperty("verification", "native_job_latch_then_file_headers_or_arranger_readback");
        return result;
    }

    private Access access(HostOperation op) {
        if (access == null) access = Bitwig614RenderAccess.bind(natives.get(op).access());
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
                    "An earlier bounce.inPlace has an unknown outcome; bounces are refused.",
                    "Inspect arranger.clips.list on its tracks");
        if (previous != null && unverified(previous) && !holds.held(QUARANTINE))
            throw Errors.notApplied(
                    Reason.BUSY,
                    "The previous bounce.inPlace returned before it was verified; no request was queued.",
                    "Verify it with jobs.get " + previous.id + ".");
        SafetyObservation safety = transportSafety.stopped(op);
        boolean export = method.equals(EXPORT);
        List<String> ids = ids(p, export ? "sources" : "trackIds", export);
        List<Object> proxies = new ArrayList<>();
        int[] slots = new int[ids.size()];
        long[] seen = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            boolean master = ids.get(i).equals(MASTER);
            slots[i] = master ? -1 : window.trackSlot(ids.get(i));
            proxies.add(master ? null : window.bank().getItemAt(slots[i]));
            if (!master) seen[i] = epochs.get(slots[i]);
        }
        double start = p.get("startBeats").getAsDouble(),
                end = p.get("endBeats").getAsDouble();
        String tap = p.has("tap") ? p.get("tap").getAsString() : "post_fader";
        Job next;
        if (export) {
            String format = p.get("format").getAsString();
            Export options = new Export(
                    format,
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
                    format,
                    options.overwrite);
            next = new Job(EXPORT, ids, proxies, start, end, tap, plan, options);
        } else {
            next = new Job(BOUNCE, ids, proxies, start, end, tap, null, null);
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
                            Reason.STALE_CONTEXT,
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
                        current.export.format,
                        current.export.bitDepth,
                        current.export.sampleRate);
                row.addProperty("source", current.ids.get(i));
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
                row.addProperty("trackId", id);
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
                    boolean overlaps = c.get("startBeats").getAsDouble() < current.end - EPS
                            && current.start < c.get("endBeats").getAsDouble() - EPS;
                    if (!overlaps) continue;
                    inRange++;
                    if (old < 0
                            && "audio".equals(str(c, "kind"))
                            && near(c.get("startBeats").getAsDouble(), current.start)
                            && near(c.get("endBeats").getAsDouble(), current.end)) added = c;
                }
                changed |= trackChanged;
                if (added == null) problem = trackChanged ? "no_new_audio_clip_at_requested_range" : "lane_unchanged";
                else if (inRange != 1) problem = "range_still_overlapped_by_another_clip";
                if (added != null) row.add("clip", added);
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
        return near(a.get("startBeats").getAsDouble(), b.get("startBeats").getAsDouble())
                && near(a.get("lengthBeats").getAsDouble(), b.get("lengthBeats").getAsDouble());
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
                            ? "Bitwig finished the bounce but the arranger readback differs from one new audio clip over the range; inspect arranger.clips.list on its tracks, then ack "
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
            result.addProperty("verification", "native_job_latch_and_arranger_readback");
        } else {
            result.add("files", evaluation.get("files"));
            result.addProperty("mayOpenFileManager", true);
            result.addProperty("verification", "native_job_latch_and_file_headers");
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
        result.addProperty("startBeats", current.start);
        result.addProperty("endBeats", current.end);
        result.addProperty("tap", current.tap);
        if (current.export != null) {
            result.addProperty("format", current.export.format);
            result.addProperty("bitDepth", current.export.bitDepth);
            result.addProperty(
                    "sampleRate",
                    current.export.sampleRate == 0 ? "current" : String.valueOf(current.export.sampleRate));
            result.addProperty("directory", current.plan.directory.toString());
        }
        result.addProperty("elapsedMs", TimeUnit.NANOSECONDS.toMillis(scheduler.now() - current.started));
        result.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        result.addProperty("nativeJarSha256", bridge.nativeJarSha256());
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
                        "hint", "Dispatched but no native job was observed; inspect the files or arranger.clips.list.");
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
