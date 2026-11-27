"""The audio guard: link Bitwig's outputs to the silent sink from outside and refuse any other link."""

from bitwig_agent.audio import pipewire
from bitwig_agent.errors import bridge_error


def stray_links(listing: str) -> list[str]:
    """Links in `pw-link -l` output between a Bitwig port and anything but the silent sink."""
    stray, port = set(), ""
    for line in listing.splitlines():
        if line and not line[0].isspace():
            port = line.strip()
            continue
        peer = line.strip()[3:].strip()
        bitwig = [p for p in (port, peer) if p.startswith(f"{pipewire.NODE}:")]
        other = [p for p in (port, peer) if not p.startswith((f"{pipewire.NODE}:", f"{pipewire.SINK}:"))]
        if peer and bitwig and other:
            stray.add(f"{bitwig[0]} <-> {other[0]}")
    return sorted(stray)


def ports_ready() -> bool:
    code, out, _ = pipewire.run(["pw-link", "-o"])
    return code == 0 and set(pipewire.OUT_PORTS) <= {line.strip() for line in out.splitlines()}


def connect() -> tuple[str, list[str]]:
    """Link out1/out2 to the sink; returns the `pw-link -l` listing read back and its stray links."""
    for out, sink_port in pipewire.OUT_PORTS.items():
        pipewire.link([out, sink_port], "connect")
    code, listing, err = pipewire.run(["pw-link", "-l"])
    if code != 0:
        raise bridge_error("job_failed", f"pw-link -l failed: {err.strip() or code}")
    return listing, stray_links(listing)
