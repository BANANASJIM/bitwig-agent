package dev.bitwigagent.bitwig614;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.host.NativeReadContext;
import dev.bitwigagent.launcher.LauncherSettingsModule;
import java.lang.reflect.Method;
import java.util.*;

/** Exact 6.1.4 bindings for a source-bound launcher slot; no cursor or GUI selection. */
public final class Bitwig614LauncherSettingsAccess implements LauncherSettingsModule.Access {
    private final Bitwig614Runtime.Access a;
    private final NativeReadContext context;
    private final Bitwig614TrackResolver resolver;
    private final Class<?> event, slotType, projectType;
    private final Method target, clip, slot, name, actionString;
    private final Bitwig614ClipTiming clipTiming;
    private final Map<String, Method> getters = new LinkedHashMap<>(), setters = new LinkedHashMap<>();
    private final Map<String, Object> actions = new LinkedHashMap<>();

    public Bitwig614LauncherSettingsAccess(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        this.a = a;
        context = new NativeReadContext(new Bitwig614ReadContextAccess(a));
        resolver = new Bitwig614TrackResolver(a);
        event = a.type("zZi");
        slotType = a.type("Us");
        projectType = a.type("com.bitwig.flt.document.core.master.g2L");
        Class<?> proxy = a.type("com.bitwig.flt.control_surface.proxy.ControlSurfaceProxy");
        target = a.exactMethod(proxy, "getTarget", Object.class, false);
        clip = a.exactMethod(
                a.type("com.bitwig.flt.control_surface.proxy.ClipLauncherSlotProxy"), "getClip", a.type("Ob"), false);
        slot = a.exactMethod(a.type("Ob"), "kFU", slotType, false);
        name = a.exactMethod(a.type("Ob"), "dJ_", String.class, false);
        clipTiming = new Bitwig614ClipTiming(a);
        Class<?> t = a.type("t3q"), action = a.type("bY");
        bind(t, "nextAction", "FPW", "cfM", action);
        bind(t, "nextActionEnabled", "Yt", "rGR", boolean.class);
        bind(t, "nextActionLinkToLoop", "Pus", "Lcd", boolean.class);
        bind(t, "nextActionLoopCount", "PdY", "TMF", int.class);
        bind(t, "nextActionDelayBeats", "sWR", "jFL", double.class);
        actionString = a.exactMethod(action, "MRY", String.class, false);
        for (Object value : action.getEnumConstants()) actions.put((String) a.invoke(actionString, value), value);
        if (!actions.containsKey("play_next") || !actions.containsKey("stop"))
            throw new NoSuchMethodException("Required follow-action enum values absent");
    }

    private void bind(Class<?> owner, String field, String get, String set, Class<?> type)
            throws ReflectiveOperationException {
        getters.put(field, a.exactMethod(owner, get, type, false));
        setters.put(field, a.exactMethod(owner, set, void.class, false, type));
    }

    @Override
    public void execute(Runnable work) {
        a.execute(work);
    }

