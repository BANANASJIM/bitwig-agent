package dev.bitwigagent;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;

/** Source-bound launcher properties, independent of the note editor cursor. */
final class LauncherSettingsModule implements HostModule {
    static final Set<String> METHODS = Set.of("launcher.get", "launcher.set");
    static final List<String> WRITE_ORDER = List.of(
            "nextAction", "nextActionLinkToLoop", "nextActionLoopCount", "nextActionDelayBeats", "nextActionEnabled");

    interface Access {
        void execute(Runnable work);

        JsonObject apply(
                Object slot,
                String trackId,
                String expectedName,
                JsonObject changes,
                Runnable check,
                Runnable beforeWrite);
    }

    private final Scheduler scheduler;
    private final TrackWindow window;
    private final TransportSafety transportSafety;
    private final NativeRuntime natives;
    private final Holds holds;
    private final AtomicLongArray epochs = new AtomicLongArray(HostLimits.TRACKS);
    private Access access;
    private LauncherModule launcher;
    private static final String PENDING = LauncherStructureLock.SETTINGS_PENDING,
            QUARANTINE = LauncherStructureLock.SETTINGS;
    private static final String PENDING_HOLD = "A launcher.set settings write is pending.",
            QUARANTINE_HOLD = "A launcher.set settings write was dispatched and its outcome is unresolved.";

    LauncherSettingsModule(
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            LauncherModule launcher) {
        this(scheduler, window, transportSafety, natives, holds, (Access) null);
        this.launcher = launcher;
    }

