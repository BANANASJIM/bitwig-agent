"""Record every PipeWire link between Bitwig and anything but the silent sink until SIGTERM or SIGINT.

Two independent sources: `pw-dump --monitor` (an event stream that sees even short-lived links) and
a `pw-link -l` poll every 50 ms; both apply audio.outside_sink. It prints "monitor started" only once
the first pw-dump snapshot is applied, and its summary line says whether pw-dump exited early or the poller died.

  PYTHONPATH=scripts python -m native.monitor LABEL >> monitor.log
"""

import json
import signal
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Any

from bitwig_agent.audio import pipewire

from . import audio

POLL_SECONDS = 0.05
STARTED = "monitor started"


def stamp() -> str:
    return time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"


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


@dataclass
class Graph:
    """The PipeWire nodes and links seen so far, and the Bitwig links outside the sink among them."""

    nodes: dict[Any, str] = field(default_factory=dict)
    links: dict[Any, dict[str, Any]] = field(default_factory=dict)
    seen: set[str] = field(default_factory=set)
    bad: dict[Any, str] = field(default_factory=dict)
    events: int = 0

    def apply(self, objects: list[dict[str, Any]]) -> list[str]:
        """Update from pw-dump objects; returns the links outside the sink not reported before."""
        for obj in objects:
            self.events += 1
            oid, info = obj.get("id"), obj.get("info")
            if info is None:
                self.nodes.pop(oid, None)
                self.links.pop(oid, None)
            elif obj.get("type", "").endswith("Node"):
                self.nodes[oid] = (info.get("props") or {}).get("node.name", self.nodes.get(oid, ""))
            elif obj.get("type", "").endswith("Link"):
                self.links[oid] = {**self.links.get(oid, {}), **info}
        fresh = []
        for lid, info in self.links.items():
            out, sink = self.nodes.get(info.get("output-node-id"), ""), self.nodes.get(info.get("input-node-id"), "")
            if "bitwig" in f"{out} {sink}".lower():
                self.seen.add(f"{out} -> {sink}")
            if audio.outside_sink(out, sink) and lid not in self.bad:
                self.bad[lid] = f"pw-dump link {lid}: {out} -> {sink}"
                fresh.append(self.bad[lid])
        return fresh


@dataclass
class Monitor:
    """Both link sources of one monitored run, and their summary."""

    label: str
    graph: Graph = field(default_factory=Graph)
    polled: list[str] = field(default_factory=list)
    polls: int = 0
    dump_exited: bool = False
    poller_died: bool = False
    lock: threading.Lock = field(default_factory=threading.Lock)
    stop: threading.Event = field(default_factory=threading.Event)
    ready: threading.Event = field(default_factory=threading.Event)  # the first pw-dump snapshot is applied
    started: float = field(default_factory=time.time)

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
                self.ready.set()
        self.dump_exited = not self.stop.is_set()

    def poll(self) -> None:
        try:
            while not self.stop.is_set():
                listing = pipewire.run(["pw-link", "-l"])[1]
                self.polls += 1
                for out, target in audio.stray_links(listing):
                    self.polled.append(f"{out} |-> {target}")
                    self.report(f"pw-link: {self.polled[-1]}")
                self.stop.wait(POLL_SECONDS)
        finally:
            self.poller_died = not self.stop.is_set()

    def summary(self) -> str:
        with self.lock:
            return (
                f"[{self.label}] {stamp()} monitor stopped after {time.time() - self.started:.1f}s: "
                f"pw-dump events={self.graph.events} pw-link polls={self.polls} bitwig links outside "
                f"{pipewire.SINK}: dump={len(self.graph.bad)} poll={len(self.polled)}; "
                f"pw-dump exited early: {'yes' if self.dump_exited else 'no'}; "
                f"pw-link poller died: {'yes' if self.poller_died else 'no'}; "
                f"bitwig links seen: {sorted(self.graph.seen) or 'none'}"
            )


def main() -> None:
    monitor = Monitor(sys.argv[1] if len(sys.argv) > 1 else "run")
    dump = subprocess.Popen(["pw-dump", "--monitor", "--no-colors"], stdout=subprocess.PIPE, text=True)
    threading.Thread(target=monitor.read_dump, args=(dump.stdout,), daemon=True).start()
    poller = threading.Thread(target=monitor.poll, daemon=True)
    poller.start()
    if monitor.ready.wait(10):
        print(f"[{monitor.label}] {stamp()} {STARTED}", flush=True)

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
