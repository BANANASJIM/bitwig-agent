package dev.bitwigagent.nativehost;

import com.google.gson.*;
import dev.bitwigagent.core.Errors;
import dev.bitwigagent.core.Outcome;
import dev.bitwigagent.core.Reason;
import dev.bitwigagent.core.Results;
import dev.bitwigagent.launcher.LauncherSettingsModule;
import java.lang.reflect.Method;
import java.util.*;

/** Exact bindings for a source-bound launcher slot; no cursor or GUI selection. */
public final class NativeLauncherSettingsAccess implements LauncherSettingsModule.Access {
    private final NativeHost.Access a;
    private final NativeLauncherClip launcher;
    private final Method actionString;
    private final NativeClipTiming clipTiming;
    private final Map<String, Method> getters = new LinkedHashMap<>(), setters = new LinkedHashMap<>();
    private final Map<String, Object> actions = new LinkedHashMap<>();

    public NativeLauncherSettingsAccess(NativeHost.Access a) throws ReflectiveOperationException {
        this.a = a;
        launcher = new NativeLauncherClip(a);
        clipTiming = new NativeClipTiming(a);
        Class<?> action = a.type("clip.followAction");
        bind("nextAction", "clip.launch.nextAction", "clip.launch.setNextAction", action);
        bind("nextActionEnabled", "clip.launch.nextActionEnabled", "clip.launch.setNextActionEnabled", boolean.class);
        bind(
                "nextActionLinkToLoop",
                "clip.launch.nextActionLinkToLoop",
                "clip.launch.setNextActionLinkToLoop",
                boolean.class);
        bind("nextActionLoopCount", "clip.launch.nextActionLoopCount", "clip.launch.setNextActionLoopCount", int.class);
        bind(
                "nextActionDelayBeats",
                "clip.launch.nextActionDelayBeats",
                "clip.launch.setNextActionDelayBeats",
                double.class);
        actionString = a.exactMethod("clip.followAction.wireName", String.class, false);
        for (Object value : action.getEnumConstants()) actions.put((String) a.invoke(actionString, value), value);
        if (!actions.containsKey("play_next") || !actions.containsKey("stop"))
            throw new NoSuchMethodException("Required follow-action enum values absent");
    }

    private void bind(String field, String get, String set, Class<?> type) throws ReflectiveOperationException {
        getters.put(field, a.exactMethod(get, type, false));
        setters.put(field, a.exactMethod(set, void.class, false, type));
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
        NativeLauncherClip.Bound bound = launcher.clip(proxy, trackId, expectedName, check);
        Object value = bound.clip;
        Object timing = a.invoke(clipTiming.timing, value), loopRange = a.invoke(clipTiming.loop, timing);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("playStartBeats", a.invoke(clipTiming.position, a.invoke(clipTiming.playStart, timing)));
        properties.put("playStopBeats", a.invoke(clipTiming.position, a.invoke(clipTiming.playStop, timing)));
        properties.put("loopStartBeats", a.invoke(clipTiming.position, loopRange));
        properties.put("loopLengthBeats", a.invoke(clipTiming.duration, loopRange));
        properties.put("loopEnabled", a.invoke(clipTiming.loopEnabled, timing));
        JsonObject before = read(value, properties);
        Runnable guard = bound::guard;
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
        after.addProperty("name", (String) a.invoke(launcher.name, value));
        after.add("fields", fields);
        a.provenance(after);
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
