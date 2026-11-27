package dev.bitwigagent.launcher;

import com.bitwig.extension.callback.*;
import com.bitwig.extension.controller.api.*;
import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Holds;
import dev.bitwigagent.core.HostLimits;
import dev.bitwigagent.core.HostOperation;
import dev.bitwigagent.core.PortHandle;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.SafetyObservation;
import dev.bitwigagent.core.Scheduler;
import dev.bitwigagent.host.LauncherStructureLock;
import dev.bitwigagent.host.TrackWindow;
import dev.bitwigagent.host.TransportSafety;
import java.util.*;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/** Source-bound launcher clip properties (timing and Next Action), independent of the note editor cursor. */
public final class LauncherSettingsModule {
    public static final List<String> WRITE_ORDER = List.of(
            "nextAction", "nextActionLinkToLoop", "nextActionLoopCount", "nextActionDelay", "nextActionEnabled");

    public interface Access {
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
    private final PortHandle<Access> port;
    private final Holds holds;
    private final AtomicLongArray epochs = new AtomicLongArray(HostLimits.TRACKS);
    private static final String PENDING = LauncherStructureLock.SETTINGS_PENDING,
            QUARANTINE = LauncherStructureLock.SETTINGS;
    private static final String PENDING_HOLD = "A clips.set settings write is pending.",
            QUARANTINE_HOLD = "A clips.set settings write was dispatched and its outcome is unresolved.";

    public LauncherSettingsModule(
            Scheduler scheduler,
            TrackWindow window,
            TransportSafety transportSafety,
            PortHandle<Access> port,
            Holds holds) {
        this.scheduler = scheduler;
        this.window = window;
        this.transportSafety = transportSafety;
        this.port = port;
        this.holds = holds;
        for (int i = 0; i < HostLimits.TRACKS; i++) {
            final int index = i;
            Track t = window.at(i);
            t.channelId().addValueObserver((StringValueChangedCallback) v -> epochs.incrementAndGet(index));
            t.exists().addValueObserver((BooleanValueChangedCallback) v -> epochs.incrementAndGet(index));
        }
    }

    public static boolean matches(JsonElement a, JsonElement b) {
        if (a == null || b == null) return false;
        if (a.isJsonPrimitive()
                && b.isJsonPrimitive()
                && a.getAsJsonPrimitive().isNumber()
                && b.getAsJsonPrimitive().isNumber()) return Math.abs(a.getAsDouble() - b.getAsDouble()) < 1e-8;
        return a.equals(b);
    }

    /** Reads, or writes {@code changes} to, the clip in the target's slot; {@code done} gets the native result. */
    void run(
            HostOperation op,
            LauncherState.Target target,
            JsonObject changes,
            boolean write,
            Consumer<JsonObject> done) {
        scheduler.check(op);
        if (holds.held(PENDING))
            throw Errors.notApplied(Reason.BUSY, "Another native launcher settings operation is pending");
        if (write && holds.held(QUARANTINE))
            throw holds.blocked(QUARANTINE, "A launcher settings write is unresolved.", "Inspect clips.get");
        String id = target.id(), expected = target.slot.api.name().get();
        int index = target.at.track, offset = target.at.offset, scene = target.scene();
        Track track = target.track;
        ClipLauncherSlot slot = target.slot.api;
        if (!slot.hasContent().get() || slot.sceneIndex().get() != scene)
            throw Errors.notApplied(Reason.NOT_READY, "The slot proxy does not show the scene yet.");
        SafetyObservation safety = write ? transportSafety.stopped(op) : null;
        Access access = port.get(op);
        long epoch = epochs.get(index);
        holds.inFlight(PENDING, PENDING_HOLD);
        Runnable check = () -> {
            scheduler.check(op);
            if (epochs.get(index) != epoch || window.sceneOffset() != offset)
                throw Errors.notApplied(
                        Reason.TARGET_CHANGED,
                        "The launcher track or scene window changed while the operation was queued");
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
                    scheduler.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!window.trackMatches(id, track)
                                        || slot.sceneIndex().get() != scene
                                        || !expected.equals(slot.name().get()))
                                    throw Errors.notApplied(
                                            Reason.TARGET_CHANGED,
                                            "Launcher public target changed after native access");
                                if (write) holds.release(QUARANTINE);
                                holds.release(PENDING);
                                done.accept(result);
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