    LauncherSettingsModule(
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            NativeRuntime natives,
            Holds holds,
            Access access) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.natives = natives;
        this.holds = holds;
        this.access = access;
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            final int index = i;
            Track t = window.at(i);
            ClipLauncherSlotBank bank = t.clipLauncherSlotBank();
            t.channelId().addValueObserver((StringValueChangedCallback) v -> epochs.incrementAndGet(index));
            t.exists().addValueObserver((BooleanValueChangedCallback) v -> epochs.incrementAndGet(index));
            bank.scrollPosition().addValueObserver((IntegerValueChangedCallback) v -> epochs.incrementAndGet(index));
            window.markSlotIdentityInterested(t);
        }
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public void validate(String method, JsonObject p) {
        validateParams(method, p);
    }

    static void validateParams(String method, JsonObject p) {
        if (!METHODS.contains(method))
            throw Errors.notApplied(Reason.UNKNOWN_METHOD, "Unknown launcher settings method");
        Set<String> required = Set.of("trackId", "slotIndex", "expectedClipName");
        Set<String> optional = new HashSet<>();
        if (method.equals("launcher.set")) {
            optional.addAll(WRITE_ORDER);
            optional.addAll(LauncherModule.LAUNCH_FIELDS);
        }
        Params.fields(p, required, optional);
        if (!DeviceSources.canonicalUuid(Params.string(p, "trackId")))
            throw Errors.notApplied(Reason.INVALID_PARAMS, "trackId must be a canonical UUID");
        Params.integer(p, "slotIndex", 0, HostLimits.SCENES - 1);
        if (Params.string(p, "expectedClipName").length() > 256)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "expectedClipName is too long");
        if (method.equals("launcher.set") && p.size() == 3)
            throw Errors.notApplied(Reason.INVALID_PARAMS, "Supply at least one setting");
        boolean launch = LauncherModule.hasLaunchFields(p), nextAction = false;
        for (String key : WRITE_ORDER) nextAction |= p.has(key);
        if (launch && nextAction)
            throw Errors.notApplied(
                    Reason.INVALID_PARAMS,
                    "Launch fields (launchQuantization, launchMode, useLoopStartAsQuantizationReference) and Next Action fields use different paths; send them in two calls");
        if (p.has("launchQuantization")) Params.string(p, "launchQuantization");
        if (p.has("launchMode")) Params.string(p, "launchMode");
        if (p.has("useLoopStartAsQuantizationReference")) Params.bool(p, "useLoopStartAsQuantizationReference");
        for (String key : WRITE_ORDER)
            if (p.has(key)) {
                if (key.equals("nextAction")) {
                    if (!Set.of("play_next", "stop").contains(Params.string(p, key)))
                        throw Errors.notApplied(Reason.INVALID_PARAMS, "nextAction must be play_next or stop");
                } else if (key.equals("nextActionLoopCount")) Params.integer(p, key, 1, 64);
                else if (key.endsWith("Enabled") || key.equals("nextActionLinkToLoop")) Params.bool(p, key);
                else Params.number(p, key, 0, 4194304);
            }
    }

    static boolean matches(JsonElement a, JsonElement b) {
        if (a == null || b == null) return false;
        if (a.isJsonPrimitive()
                && b.isJsonPrimitive()
                && a.getAsJsonPrimitive().isNumber()
                && b.getAsJsonPrimitive().isNumber()) return Math.abs(a.getAsDouble() - b.getAsDouble()) < 1e-8;
        return a.equals(b);
    }

    @Override
    public void dispatch(HostOperation op, String method, JsonObject p) {
        scheduler.check(op);
        boolean write = method.equals("launcher.set");
        if (write && LauncherModule.hasLaunchFields(p)) {
            if (launcher == null)
                throw Errors.notApplied(Reason.NATIVE_UNAVAILABLE, "Launch settings need the launcher module");
            launcher.launchSettings(op, p);
            return;
        }
        if (holds.held(PENDING))
            throw Errors.notApplied(Reason.BUSY, "Another native launcher settings operation is pending");
        if (write && holds.held(QUARANTINE))
            throw holds.blocked(QUARANTINE, "A launcher settings write is unresolved.", "Inspect launcher.get");
        String id = p.get("trackId").getAsString(),
                expected = p.get("expectedClipName").getAsString();
        int index = window.trackSlot(id), number = p.get("slotIndex").getAsInt();
        Track track = window.at(index);
        ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
        if (bank.scrollPosition().get() != 0 || number >= bank.itemCount().get())
            throw Errors.notApplied(
                    Reason.OUTSIDE_WINDOW, "Launcher slot is outside the current zero-offset scene window");
        ClipLauncherSlot slot = (ClipLauncherSlot) bank.getItemAt(number);
        if (!slot.hasContent().get()
                || !expected.equals(slot.name().get())
                || slot.sceneIndex().get() != number)
            throw Errors.notApplied(
                    Reason.STALE_CONTEXT, "Observe the exact occupied slot name and index before settings access");
        if (write && launcher != null) launcher.requireSettingsSafe(id, number);
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        if (access == null)
            try {
                access = new Bitwig614LauncherSettingsAccess(natives.get(op).access());
            } catch (ReflectiveOperationException e) {
                throw Errors.notApplied(
                        Reason.NATIVE_UNAVAILABLE,
                        "Exact launcher settings descriptors unavailable: " + e.getMessage());
            }
        JsonObject changes = new JsonObject();
        for (String key : WRITE_ORDER) if (p.has(key)) changes.add(key, p.get(key));
        long epoch = epochs.get(index);
        holds.inFlight(PENDING, PENDING_HOLD);
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch)
                throw Errors.notApplied(
                        Reason.STALE_CONTEXT, "Launcher track bank changed while settings operation was queued");
            if (write) transportSafety.guardStopped(safety);
        };
        try {
            access.execute(() -> {
                try {
                    check.run();
                    JsonObject result = access.apply(slot, id, expected, changes, check, () -> {
                        check.run();
                        op.markDispatched();
                        holds.place(QUARANTINE, QUARANTINE_HOLD);
                    });
                    check.run();
                    if (write)
                        result.addProperty(
                                "outcome",
                                Results.worst(result.getAsJsonObject("fields")
                                                .asMap()
                                                .values())
                                        .wire());
                    result.addProperty("trackId", id);
                    result.addProperty("slotIndex", number);
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!window.trackMatches(id, track)
                                        || bank.scrollPosition().get() != 0
                                        || slot.sceneIndex().get() != number
                                        || !expected.equals(slot.name().get()))
                                    throw Errors.notApplied(
                                            Reason.STALE_CONTEXT, "Launcher public target changed after native access");
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                op.result.complete(result);
                            },
                            0);
                } catch (RuntimeException | Error e) {
                    holds.release(PENDING);
                    op.fail(e);
                }
            });
        } catch (RuntimeException | Error e) {
            holds.release(PENDING);
            throw e;
        }
        op.result.whenComplete((r, e) -> holds.release(PENDING));
    }
}
