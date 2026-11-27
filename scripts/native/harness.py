"""The harness commands: init, up, down, reset, status, cli, shot and proof."""

import json
import os
import pwd
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, NoReturn

from bitwig_agent.audio import pipewire
from bitwig_agent.errors import BridgeError, HostChanged

from . import audio, bitwig, doctor, evidence, lock, sandbox
from .config import Config, Host

EXTENSION = "BitwigAgent.bwextension"
LICENCE = (".activation-*", ".eula-agreed-*")
PORTS_SECONDS = 60
STATUS_SECONDS = 180
LOCK_SECONDS = 30


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


def abort(run: Path, session: int, reason: str) -> NoReturn:
    """Fail closed: stop the whole sandbox at once, then leave native-down to close the evidence."""
    os.killpg(session, signal.SIGTERM)
    evidence.update_run(run, aborted=reason)
    raise SystemExit(f"ABORTED: {reason}; run native-down to close the evidence in {run}")


def spawn(argv: list[str], output: Path, **options: Any) -> int:
    with output.open("ab") as handle:
        child = subprocess.Popen(
            argv, stdin=subprocess.DEVNULL, stdout=handle, stderr=subprocess.STDOUT, start_new_session=True, **options
        )
    return child.pid


def managed(session: int) -> bitwig.Process | None:
    return next((p for p in bitwig.processes() if p.session == session), None)


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


def guard_audio(run: Path, session: int) -> None:
    """Link Bitwig to the silent sink from outside; abort the run on any failure or any other link."""
    if not bitwig.wait_until(audio.ports_ready, PORTS_SECONDS, 0.1):
        abort(run, session, f"no '{pipewire.NODE}' output ports within {PORTS_SECONDS}s")
    try:
        listing, stray = audio.connect()
    except (BridgeError, HostChanged) as exc:
        abort(run, session, f"linking Bitwig to {pipewire.SINK} failed: {exc}")
    (run / "pw-link.txt").write_text(listing, encoding="utf-8")
    evidence.update_run(run, strayLinks=stray)
    if stray:
        abort(run, session, f"Bitwig linked outside {pipewire.SINK}: {stray}")


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
    monitor = spawn([sys.executable, "-m", "native.monitor", run.name], run / "monitor.log")
    time.sleep(1)  # the monitor holds its first pw-dump snapshot before Bitwig starts
    argv = sandbox.argv(table, run / "project" / config.fixture.name / f"{config.fixture.name}.bwproject")
    (run / "bwrap-argv.txt").write_text(subprocess.list2cmdline(argv) + "\n", encoding="utf-8")
    env = sandbox.environment(os.environ, config.display, config.port)
    session = spawn(argv, run / "bitwig.out", env=env, pass_fds=(fd,))
    lock.write_owner({"run": str(run), "host": name, "session": session, "monitor": monitor, "port": config.port})
    guard_audio(run, session)
    process = managed(session)
    if process is None:
        abort(run, session, "the sandboxed BitwigStudio process is gone")
    mountinfo = (bitwig.PROC / str(process.pid) / "mountinfo").read_text(encoding="utf-8")
    (run / "mountinfo.txt").write_text(mountinfo, encoding="utf-8")
    evidence.update_run(run, pid=process.pid)
    log = run / "cli.log"
    if not bitwig.wait_until(lambda: bitwig.cli(["status"], config.port, log).returncode == 0, STATUS_SECONDS, 3):
        abort(run, session, f"the extension did not answer status on port {config.port}")
    time.sleep(2)
    bitwig.dismiss_modal(config.display)  # a stale crash-recovery dialog would block Save and focus-dependent Actions
    silent = bitwig.cli(["audio", "route", "--mode=silent"], config.port, log)
    (run / "silent-route.json").write_text(silent.stdout, encoding="utf-8")
    print(f"up: {name} pid {process.pid} port {config.port} display {config.display}; evidence {run}")
    return run


def down(config: Config) -> None:
    owner = lock.owner()
    if owner is None:
        for unmanaged in bitwig.processes():
            if unmanaged.display == config.display:
                print(f"no harness run; quit unmanaged Bitwig {unmanaged.pid}: {bitwig.quit_bitwig(unmanaged, None)}")
        return
    run = Path(owner["run"])
    process = managed(owner["session"])
    quit_kind = (
        bitwig.quit_bitwig(process, run / "cli.log") if process else evidence.read_run(run).get("aborted", "gone")
    )
    released = bitwig.wait_until(lambda: not lock.held(), LOCK_SECONDS)
    if bitwig.alive(owner["monitor"]):
        os.kill(owner["monitor"], signal.SIGTERM)
    bitwig.wait_until(lambda: not bitwig.alive(owner["monitor"]), 5)
    table = host_mounts(config, config.host(owner["host"]))
    after = evidence.snapshot(snapshot_roots(table))
    evidence.write_snapshot(run / "real-after.tsv.gz", after)
    changed = evidence.diff(evidence.read_snapshot(run / "real-before.tsv.gz"), after)
    (run / "real.diff").write_text("".join(f"{row}\n" for row in changed), encoding="utf-8")
    log_dir = config.iso(config.host(owner["host"])) / "BitwigStudio/log"
    if log_dir.is_dir():
        shutil.copytree(log_dir, run / "bitwig-log", dirs_exist_ok=True)
    evidence.update_run(run, quit=quit_kind, lockReleased=released, ended=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    lock.clear_owner()
    proof(config, str(run))


def reset(config: Config, name: str, extension: str) -> None:
    down(config)
    up(config, name, extension)


def current_run() -> tuple[dict[str, Any], Path]:
    owner = lock.owner()
    if owner is None:
        raise SystemExit("no harness run; start one with native-up")
    return owner, Path(owner["run"])


def cli(args: list[str]) -> int:
    owner, run = current_run()
    done = bitwig.cli(args, owner["port"], run / "cli.log")
    sys.stdout.write(done.stdout)
    sys.stderr.write(done.stderr)
    return done.returncode


def shot(config: Config) -> Path:
    _, run = current_run()
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
