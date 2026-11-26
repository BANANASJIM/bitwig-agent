package dev.bitwigagent;

import java.lang.reflect.Method;

/** Exact 6.1.4 getters for a clip event's play and loop timing. */
final class Bitwig614ClipTiming {
    final Method timing, playStart, playStop, loop, loopEnabled, position, duration, doubleGet, boolGet;

    Bitwig614ClipTiming(Bitwig614Runtime.Access a) throws ReflectiveOperationException {
        Class<?> number = a.type("com.bitwig.ramona.core.iYN"), bool = a.type("com.bitwig.ramona.core.lBQ");
        timing = a.exactMethod(a.type("eib"), "Nnn", a.type("mdC"), false);
        playStart = a.exactMethod(a.type("RsJ"), "TMF", a.type("etA"), false);
        playStop = a.exactMethod(a.type("RsJ"), "hy_", a.type("XbH"), false);
        loop = a.exactMethod(a.type("nLa"), "tp2", a.type("PTe"), false);
        loopEnabled = a.exactMethod(a.type("nLa"), "LaE", bool, false);
        position = a.exactMethod(a.type("vFg"), "Ktv", number, false);
        duration = a.exactMethod(a.type("iCu"), "Coi", number, false);
        doubleGet = a.exactMethod(number, "getDoubleValue", double.class, false);
        boolGet = a.exactMethod(bool, "getBoolValue", boolean.class, false);
    }
}
