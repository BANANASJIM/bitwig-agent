"""The audio guard: link Bitwig's outputs to the silent sink from outside and refuse any other link."""

from bitwig_agent.audio import pipewire
from bitwig_agent.errors import bridge_error


def outside_sink(source: str, target: str) -> bool:
    """A link between Bitwig and anything but the silent sink, by node name (a port's part before the colon)."""
    nodes = [name.partition(":")[0] for name in (source, target)]
    bitwig = ["bitwig" in node.lower() for node in nodes]
    if bitwig[0] == bitwig[1]:
        return False
    return nodes[1 if bitwig[0] else 0] != pipewire.SINK


def stray_links(listing: str) -> list[tuple[str, str]]:
    """The (output, input) links in `pw-link -l` output between Bitwig and anything but the silent sink."""
    return [(out, target) for out, target in pipewire.all_links(listing) if outside_sink(out, target)]


def listing() -> str:
    code, out, err = pipewire.run(["pw-link", "-l"])
    if code != 0:
        raise bridge_error("job_failed", f"pw-link -l failed: {err.strip() or code}")
    return out


def ports_ready() -> bool:
    code, out, _ = pipewire.run(["pw-link", "-o"])
    return code == 0 and set(pipewire.OUT_PORTS) <= {line.strip() for line in out.splitlines()}


def connect() -> str:
    """Link out1/out2 to the sink; returns the `pw-link -l` listing read back."""
    for out, sink_port in pipewire.OUT_PORTS.items():
        pipewire.link([out, sink_port], "connect")
    return listing()


def disconnect_strays() -> list[tuple[str, str]]:
    """Remove every link between Bitwig and anything but the sink; returns the links removed."""
    strays = stray_links(listing())
    for out, target in strays:
        pipewire.link(["-d", out, target], "disconnect")
    return strays
