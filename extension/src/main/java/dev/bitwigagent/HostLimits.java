package dev.bitwigagent;

import dev.bitwigagent.core.MethodSpecs;

/** Bounded host windows and value resolution, read once from the protocol bundle. */
final class HostLimits {
    static final MethodSpecs.Limits LIMITS = MethodSpecs.load().limits();
    static final int TRACKS = LIMITS.trackWindow, SCENES = LIMITS.sceneWindow;
    static final int DEVICES = LIMITS.deviceWindow, SENDS = LIMITS.sendWindow, RESOLUTION = 16384;
    static final double TOLERANCE = 1.0 / (RESOLUTION - 1) + 1e-9;

    private HostLimits() {}
}
