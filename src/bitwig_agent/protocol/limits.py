"""Sizes of the fixed host proxy windows; the single definition both the Python protocol and the extension derive from."""

TRACK_WINDOW = 1024
# Launcher slot proxies are tracks x scenes, so the scene window stays small while the track window is large.
SCENE_WINDOW = 16


def bundle_limits() -> dict[str, int]:
    return {"trackWindow": TRACK_WINDOW, "sceneWindow": SCENE_WINDOW}
