package dev.bitwigagent;

import dev.bitwigagent.core.MethodSpecs;

/** Bounded host windows and value resolution, read once from the protocol bundle. */
final class HostLimits {
    static final MethodSpecs.Limits LIMITS = MethodSpecs.load().limits();
    static final int TRACKS = LIMITS.trackWindow, SCENES = LIMITS.sceneWindow;
    static final int DEVICES = LIMITS.deviceWindow, SENDS = LIMITS.sendWindow;
    static final int RESOLUTION = LIMITS.parameterResolution;
    static final double TOLERANCE = LIMITS.normalizedTolerance;

    private HostLimits() {}
}
