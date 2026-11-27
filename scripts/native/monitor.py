"""Record every PipeWire link from a Bitwig node to an alsa_output.* node until SIGTERM or SIGINT.

Two independent sources: `pw-dump --monitor` (an event stream that sees even short-lived links) and
a `pw-link -l` poll every 50 ms. Prints one summary line when stopped. GUARD_TARGET overrides the
target node prefix, only for a positive control (GUARD_TARGET=bwtest must report the silent links).

  PYTHONPATH=scripts python -m native.monitor LABEL >> monitor.log
"""

import json
import os
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any

TARGET = os.environ.get("GUARD_TARGET", "alsa_output")
POLL_SECONDS = 0.05
NAME_KEYS = ("node.name", "application.name", "application.process.binary", "client.name")


def stamp() -> str:
    return time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"


def is_bitwig(props: dict[str, Any]) -> bool:
    return any("bitwig" in str(props.get(key, "")).lower() for key in NAME_KEYS)


def split_dump(buffer: str) -> tuple[list[dict[str, Any]], str]:
    """The complete top-level JSON arrays at the start of buffer, flattened, and the unparsed rest."""
    decoder, objects = json.JSONDecoder(), []
    while buffer := buffer.lstrip():
        try:
            batch, end = decoder.raw_decode(buffer)
        except ValueError:
            break
        objects += batch
        buffer = buffer[end:]
    return objects, buffer


def bad_polls(listing: str, target: str) -> list[str]:
    """`pw-link -l` lines linking a Bitwig output to a target port."""
    bad, port = [], ""
    for line in listing.splitlines():
        if not line.startswith(" "):
            port = line
        elif "|->" in line and "bitwig" in port.lower() and target in line:
            bad.append(f"{port} {line.strip()}")
    return bad


@dataclass
class Graph:
    """The PipeWire nodes and links seen so far, and the Bitwig links to the target among them."""

    target: str
    nodes: dict[Any, dict[str, Any]] = field(default_factory=dict)
    links: dict[Any, dict[str, Any]] = field(default_factory=dict)
    seen: set[str] = field(default_factory=set)
    bad: dict[Any, str] = field(default_factory=dict)
    events: int = 0

    def apply(self, objects: list[dict[str, Any]]) -> list[str]:
        """Update from pw-dump objects; returns the target links not reported before."""
        for obj in objects:
            self.events += 1
            oid, info = obj.get("id"), obj.get("info")
            if info is None:
                self.nodes.pop(oid, None)
                self.links.pop(oid, None)
            elif obj.get("type", "").endswith("Node"):
                self.nodes.setdefault(oid, {}).update(info.get("props") or {})
            elif obj.get("type", "").endswith("Link"):
                self.links[oid] = {**self.links.get(oid, {}), **info}
        fresh = []
        for lid, info in self.links.items():
            out, sink = self.nodes.get(info.get("output-node-id"), {}), self.nodes.get(info.get("input-node-id"), {})
            if not is_bitwig(out):
                continue
            self.seen.add(f"{out.get('node.name')} -> {sink.get('node.name')}")
            if str(sink.get("node.name", "")).startswith(self.target) and lid not in self.bad:
                self.bad[lid] = f"pw-dump link {lid}: {out.get('node.name')} -> {sink.get('node.name')}"
                fresh.append(self.bad[lid])
        return fresh


@dataclass
class Monitor:
    """Both link sources of one monitored run, and their summary."""

    label: str
    target: str = TARGET
    graph: Graph = field(init=False)
    polled: list[str] = field(default_factory=list)
    polls: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)
    stop: threading.Event = field(default_factory=threading.Event)
    started: float = field(default_factory=time.time)

    def __post_init__(self) -> None:
        self.graph = Graph(self.target)

    def report(self, line: str) -> None:
        print(f"[{self.label}] BAD {stamp()} {line}", flush=True)

    def read_dump(self, stream: Any) -> None:
        buffer = ""
        for chunk in iter(stream.readline, ""):
            buffer += chunk
            if chunk.startswith("]"):  # pw-dump closes each top-level array at column 0
                objects, buffer = split_dump(buffer)
                with self.lock:
                    for line in self.graph.apply(objects):
                        self.report(line)

    def poll(self) -> None:
        while not self.stop.is_set():
            command = ["pw-link", "-l"]
            listing = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", check=False).stdout
            self.polls += 1
            for line in bad_polls(listing, self.target):
                self.polled.append(line)
                self.report(f"pw-link: {line}")
            self.stop.wait(POLL_SECONDS)

    def summary(self) -> str:
        with self.lock:
            return (
                f"[{self.label}] {stamp()} monitor stopped after {time.time() - self.started:.1f}s: "
                f"pw-dump events={self.graph.events} pw-link polls={self.polls} bitwig->{self.target} links: "
                f"dump={len(self.graph.bad)} poll={len(self.polled)}; bitwig links seen: {sorted(self.graph.seen) or 'none'}"
            )


def main() -> None:
    monitor = Monitor(sys.argv[1] if len(sys.argv) > 1 else "run")
    dump = subprocess.Popen(["pw-dump", "--monitor", "--no-colors"], stdout=subprocess.PIPE, text=True)
    threading.Thread(target=monitor.read_dump, args=(dump.stdout,), daemon=True).start()
    poller = threading.Thread(target=monitor.poll, daemon=True)
    print(f"[{monitor.label}] {stamp()} monitor started", flush=True)
    poller.start()

    def finish(*_: object) -> None:
        monitor.stop.set()
        poller.join(1)
        dump.terminate()
        print(monitor.summary(), flush=True)
        sys.exit(0)

    signal.signal(signal.SIGTERM, finish)
    signal.signal(signal.SIGINT, finish)
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