    @Override
    public JsonObject apply(
            Object proxy,
            String trackId,
            String expectedName,
            JsonObject changes,
            Runnable check,
            Runnable beforeWrite) {
        a.documentThread();
        check.run();
        Object nativeSlot = a.invoke(target, proxy), value = a.invoke(clip, proxy);
        if (!slotType.isInstance(nativeSlot) || !event.isInstance(value) || a.invoke(slot, value) != nativeSlot)
            throw Errors.notApplied(
                    Reason.TARGET_CHANGED, "Launcher proxy does not resolve to one attached native clip slot.");
        Object track = resolver.require(resolver.slotTrack(nativeSlot), trackId, Bitwig614TrackResolver.Kind.CHANNEL);
        if (!expectedName.equals(a.invoke(name, value)))
            throw Errors.notApplied(Reason.TARGET_CHANGED, "Native clip name differs from expectedClipName.");
        Object owningProject = resolver.owner(value, projectType);
        Object[] models = {value, nativeSlot, track};
        String[] labels = {"clipDeleted", "slotDeleted", "trackDeleted", "projectDeleted"};
        NativeReadContext.Capture capture = context.capture(owningProject, models, labels, check, false);
        a.requireModelThread(value);
        Object timing = a.invoke(clipTiming.timing, value), loopRange = a.invoke(clipTiming.loop, timing);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("playStartBeats", a.invoke(clipTiming.position, a.invoke(clipTiming.playStart, timing)));
        properties.put("playStopBeats", a.invoke(clipTiming.position, a.invoke(clipTiming.playStop, timing)));
        properties.put("loopStartBeats", a.invoke(clipTiming.position, loopRange));
        properties.put("loopLengthBeats", a.invoke(clipTiming.duration, loopRange));
        properties.put("loopEnabled", a.invoke(clipTiming.loopEnabled, timing));
        JsonObject before = read(value, properties);
        Runnable guard = () -> {
            check.run();
            a.requireModelThread(value);
            context.guard(capture, models, labels, check);
            if (a.invoke(target, proxy) != nativeSlot
                    || a.invoke(clip, proxy) != value
                    || a.invoke(slot, value) != nativeSlot
                    || resolver.slotTrack(nativeSlot) != track)
                throw Errors.notApplied(Reason.TARGET_CHANGED, "Launcher target changed during native operation.");
        };
        guard.run();
        JsonObject fields = new JsonObject();
        for (String key : LauncherSettingsModule.WRITE_ORDER) {
            if (!changes.has(key)) continue;
            JsonElement requested = changes.get(key), original = before.get(key);
            if (!LauncherSettingsModule.matches(original, requested)) {
                guard.run();
                beforeWrite.run();
                {
                    Object input;
                    if (key.equals("nextAction")) input = actions.get(requested.getAsString());
                    else if (key.equals("nextActionLoopCount")) input = Integer.valueOf(requested.getAsInt());
                    else if (key.equals("nextActionDelayBeats")) input = Double.valueOf(requested.getAsDouble());
                    else input = Boolean.valueOf(requested.getAsBoolean());
                    if (input == null) throw Errors.notApplied(Reason.INVALID_PARAMS, "Unsupported native nextAction");
                    a.invoke(setters.get(key), value, input);
                }
            }
        }
        guard.run();
        JsonObject after = read(value, properties);
        for (var e : before.entrySet())
            if (!changes.has(e.getKey()) && !LauncherSettingsModule.matches(e.getValue(), after.get(e.getKey())))
                throw Errors.unknown(Reason.SIDE_EFFECT, "Native write changed omitted setting: " + e.getKey());
        for (var e : changes.entrySet()) {
            if (!LauncherSettingsModule.matches(e.getValue(), after.get(e.getKey())))
                throw Errors.unknown(Reason.READBACK_MISMATCH, "Native clip setting readback differs: " + e.getKey());
            boolean already = LauncherSettingsModule.matches(before.get(e.getKey()), e.getValue());
            fields.add(
                    e.getKey(),
                    Results.field(
                            e.getValue(),
                            after.get(e.getKey()),
                            already ? Outcome.ALREADY_MATCHED : Outcome.APPLIED,
                            null));
        }
        after.addProperty("name", (String) a.invoke(name, value));
        after.add("fields", fields);
        after.addProperty("nativeVersion", Bitwig614Runtime.VERSION);
        after.addProperty("nativeJarSha256", a.actualHash());
        after.addProperty("scope", "source_bound_launcher_clip_settings");
        after.addProperty("selectionSideEffect", "none");
        after.addProperty("verificationScope", "native_model_values_not_playback_or_save_reopen");
        return after;
    }

    private JsonObject read(Object value, Map<String, Object> properties) {
        JsonObject r = new JsonObject();
        for (var e : properties.entrySet())
            if (e.getKey().equals("loopEnabled"))
                r.addProperty(e.getKey(), (Boolean) a.invoke(clipTiming.boolGet, e.getValue()));
            else r.addProperty(e.getKey(), (Double) a.invoke(clipTiming.doubleGet, e.getValue()));
        for (var e : getters.entrySet()) {
            Object v = a.invoke(e.getValue(), value);
            if (e.getKey().equals("nextAction")) r.addProperty(e.getKey(), (String) a.invoke(actionString, v));
            else if (v instanceof Boolean) r.addProperty(e.getKey(), (Boolean) v);
            else r.addProperty(e.getKey(), (Number) v);
        }
        return r;
    }
}
