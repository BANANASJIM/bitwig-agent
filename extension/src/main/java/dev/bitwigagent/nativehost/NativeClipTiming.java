package dev.bitwigagent.nativehost;

import java.lang.reflect.Method;

/** Exact getters for a clip event's play and loop timing. */
final class NativeClipTiming {
    final Method timing, playStart, playStop, loop, loopEnabled, position, duration, doubleGet, boolGet;

    NativeClipTiming(NativeHost.Access a) throws ReflectiveOperationException {
        Class<?> number = a.type("model.floatProperty"), bool = a.type("model.booleanProperty");
        timing = a.exactMethod("clip.timed.timing", a.type("clip.timing"), false);
        playStart = a.exactMethod("clip.playRange.start", a.type("clip.playStart"), false);
        playStop = a.exactMethod("clip.playRange.stop", a.type("clip.playStop"), false);
        loop = a.exactMethod("clip.loopSettings.range", a.type("time.range"), false);
        loopEnabled = a.exactMethod("clip.loopSettings.enabled", bool, false);
        position = a.exactMethod("time.positioned.position", number, false);
        duration = a.exactMethod("time.sized.duration", number, false);
        doubleGet = a.exactMethod("model.floatProperty.get", double.class, false);
        boolGet = a.exactMethod("model.booleanProperty.get", boolean.class, false);
    }
}
