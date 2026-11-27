"""The harness commands: init, up, down, reset, status, cli, shot and proof."""

import contextlib
import json
import os
import pwd
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

from bitwig_agent.audio import pipewire

from . import audio, bitwig, doctor, evidence, lock, monitor, sandbox
from .config import Config, Host

EXTENSION = "BitwigAgent.bwextension"
LICENCE = (".activation-*", ".eula-agreed-*")
PORTS_SECONDS = 60
STATUS_SECONDS = 180
LOCK_SECONDS = 30
MONITOR_SECONDS = 10
KILL_SECONDS = 10
TOKEN = "BWA_NATIVE_RUN"  # the run id native-up prints; down, reset, cli and shot require it
REFUSED_CLI = ("allowAudible", "restore")  # audible captures and route restores never go through the harness


def account() -> pwd.struct_passwd:
    """Java reads user.home from passwd, so the sandbox binds over the passwd home, never $HOME."""
    return pwd.getpwuid(os.getuid())


def java_tmpdir(user: str) -> Path:
    """Bitwig's java.io.tmpdir."""
    return Path(f"/tmp/bitwig-{user}")


def host_mounts(config: Config, host: Host) -> list[sandbox.Mount]:
    user = account()
    return sandbox.mounts(host.root, config.iso(host), Path(user.pw_dir), java_tmpdir(user.pw_name))


def snapshot_roots(table: list[sandbox.Mount]) -> list[Path]:
    return [m.target for m in table if m.snapshot]


def init(config: Config, name: str) -> None:
    """Build the isolated home of a host from your own installation; never runs Bitwig, never overwrites."""
    host = config.host(name)
    iso = config.iso(host)
    for path in sandbox.iso_dirs(host_mounts(config, host), iso):
        path.mkdir(parents=True, exist_ok=True)
    (iso / "BWS/Extensions").mkdir(exist_ok=True)
    real = Path(account().pw_dir) / ".BitwigStudio"
    for pattern in LICENCE:
        for source in real.glob(pattern):
            if not (iso / "BitwigStudio" / source.name).exists():
                shutil.copy2(source, iso / "BitwigStudio" / source.name)
                print(f"copied {source.name}")
    if not (iso / "BitwigStudio/prefs").exists():
        shutil.copytree(host.prefs_seed, iso / "BitwigStudio/prefs")
        print(f"copied prefs from {host.prefs_seed}")
    if not config.fixture.exists():
        shutil.copytree(config.fixture_seed, config.fixture)
        print(f"copied fixture to {config.fixture}")
    print(f"{name}: isolated home {iso}")


def install_extension(iso: Path, extension: str) -> str | None:
    """Copy the extension (relative to the caller's directory) into the isolated Extensions; returns its sha256."""
    target = iso / "BWS/Extensions" / EXTENSION
    if extension:
        shutil.copy2(Path(os.environ.get("INIT_CWD", ".")) / extension, target)
    return evidence.sha256(target) if target.is_file() else None


def refuse_running() -> None:
    running = bitwig.processes()
    if running:
        listed = ", ".join(f"pid {p.pid} on {p.display or 'no display'}" for p in running)
        raise SystemExit(f"refusing: Bitwig already running ({listed}); quit it first")


def stop_sandbox(run: Path, session: int, reason: str) -> bool:
    """Fail closed: cut every Bitwig link outside the sink, then end the whole sandbox (TERM, then KILL).

    Returns whether the sandbox is confirmed gone; a failed cut is recorded and never skips the kill.
    """
    evidence.update_run(run, aborted=reason)
    try:
        evidence.update_run(run, strayLinksCut=audio.disconnect_strays())
    except Exception as exc:  # noqa: BLE001 - whatever the cut raised is recorded; the kill below must run
        evidence.update_run(run, cutFailed=f"{type(exc).__name__}: {exc}")
    for sig in (signal.SIGTERM, signal.SIGKILL):
        if bitwig.session_alive(session):
            with contextlib.suppress(ProcessLookupError):  # the last process exited after the check
                os.killpg(session, sig)
        if bitwig.wait_until(lambda: not bitwig.session_alive(session), KILL_SECONDS):
            return True
    return False


def spawn(argv: list[str], output: Path, **options: Any) -> int:
    with output.open("ab") as handle:
        child = subprocess.Popen(
            argv, stdin=subprocess.DEVNULL, stdout=handle, stderr=subprocess.STDOUT, start_new_session=True, **options
        )
    return child.pid


def managed(session: int) -> bitwig.Process | None:
    return next((p for p in bitwig.processes() if p.session == session), None)


