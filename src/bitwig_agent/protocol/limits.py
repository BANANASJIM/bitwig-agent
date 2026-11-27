"""Bounds and sizes shared by the Python protocol, the client and the extension; the bundle carries them to Java."""

TRACK_WINDOW = 1024
# Launcher slot proxies are tracks x scenes, so the scene window stays small while the track window is large.
SCENE_WINDOW = 16
DEVICE_WINDOW = 16
SEND_WINDOW = 8
REMOTE_PAGE_SLOTS = 8
PARAMETER_RESOLUTION = 16384
# One host step of a normalized value; the 1e-9 absorbs float rounding of the step.
NORMALIZED_TOLERANCE = 1 / (PARAMETER_RESOLUTION - 1) + 1e-9

MIN_TIMEOUT_MS = 100
DEFAULT_TIMEOUT_MS = 5000
MAX_TIMEOUT_MS = 10000
# Ceiling for calls that verify against a full graph read per entry or wait for a native job.
LONG_TIMEOUT_MS = 60000
LONG_DEFAULT_TIMEOUT_MS = 30000
# How long the client waits past a request's deadline for the host's own answer.
RESPONSE_GRACE_MS = 1000

# What an agent reads per tool: the advertised description and its example fit this many characters.
DESCRIPTION_LIMIT = 600

MAX_REQUEST_BYTES = 1 << 20
MAX_RESPONSE_BYTES = 16 << 20

DEFAULT_PORT = 61172

BEAT_GRID_DIVISOR = 128
BEAT_GRID = 1 / BEAT_GRID_DIVISOR
BEAT_GRID_LABEL = f"1/{BEAT_GRID_DIVISOR}"
MAX_CLIP_BEATS = 4096
# Bitwig 6.1.4 stores a note duration to this step (native readback: 0.225 reads back as 0.22500038146972656).
DURATION_RESOLUTION_DIVISOR = 1 << 20
DURATION_RESOLUTION = 1 / DURATION_RESOLUTION_DIVISOR
# Bitwig clamps an automation point's curve to this magnitude (inspected 6.1.4/6.1.7 JAR).
MAX_CURVE = 1.0


def mebibytes(size: int) -> str:
    return f"{size >> 20} MiB"


def bundle_limits() -> dict[str, int | float]:
    return {
        "trackWindow": TRACK_WINDOW,
        "sceneWindow": SCENE_WINDOW,
        "deviceWindow": DEVICE_WINDOW,
        "sendWindow": SEND_WINDOW,
        "remotePageSlots": REMOTE_PAGE_SLOTS,
        "parameterResolution": PARAMETER_RESOLUTION,
        "normalizedTolerance": NORMALIZED_TOLERANCE,
        "minTimeoutMs": MIN_TIMEOUT_MS,
        "maxRequestBytes": MAX_REQUEST_BYTES,
        "maxResponseBytes": MAX_RESPONSE_BYTES,
        "defaultPort": DEFAULT_PORT,
        "beatGrid": BEAT_GRID,
        "durationResolution": DURATION_RESOLUTION,
        "maxClipLength": MAX_CLIP_BEATS,
        "responseGraceMs": RESPONSE_GRACE_MS,
        "maxCurve": MAX_CURVE,
    }
