package dev.bitwigagent.core;

/** Bounded host windows and value resolution, read once from the protocol bundle. */
public final class HostLimits {
    public static final MethodSpecs.Limits LIMITS = MethodSpecs.load().limits();
    public static final int TRACKS = LIMITS.trackWindow, SCENES = LIMITS.sceneWindow;
    public static final int DEVICES = LIMITS.deviceWindow, SENDS = LIMITS.sendWindow;
    public static final int RESOLUTION = LIMITS.parameterResolution;
    public static final double TOLERANCE = LIMITS.normalizedTolerance;

    private HostLimits() {}
}