def start_monitor(run: Path) -> int:
    """The link monitor, once it holds its first pw-dump snapshot (before Bitwig starts)."""
    log = run / "monitor.log"
    pid = spawn([sys.executable, "-m", "native.monitor", run.name], log)
    if not bitwig.wait_until(lambda: monitor.STARTED in log.read_text(encoding="utf-8"), MONITOR_SECONDS, 0.1):
        os.kill(pid, signal.SIGTERM)
        raise SystemExit(f"refusing: the link monitor did not start within {MONITOR_SECONDS}s; see {log}")
    return pid


def start_run(config: Config, host: Host, extension: str, jar: str | None) -> tuple[Path, list[sandbox.Mount]]:
    """The run directory with its project copy, run.json and the real-directory snapshot before launch."""
    run = config.runs / f"{time.strftime('%Y-%m-%d-%H%M%S')}-{host.name}"
    run.mkdir(parents=True)
    shutil.copytree(config.fixture, run / "project" / config.fixture.name)
    table = host_mounts(config, host)
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=Path(__file__).parent, capture_output=True, text=True, check=False
    ).stdout.strip()
    evidence.update_run(
        run,
        host=host.name,
        root=str(host.root),
        jarSha256=jar,
        expectedJarSha256=doctor.expected_jar(host),
        extensionSha256=install_extension(config.iso(host), extension),
        commit=commit,
        port=config.port,
        display=config.display,
        mounts=[str(m.target) for m in table],
        started=time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    )
    for path in snapshot_roots(table):
        path.mkdir(parents=True, exist_ok=True)  # bwrap would create a missing mount point during the run
    evidence.write_snapshot(run / "real-before.tsv.gz", evidence.snapshot(snapshot_roots(table)))
    return run, table


def guard_audio(run: Path) -> None:
    """Link Bitwig to the silent sink from outside; a link anywhere else fails the run."""
    if not bitwig.wait_until(audio.ports_ready, PORTS_SECONDS, 0.1):
        raise RuntimeError(f"no '{pipewire.NODE}' output ports within {PORTS_SECONDS}s")
    listing = audio.connect()
    (run / "pw-link.txt").write_text(listing, encoding="utf-8")
    stray = [f"{out} -> {target}" for out, target in audio.stray_links(listing)]
    evidence.update_run(run, strayLinks=stray)
    if stray:
        raise RuntimeError(f"Bitwig linked outside {pipewire.SINK}: {stray}")


def settle(config: Config, run: Path, session: int) -> int:
    """Everything between launch and a usable instance; returns the Bitwig pid. Any failure raises."""
    guard_audio(run)
    process = managed(session)
    if process is None:
        raise RuntimeError("the sandboxed BitwigStudio process is gone")
    mountinfo = (bitwig.PROC / str(process.pid) / "mountinfo").read_text(encoding="utf-8")
    (run / "mountinfo.txt").write_text(mountinfo, encoding="utf-8")
    evidence.update_run(run, pid=process.pid)
    if not bitwig.wait_until(lambda: bitwig.cli(["status"], config.port, run).returncode == 0, STATUS_SECONDS, 3):
        raise RuntimeError(f"the extension did not answer status on port {config.port}")
    time.sleep(2)
    bitwig.dismiss_modal(config.display)  # a stale crash-recovery dialog would block Save and focus-dependent Actions
    silent = bitwig.cli(["audio", "route", "--mode=silent"], config.port, run)
    (run / "silent-route.json").write_text(silent.stdout, encoding="utf-8")
    return process.pid


def up(config: Config, name: str, extension: str) -> Path:
    host = config.host(name)
    expected, found = doctor.expected_jar(host), doctor.jar_sha(host)
    if found != expected:
        raise SystemExit(f"refusing: {host.root}/bin/bitwig.jar sha256 {found} is not {name}'s {expected}")
    if not (config.iso(host) / "BitwigStudio/prefs").is_dir():
        raise SystemExit(f"refusing: {config.iso(host)} is not initialised; run native-init {name}")
    refuse_running()
    fd = lock.acquire()
    if fd is None:
        raise SystemExit(f"refusing: another run holds the harness lock: {lock.owner()}")
    run, table = start_run(config, host, extension, found)
    pipewire.ensure_sink()
    monitor_pid = start_monitor(run)
    argv = sandbox.argv(table, run / "project" / config.fixture.name / f"{config.fixture.name}.bwproject")
    (run / "bwrap-argv.txt").write_text(subprocess.list2cmdline(argv) + "\n", encoding="utf-8")
    env = sandbox.environment(os.environ, config.display, config.port)
    session = spawn(argv, run / "bitwig.out", env=env, pass_fds=(fd,))
    try:
        lock.write_owner(
            {"run": str(run), "host": name, "session": session, "monitor": monitor_pid, "port": config.port}
        )
        pid = settle(config, run, session)
    except BaseException as exc:  # any failure, Ctrl-C included, must not leave a running sandbox
        stopped = stop_sandbox(run, session, f"{type(exc).__name__}: {exc}")
        state = "sandbox stopped" if stopped else f"sandbox NOT stopped: session {session} is still running"
        raise SystemExit(
            f"ABORTED: {exc}; {state}; close the evidence in {run} with\n"
            f"export {TOKEN}={run.name}\npixi run native-down"
        ) from exc
    print(f"up: {name} pid {pid} port {config.port} display {config.display}; evidence {run}")
    print(f"export {TOKEN}={run.name}")
    return run


