"""Bitwig processes: discovery from /proc, host RPC through the bitwig-agent CLI, and a clean quit."""

import os
import shlex
import signal
import subprocess
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from bitwig_agent.protocol.limits import DEFAULT_PORT

PROC = Path("/proc")
COMM = "BitwigStudio"
WINDOW = "^Bitwig Studio"
QUIT_SECONDS = 30


@dataclass(frozen=True)
class Process:
    pid: int
    display: str
    port: int
    session: int


def wait_until(ready: Callable[[], bool], seconds: float, interval: float = 0.5) -> bool:
    deadline = time.monotonic() + seconds
    while not ready():
        if time.monotonic() > deadline:
            return False
        time.sleep(interval)
    return True


def parse_environ(raw: bytes) -> dict[str, str]:
    pairs = (item.split("=", 1) for item in raw.decode("utf-8", "replace").split("\0") if "=" in item)
    return dict(pairs)


def session_of(stat: str) -> int:
    """The session id from /proc/<pid>/stat: the fourth field after the parenthesised command."""
    return int(stat.rpartition(")")[2].split()[3])


def processes() -> list[Process]:
    found = []
    for comm in PROC.glob("[0-9]*/comm"):
        try:
            if comm.read_text(encoding="utf-8").strip() != COMM:
                continue
            env = parse_environ((comm.parent / "environ").read_bytes())
            stat = (comm.parent / "stat").read_text(encoding="utf-8")
        except FileNotFoundError:  # the process exited during the scan
            continue
        port = int(env.get("BITWIG_AGENT_PORT", DEFAULT_PORT))
        found.append(Process(int(comm.parent.name), env.get("DISPLAY", ""), port, session_of(stat)))
    return found


def session_alive(session: int) -> bool:
    """Whether any live process of the session exists; a zombie (state Z, e.g. the unreaped bwrap leader) has ended."""
    for stat in PROC.glob("[0-9]*/stat"):
        try:
            text = stat.read_text(encoding="utf-8")
            if text.rpartition(")")[2].split()[0] != "Z" and session_of(text) == session:
                return True
        except FileNotFoundError:  # the process exited during the scan
            continue
    return False


def alive(pid: int) -> bool:
    return (PROC / str(pid)).exists()


def cli(args: list[str], port: int, run: Path | None = None) -> subprocess.CompletedProcess[str]:
    """Run the bitwig-agent CLI against port; within a run, append the call to its cli.log and keep the
    CLI's client-side state (the audio route's saved links) in the run directory, never the user's."""
    command = ["bitwig-agent", *args]
    env = {**os.environ, "BITWIG_AGENT_PORT": str(port), **({"XDG_CACHE_HOME": str(run)} if run else {})}
    done = subprocess.run(command, env=env, capture_output=True, text=True, encoding="utf-8", check=False)
    if run:
        with (run / "cli.log").open("a", encoding="utf-8") as handle:
            handle.write(f"$ {shlex.join(command)}  # {time.strftime('%H:%M:%S')} port={port} rc={done.returncode}\n")
            handle.write(done.stdout + done.stderr + "\n")
    return done


def dismiss_modal(display: str) -> None:
    """Bitwig draws its modals (e.g. Recover Projects) inside its own window; keys reach them, clicks may not."""
    command = ["xdotool", "search", "--name", WINDOW, "windowactivate", "--sync", "key", "Escape"]
    subprocess.run(command, env={**os.environ, "DISPLAY": display}, capture_output=True, check=False)


def quit_bitwig(process: Process, run: Path | None) -> str:
    """Save the disposable copy and Quit; TERM after QUIT_SECONDS. Returns clean, term or alive."""
    dismiss_modal(process.display)
    # Quit alone did not exit within 30 s on 2026-11-27 (likely a save prompt), so save first.
    cli(["project", "save"], process.port, run)
    cli(["actions", "invoke", "--actionId=Quit", "--discardUnsavedChanges=true"], process.port, run)
    if wait_until(lambda: not alive(process.pid), QUIT_SECONDS):
        return "clean"
    os.kill(process.pid, signal.SIGTERM)
    return "term" if wait_until(lambda: not alive(process.pid), QUIT_SECONDS) else "alive"
