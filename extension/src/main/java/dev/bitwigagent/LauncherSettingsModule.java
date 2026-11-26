package dev.bitwigagent;

import static dev.bitwigagent.HostServices.failure;

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

    private final HostServices s;
    private final AtomicLongArray epochs = new AtomicLongArray(HostServices.TRACKS);
    private Access access;
    private LauncherModule launcher;
    private volatile boolean pending, quarantined;

    LauncherSettingsModule(HostServices s, LauncherModule launcher) {
        this(s, (Access) null);
        this.launcher = launcher;
        launcher.settingsWriteGuard = this::requireNoPendingOrUnresolved;
    }

    @Override
    public java.util.List<String> unresolved() {
        return pending || quarantined
                ? java.util.List.of("A launcher.set settings write is pending or unresolved.")
                : java.util.List.of();
    }

    void requireNoPendingOrUnresolved() {
        if (pending || quarantined)
            throw failure(
                    ErrorCode.UNRESOLVED_MUTATION,
                    "Launcher settings write is pending or unresolved; inspect state before further launcher writes");
    }

    LauncherSettingsModule(HostServices s, Access access) {
        this.s = s;
        this.access = access;
        for (int i = 0; i < HostServices.TRACKS; i++) {
            final int index = i;
            Track t = (Track) s.tracks.getItemAt(i);
            ClipLauncherSlotBank bank = t.clipLauncherSlotBank();
            t.channelId().addValueObserver((StringValueChangedCallback) v -> epochs.incrementAndGet(index));
            t.exists().addValueObserver((BooleanValueChangedCallback) v -> epochs.incrementAndGet(index));
            bank.scrollPosition().addValueObserver((IntegerValueChangedCallback) v -> epochs.incrementAndGet(index));
            bank.itemCount().markInterested();
            for (int j = 0; j < HostServices.SCENES; j++) {
                ClipLauncherSlot slot = (ClipLauncherSlot) bank.getItemAt(j);
                slot.name().markInterested();
                slot.hasContent().markInterested();
                slot.sceneIndex().markInterested();
            }
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
        if (!METHODS.contains(method)) throw failure(ErrorCode.UNKNOWN_METHOD, "Unknown launcher settings method");
        Set<String> required = Set.of("trackId", "slotIndex", "expectedClipName");
        Set<String> optional = new HashSet<>();
        if (method.equals("launcher.set")) {
            optional.addAll(WRITE_ORDER);
            optional.addAll(LauncherModule.LAUNCH_FIELDS);
        }
        Params.fields(p, required, optional);
        if (!DeviceSources.canonicalUuid(Params.string(p, "trackId")))
            throw failure(ErrorCode.INVALID_PARAMS, "trackId must be a canonical UUID");
        Params.integer(p, "slotIndex", 0, 15);
        if (Params.string(p, "expectedClipName").length() > 256)
            throw failure(ErrorCode.INVALID_PARAMS, "expectedClipName is too long");
        if (method.equals("launcher.set") && p.size() == 3)
            throw failure(ErrorCode.INVALID_PARAMS, "Supply at least one setting");
        boolean launch = LauncherModule.hasLaunchFields(p), nextAction = false;
        for (String key : WRITE_ORDER) nextAction |= p.has(key);
        if (launch && nextAction)
            throw failure(
                    ErrorCode.INVALID_PARAMS,
                    "Launch fields (launchQuantization, launchMode, useLoopStartAsQuantizationReference) and Next Action fields use different paths; send them in two calls");
        if (p.has("launchQuantization")) Params.string(p, "launchQuantization");
        if (p.has("launchMode")) Params.string(p, "launchMode");
        if (p.has("useLoopStartAsQuantizationReference")) Params.bool(p, "useLoopStartAsQuantizationReference");
        for (String key : WRITE_ORDER)
            if (p.has(key)) {
                if (key.equals("nextAction")) {
                    if (!Set.of("play_next", "stop").contains(Params.string(p, key)))
                        throw failure(ErrorCode.INVALID_PARAMS, "nextAction must be play_next or stop");
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
        s.check(op);
        boolean write = method.equals("launcher.set");
        if (write && LauncherModule.hasLaunchFields(p)) {
            if (launcher == null)
                throw failure(ErrorCode.LAUNCHER_UNAVAILABLE, "Launch settings need the launcher module");
            launcher.launchSettings(op, p);
            return;
        }
        if (pending) throw failure(ErrorCode.BUSY, "Another native launcher settings operation is pending");
        if (write && quarantined)
            throw failure(
                    ErrorCode.WRITE_QUARANTINED,
                    "An unresolved launcher settings write requires inspection and extension reload");
        String id = p.get("trackId").getAsString(),
                expected = p.get("expectedClipName").getAsString();
        int index = s.trackSlot(id), number = p.get("slotIndex").getAsInt();
        Track track = (Track) s.tracks.getItemAt(index);
        ClipLauncherSlotBank bank = track.clipLauncherSlotBank();
        if (bank.scrollPosition().get() != 0 || number >= bank.itemCount().get())
            throw failure(ErrorCode.OUTSIDE_WINDOW, "Launcher slot is outside the current zero-offset scene window");
        ClipLauncherSlot slot = (ClipLauncherSlot) bank.getItemAt(number);
        if (!slot.hasContent().get()
                || !expected.equals(slot.name().get())
                || slot.sceneIndex().get() != number)
            throw failure(
                    ErrorCode.STALE_CONTEXT, "Observe the exact occupied slot name and index before settings access");
        if (write && launcher != null) launcher.requireSettingsSafe(id, number);
        HostServices.SafetyObservation safety = write ? s.stoppedSafety(op) : null;
        if (access == null)
            try {
                access = new Bitwig614LauncherSettingsAccess(
                        s.nativeRuntime(op).access(ErrorCode.LAUNCHER_SETTINGS_UNAVAILABLE));
            } catch (ReflectiveOperationException e) {
                throw failure(
                        ErrorCode.LAUNCHER_SETTINGS_UNAVAILABLE,
                        "Exact launcher settings descriptors unavailable: " + e.getMessage());
            }
        JsonObject changes = new JsonObject();
        for (String key : WRITE_ORDER) if (p.has(key)) changes.add(key, p.get(key));
        long epoch = epochs.get(index);
        pending = true;
        Runnable check = () -> {
            s.check(op);
            if (epochs.get(index) != epoch)
                throw failure(
                        ErrorCode.STALE_CONTEXT, "Launcher track bank changed while settings operation was queued");
            if (write) s.guardStoppedSafety(safety);
        };
        try {
            access.execute(() -> {
                try {
                    check.run();
                    JsonObject result = access.apply(slot, id, expected, changes, check, () -> {
                        check.run();
                        op.dispatched = true;
                        quarantined = true;
                    });
                    check.run();
                    result.addProperty("trackId", id);
                    result.addProperty("slotIndex", number);
                    s.schedule(
                            op,
                            () -> {
                                check.run();
                                if (!s.trackMatches(id, track)
                                        || bank.scrollPosition().get() != 0
                                        || slot.sceneIndex().get() != number
                                        || !expected.equals(slot.name().get()))
                                    throw failure(
                                            ErrorCode.STALE_CONTEXT,
                                            "Launcher public target changed after native access");
                                if (write) quarantined = false;
                                pending = false;
                                op.result.complete(result);
                            },
                            0);
                } catch (RuntimeException | Error e) {
                    pending = false;
                    op.fail(e);
                }
            });
        } catch (RuntimeException | Error e) {
            pending = false;
            throw e;
        }
        op.result.whenComplete((r, e) -> pending = false);
    }
}