def owned_run() -> tuple[dict[str, Any], Path]:
    """The current run, refused unless the caller holds its id in BWA_NATIVE_RUN."""
    owner = lock.owner()
    if owner is None:
        raise SystemExit("no harness run; start one with native-up")
    run = Path(owner["run"])
    if os.environ.get(TOKEN) != run.name:
        raise SystemExit(f"refusing: the current run is not yours; native-up printed its id as {TOKEN}=<run id>")
    return owner, run


def down(config: Config) -> None:
    if lock.owner() is None:
        for unmanaged in bitwig.processes():
            if unmanaged.display == config.display:
                print(f"no harness run; quit unmanaged Bitwig {unmanaged.pid}: {bitwig.quit_bitwig(unmanaged, None)}")
        return
    owner, run = owned_run()
    process = managed(owner["session"])
    quit_kind = bitwig.quit_bitwig(process, run) if process else evidence.read_run(run).get("aborted", "gone")
    if quit_kind == "alive" or not bitwig.wait_until(lambda: not lock.held(), LOCK_SECONDS):
        raise SystemExit(
            f"FAIL: the sandbox is still running (quit: {quit_kind}, lock held); the monitor keeps recording "
            "and the run stays owned. Inspect with native-status, end it, then run native-down again."
        )
    if bitwig.alive(owner["monitor"]):
        os.kill(owner["monitor"], signal.SIGTERM)
    bitwig.wait_until(lambda: not bitwig.alive(owner["monitor"]), 5)
    host = config.host(owner["host"])
    after = evidence.snapshot(snapshot_roots(host_mounts(config, host)))
    evidence.write_snapshot(run / "real-after.tsv.gz", after)
    changed = evidence.diff(evidence.read_snapshot(run / "real-before.tsv.gz"), after)
    (run / "real.diff").write_text("".join(f"{row}\n" for row in changed), encoding="utf-8")
    log_dir = config.iso(host) / "BitwigStudio/log"
    if log_dir.is_dir():
        shutil.copytree(log_dir, run / "bitwig-log", dirs_exist_ok=True)
    evidence.update_run(run, quit=quit_kind, lockReleased=True, ended=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    lock.clear_owner()
    proof(config, str(run))


def reset(config: Config, name: str, extension: str) -> None:
    down(config)
    up(config, name, extension)


def cli(args: list[str]) -> int:
    owner, run = owned_run()
    refused = [word for word in REFUSED_CLI if word in " ".join(args)]
    if refused:
        raise SystemExit(f"refusing: {refused} through native-cli; the test instance stays silent")
    done = bitwig.cli(args, owner["port"], run)
    sys.stdout.write(done.stdout)
    sys.stderr.write(done.stderr)
    return done.returncode


def shot(config: Config) -> Path:
    _, run = owned_run()
    path = run / f"shot-{len(list(run.glob('shot-*.png'))) + 1:02d}.png"
    env = {**os.environ, "DISPLAY": config.display}
    window = subprocess.run(
        ["xdotool", "search", "--name", bitwig.WINDOW], env=env, capture_output=True, text=True, check=True
    ).stdout.split()[0]
    subprocess.run(["magick", "import", "-window", window, str(path)], env=env, check=True)
    print(path)
    return path


def status(config: Config) -> None:
    """Read-only: the harness owner, the lock and every Bitwig process with its sandbox mounts."""
    owner = lock.owner()
    print(f"lock {'held' if lock.held() else 'free'}; owner {json.dumps(owner)}")
    for process in bitwig.processes():
        line = f"Bitwig pid {process.pid} display {process.display or '-'} port {process.port}"
        if owner and process.session == owner["session"]:
            targets = evidence.read_run(Path(owner["run"])).get("mounts", [])
            mountinfo = (bitwig.PROC / str(process.pid) / "mountinfo").read_text(encoding="utf-8")
            line += f" managed; missing mounts {sandbox.missing_mounts(mountinfo, targets)}"
        print(line)


def proof(config: Config, run: str) -> bool:
    path = Path(run) if run else max(config.runs.iterdir())
    passed, text = evidence.summary(path)
    (path / "proof.txt").write_text(text, encoding="utf-8")
    print(text, end="")
    return passed
