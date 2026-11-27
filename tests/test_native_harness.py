"""The native test harness without Bitwig: config, mount table, audio guard, evidence and the up/down flow.

The flow test replaces only the system boundary (processes under a fake /proc, PipeWire CLIs, the
bitwig-agent CLI); every decision runs in the harness code itself.
"""

import gzip
import io
import json
import os
import re
import shutil
import signal
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from bitwig_agent.audio import pipewire
from bitwig_agent.errors import BridgeError, bridge_error

if sys.platform != "linux":
    pytest.skip("the native harness needs Linux (bwrap, /proc, flock)", allow_module_level=True)

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from fakes import FakePW
from native import __main__ as entry
from native import audio, bitwig, config, doctor, evidence, harness, lock, monitor, sandbox

INSTALL = str(sandbox.INSTALL)
SILENT = json.dumps({"outcome": "applied", "silent": True})


def write_config(path: Path, state: Path, root: Path) -> Path:
    path.write_text(
        f"""
state_root = "{state}"
display = ":9"
port = 61174
fixture_seed = "{state}/seed/BWA Test"
[hosts."6.1.7"]
root = "{root}"
prefs_seed = "{state}/seed/prefs"
""",
        encoding="utf-8",
    )
    return path


# config


def test_parse_expands_home_and_derives_paths(monkeypatch):
    monkeypatch.setenv("HOME", "/home/someone")
    data = {
        "state_root": "~/hosts",
        "display": ":1",
        "port": "61174",
        "fixture_seed": "~/seed/BWA Test",
        "hosts": {"6.1.4": {"root": "/opt/bitwig-studio", "prefs_seed": "~/seed/prefs"}},
    }
    parsed = config.parse(data, ":0")
    host = parsed.host("6.1.4")
    assert parsed.port == 61174
    assert host.prefs_seed == Path("/home/someone/seed/prefs")
    assert parsed.iso(host) == Path("/home/someone/hosts/iso/6.1.4")
    assert parsed.fixture == Path("/home/someone/hosts/fixtures/BWA Test")
    assert parsed.runs == Path("/home/someone/hosts/runs")
    with pytest.raises(SystemExit, match=re.escape("Unknown host '6.1.9'; configured: 6.1.4")):
        parsed.host("6.1.9")


@pytest.mark.parametrize(
    ("display", "caller"),
    [
        (":0", ""),
        (":0.0", ":1"),
        (":2", ":2.0"),
        (":2.0", ":2"),
        ("localhost:0", ""),
        ("unix:0", ""),
        ("127.0.0.1:0.0", ""),
        (":3", "localhost:3"),
    ],
)
def test_parse_refuses_the_desktop_or_the_callers_display(display, caller):
    data = {"state_root": "/s", "display": display, "port": 1, "fixture_seed": "/s/P", "hosts": {}}
    with pytest.raises(SystemExit, match=f"refusing display {re.escape(display)}"):
        config.parse(data, caller)


def test_load_reads_the_selected_file_and_refuses_a_missing_one(tmp_path, monkeypatch):
    monkeypatch.setenv("BWA_NATIVE_CONFIG", str(write_config(tmp_path / "c.toml", tmp_path, tmp_path / "root")))
    assert config.load().hosts["6.1.7"].root == tmp_path / "root"
    monkeypatch.setenv("BWA_NATIVE_CONFIG", str(tmp_path / "absent.toml"))
    with pytest.raises(SystemExit, match="No harness config"):
        config.load()


def test_example_config_parses():
    with (config.ROOT / "native-hosts.example.toml").open("rb") as handle:
        import tomllib  # noqa: PLC0415 - only this test reads TOML directly

        parsed = config.parse(tomllib.load(handle), "")
    assert set(parsed.hosts) == {entry["version"] for entry in doctor.shared("host")}


# sandbox


def test_mount_table_binds_the_isolated_home_over_the_passwd_home():
    iso, home = Path("/s/iso/6.1.4"), Path("/home/u")
    table = sandbox.mounts(sandbox.INSTALL, iso, home, Path("/tmp/bitwig-u"))
    assert [(m.kind, str(m.target)) for m in table] == [
        ("tmpfs", f"{INSTALL}/lib/audio-host-api-plugins"),
        ("bind", "/home/u/.BitwigStudio"),
        ("ro-bind", "/home/u/.BitwigStudio/installed-packages"),
        ("bind", "/home/u/Bitwig Studio"),
        ("bind", "/home/u/.cache"),
        ("bind", "/home/u/.java"),
        ("bind", "/tmp/bitwig-u"),
    ]
    assert [str(m.target) for m in table if m.snapshot] == [
        "/home/u/.BitwigStudio",
        "/home/u/Bitwig Studio",
        "/home/u/.java",
        "/tmp/bitwig-u",
    ]
    assert sandbox.iso_dirs(table, iso) == [iso / d for d in ("BitwigStudio", "BWS", "cache", "java", "tmp")]


def test_a_second_install_is_bound_over_the_install_path_before_anything_inside_it():
    table = sandbox.mounts(Path("/s/6.1.7/root"), Path("/s/iso/6.1.7"), Path("/home/u"), Path("/tmp/bitwig-u"))
    command = sandbox.argv(table, Path("/s/runs/r/project/P/P.bwproject"))
    assert command[:8] == ["bwrap", "--dev-bind", "/", "/", "--bind", "/s/6.1.7/root", INSTALL, "--tmpfs"]
    assert command[8] == f"{INSTALL}/lib/audio-host-api-plugins"
    assert command[-2:] == [f"{INSTALL}/bitwig-studio", "/s/runs/r/project/P/P.bwproject"]
    assert "/s/6.1.7/root" not in [str(p) for p in sandbox.iso_dirs(table, Path("/s/iso/6.1.7"))]


def test_environment_selects_the_test_display_port_and_jack_guard():
    caller = {
        "WAYLAND_DISPLAY": "wayland-0",
        "DISPLAY": ":0",
        "PATH": "/pixi/env/bin:/usr/bin",
        "JAVA_HOME": "/pixi/env/lib/jvm",
        "CONDA_PREFIX": "/pixi/env",
        "XDG_DATA_DIRS": "/pixi/env/share:/usr/share",
        "PYTHONPATH": "scripts",
        "HOME": "/home/u",
        "XDG_RUNTIME_DIR": "/run/user/1000",
        "LC_TIME": "C",
    }
    env = sandbox.environment(caller, ":1", 61174)
    assert env == {
        "HOME": "/home/u",
        "XDG_RUNTIME_DIR": "/run/user/1000",
        "LC_TIME": "C",
        "DISPLAY": ":1",
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "PIPEWIRE_PROPS": "{ jack.self-connect-mode=ignore-all jack.other-connect-mode=ignore node.autoconnect=false }",
        "BITWIG_AGENT_PORT": "61174",
    }


def test_missing_mounts_decodes_mountinfo_escapes():
    mountinfo = (
        "36 35 0:1 / / rw - ext4 /dev/a rw\n"
        "40 36 0:2 /x /home/u/Bitwig\\040Studio rw - ext4 /dev/a rw\n"
        "41 36 0:3 / /home/u/.java rw - ext4 /dev/a rw\n"
    )
    assert sandbox.missing_mounts(mountinfo, ["/home/u/Bitwig Studio", "/home/u/.java", "/tmp/bitwig-u"]) == [
        "/tmp/bitwig-u"
    ]


# audio guard


def test_stray_links_flags_any_bitwig_peer_but_the_sink_in_both_directions():
    listing = (
        "Bitwig Studio:out1\n  |-> bwtest:playback_FL\n"
        "Bitwig Studio:out2\n  |-> alsa_output.pci:playback_FR\n"
        "alsa_output.pci:playback_FR\n  |<- Bitwig Studio:out2\n"
        "alsa_input.usb:capture_FL\n  |-> Bitwig Studio:in1\n"
        "Firefox:output_FL\n  |-> alsa_output.pci:playback_FL\n"
    )
    assert audio.stray_links(listing) == [
        ("Bitwig Studio:out2", "alsa_output.pci:playback_FR"),
        ("alsa_input.usb:capture_FL", "Bitwig Studio:in1"),
    ]
    assert audio.stray_links("Bitwig Studio:out1\n  |-> bwtest:playback_FL\n") == []
    assert not audio.outside_sink("Bitwig Studio", "Bitwig Studio")
    assert audio.outside_sink("Bitwig Studio", "alsa_output.pci")


class GuardPW(FakePW):
    """FakePW whose `pw-link -l` lists only the links it holds."""

    def run(self, args, timeout=5.0):
        if args[:2] == ["pw-link", "-l"]:
            return 0, "".join(f"{a}\n  |-> {b}\n" for a, b in sorted(self.links)), ""
        return super().run(args, timeout)


def test_connect_links_both_outputs_to_the_sink_and_reads_them_back(monkeypatch):
    pw = GuardPW(set(), sink=True)
    monkeypatch.setattr(pipewire, "run", pw.run)
    assert audio.ports_ready()
    listing = audio.connect()
    assert audio.stray_links(listing) == []
    assert set(pipewire.OUT_PORTS.items()) == pw.links
    assert "Bitwig Studio:out1\n  |-> bwtest:playback_FL" in listing


def test_disconnect_strays_removes_only_links_outside_the_sink(monkeypatch):
    pw = GuardPW({("Bitwig Studio:out1", "alsa_output.pci:playback_FL"), ("Bitwig Studio:out2", "bwtest:playback_FR")})
    monkeypatch.setattr(pipewire, "run", pw.run)
    assert audio.disconnect_strays() == [("Bitwig Studio:out1", "alsa_output.pci:playback_FL")]
    assert pw.links == {("Bitwig Studio:out2", "bwtest:playback_FR")}


def test_connect_stops_when_the_links_cannot_be_read_back(monkeypatch):
    pw = GuardPW(set())
    monkeypatch.setattr(pipewire, "run", lambda a, t=5.0: (1, "", "no daemon") if a == ["pw-link", "-l"] else pw.run(a))
    with pytest.raises(BridgeError, match="pw-link -l failed: no daemon"):
        audio.connect()


# monitor


def node(oid, name):
    return {"id": oid, "type": "PipeWire:Interface:Node", "info": {"props": {"node.name": name}}}


def link(oid, out, sink):
    return {"id": oid, "type": "PipeWire:Interface:Link", "info": {"output-node-id": out, "input-node-id": sink}}


def test_graph_reports_each_bitwig_link_outside_the_sink_once():
    graph = monitor.Graph()
    objects = [node(1, "Bitwig Studio"), node(2, "alsa_output.pci"), node(3, "bwtest"), link(10, 1, 3)]
    assert graph.apply(objects) == []
    assert graph.apply([link(11, 1, 2)]) == ["pw-dump link 11: Bitwig Studio -> alsa_output.pci"]
    assert graph.apply([link(11, 1, 2)]) == []
    assert graph.apply([node(4, "easyeffects_sink"), link(12, 4, 1)]) == [
        "pw-dump link 12: easyeffects_sink -> Bitwig Studio"
    ]
    graph.apply([{"id": 11, "info": None}, {"id": 2, "info": None}])
    assert graph.seen == {
        "Bitwig Studio -> bwtest",
        "Bitwig Studio -> alsa_output.pci",
        "easyeffects_sink -> Bitwig Studio",
    }
    assert list(graph.bad) == [11, 12]
    assert graph.events == 10


def test_split_dump_keeps_an_incomplete_array_for_later():
    objects, rest = monitor.split_dump('[{"id": 1}]\n[{"id": 2}, {"id": 3}]\n[{"id": 4')
    assert [o["id"] for o in objects] == [1, 2, 3]
    assert rest == '[{"id": 4'
    assert len(monitor.stamp()) == len("12:00:00.000")


def test_monitor_reads_both_sources_and_summarises(monkeypatch, capsys):
    watch = monitor.Monitor("r")
    dump = [node(1, "Bitwig Studio"), node(2, "alsa_output.pci"), link(10, 1, 2)]
    watch.read_dump(io.StringIO(json.dumps(dump, indent=2) + "\n"))
    assert watch.ready.is_set()
    listing = "Bitwig Studio:out1\n  |-> alsa_output.pci:playback_FL\n  |-> bwtest:playback_FL\n"

    def run(command, timeout=5.0):
        watch.stop.set()
        return 0, listing, ""

    monkeypatch.setattr(pipewire, "run", run)
    watch.poll()
    out = capsys.readouterr().out
    assert "pw-dump link 10: Bitwig Studio -> alsa_output.pci" in out
    assert "pw-link: Bitwig Studio:out1 |-> alsa_output.pci:playback_FL" in out
    summary = watch.summary()
    assert "pw-dump events=3 pw-link polls=1 bitwig links outside bwtest: dump=1 poll=1; " in summary
    assert "pw-dump exited early: yes;" in summary  # the stream ended before the stop
    assert not evidence.MONITOR_CLEAN.search(summary)
    assert evidence.MONITOR_CLEAN.search(monitor.Monitor("clean").summary())


def test_a_dead_poller_fails_the_monitor_proof(monkeypatch):
    watch = monitor.Monitor("r")

    def broken(args, timeout=5.0):
        raise bridge_error("deadline", "pw-link did not finish")

    monkeypatch.setattr(pipewire, "run", broken)
    with pytest.raises(BridgeError):
        watch.poll()
    assert "pw-link poller died: yes;" in watch.summary()
    assert not evidence.MONITOR_CLEAN.search(watch.summary())


def test_a_pw_dump_exit_fails_the_monitor_proof():
    watch = monitor.Monitor("r")
    watch.read_dump(io.StringIO(""))
    assert "dump=0 poll=0; pw-dump exited early: yes;" in watch.summary()
    assert not evidence.MONITOR_CLEAN.search(watch.summary())
    stopped = monitor.Monitor("r")
    stopped.stop.set()
    stopped.read_dump(io.StringIO(""))
    assert evidence.MONITOR_CLEAN.search(stopped.summary())


# processes and RPC


def write_stat(path: Path, pid: int, session: int, state: str = "S") -> None:
    (path / "stat").write_text(f"{pid} (Bitwig (x)) {state} 1 {pid} {session} 0 -1", encoding="utf-8")


def fake_process(proc: Path, pid: int, *, session=None, mounts="", comm=bitwig.COMM, **env):
    path = proc / str(pid)
    path.mkdir(parents=True)
    (path / "comm").write_text(f"{comm}\n", encoding="utf-8")
    variables = {"DISPLAY": ":9", "HOME": "/home/u", **env}
    (path / "environ").write_bytes("".join(f"{k}={v}\0" for k, v in variables.items()).encode())
    write_stat(path, pid, session or pid)
    (path / "mountinfo").write_text(mounts, encoding="utf-8")
    return path


def test_processes_reads_display_port_and_session(tmp_path, monkeypatch):
    monkeypatch.setattr(bitwig, "PROC", tmp_path)
    fake_process(tmp_path, 7, BITWIG_AGENT_PORT=61174, session=5)
    fake_process(tmp_path, 8, DISPLAY=":0")
    other = tmp_path / "9"
    other.mkdir()
    (other / "comm").write_text("bash\n", encoding="utf-8")
    assert sorted(bitwig.processes(), key=lambda p: p.pid) == [
        bitwig.Process(7, ":9", 61174, 5),
        bitwig.Process(8, ":0", 61172, 8),
    ]
    assert bitwig.alive(7)
    assert not bitwig.alive(10)


def test_processes_skips_a_process_that_exits_during_the_scan(tmp_path, monkeypatch):
    monkeypatch.setattr(bitwig, "PROC", tmp_path)
    (fake_process(tmp_path, 7) / "environ").unlink()
    assert bitwig.processes() == []


def test_session_alive_sees_any_process_of_the_session(tmp_path, monkeypatch):
    monkeypatch.setattr(bitwig, "PROC", tmp_path)
    fake_process(tmp_path, 7, session=5)
    (tmp_path / "8").mkdir()
    (tmp_path / "8/stat").symlink_to(tmp_path / "gone")  # exited between listing and reading
    assert bitwig.session_alive(5)
    assert not bitwig.session_alive(6)


def test_a_zombie_leader_does_not_keep_its_session_alive(tmp_path, monkeypatch):
    monkeypatch.setattr(bitwig, "PROC", tmp_path)
    leader = fake_process(tmp_path, 5, comm="bwrap")
    assert bitwig.session_alive(5)
    write_stat(leader, 5, 5, "Z")  # dead, not reaped by the harness that spawned it
    assert not bitwig.session_alive(5)


def completed(args, rc=0, out=""):
    return subprocess.CompletedProcess(args, rc, out, "")


def test_cli_logs_the_command_status_and_output(tmp_path, monkeypatch):
    seen: dict[str, str] = {}

    def run(command, **kwargs):
        env = kwargs["env"]
        seen.update(command=command, port=env["BITWIG_AGENT_PORT"], cache=env["XDG_CACHE_HOME"])
        return completed(command, 1, '{"code":"unavailable"}\n')

    monkeypatch.setattr(subprocess, "run", run)
    assert bitwig.cli(["status"], 61174, tmp_path).returncode == 1
    assert seen == {"command": ["bitwig-agent", "status"], "port": "61174", "cache": str(tmp_path)}
    log = (tmp_path / "cli.log").read_text(encoding="utf-8")
    assert log.startswith("$ bitwig-agent status  # ")
    assert "port=61174 rc=1\n" in log
    assert '{"code":"unavailable"}' in log


def recorder(commands, out):
    """A subprocess.run that records each command and answers out."""

    def run(command, **kwargs):
        commands.append(command)
        return completed(command, 0, out)

    return run


@pytest.fixture
def quiet(monkeypatch):
    """No sleeping and no real subprocesses; returns the list of commands run."""
    commands: list[list[str]] = []
    monkeypatch.setattr(bitwig.time, "sleep", lambda s: None)
    monkeypatch.setattr(subprocess, "run", recorder(commands, ""))
    return commands


def test_quit_saves_then_quits_and_terminates_a_bitwig_that_stays(tmp_path, monkeypatch, quiet):
    monkeypatch.setattr(bitwig, "PROC", tmp_path)
    monkeypatch.setattr(bitwig, "QUIT_SECONDS", 0)
    fake_process(tmp_path, 7)
    killed = []
    monkeypatch.setattr(os, "kill", lambda pid, sig: killed.append((pid, sig)))
    assert bitwig.quit_bitwig(bitwig.Process(7, ":9", 61174, 7), None) == "alive"
    assert killed == [(7, signal.SIGTERM)]
    assert quiet[0][:3] == ["xdotool", "search", "--name"]
    assert quiet[1:] == [
        ["bitwig-agent", "project", "save"],
        ["bitwig-agent", "actions", "invoke", "--actionId=Quit", "--discardUnsavedChanges=true"],
    ]


def test_quit_reports_a_clean_exit(tmp_path, monkeypatch, quiet):
    monkeypatch.setattr(bitwig, "PROC", tmp_path)
    assert bitwig.quit_bitwig(bitwig.Process(7, ":9", 61174, 7), None) == "clean"


# lock


def test_lock_is_exclusive_until_the_descriptor_closes(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    fd = lock.acquire()
    assert fd is not None
    assert lock.acquire() is None
    assert lock.held()
    os.close(fd)
    assert not lock.held()
    assert lock.owner() is None
    lock.write_owner({"run": "r"})
    assert lock.owner() == {"run": "r"}
    lock.clear_owner()
    assert lock.owner() is None


# evidence


def test_snapshot_and_diff_report_changed_entries(tmp_path):
    (tmp_path / "a").mkdir()
    (tmp_path / "a/f").write_text("x", encoding="utf-8")
    before = evidence.snapshot([tmp_path / "a", tmp_path / "missing"])
    assert [row.split("\t")[:2] for row in before] == [[str(tmp_path / "a/f"), "1"]]
    evidence.write_snapshot(tmp_path / "s.gz", before)
    assert evidence.read_snapshot(tmp_path / "s.gz") == before
    (tmp_path / "a/f").write_text("xy", encoding="utf-8")
    (tmp_path / "a/g").write_text("", encoding="utf-8")
    changed = evidence.diff(before, evidence.snapshot([tmp_path / "a"]))
    assert [row[0] + row[1:].split("\t")[1] for row in changed] == ["-1", "+2", "+0"]


def finished_run(run: Path, **overrides) -> Path:
    run.mkdir(parents=True)
    data = {
        "host": "6.1.7",
        "port": 61174,
        "jarSha256": "a",
        "expectedJarSha256": "a",
        "extensionSha256": "e",
        "mounts": ["/home/u/.java"],
        "strayLinks": [],
        "lockReleased": True,
        "quit": "clean",
    }
    evidence.update_run(run, **{**data, **overrides})
    (run / "mountinfo.txt").write_text("41 36 0:3 / /home/u/.java rw - ext4 /dev/a rw\n", encoding="utf-8")
    evidence.write_snapshot(run / "real-after.tsv.gz", [])
    (run / "real.diff").write_text("", encoding="utf-8")
    (run / "monitor.log").write_text(
        "[r] 10:00:00.000 monitor stopped after 9.0s: pw-dump events=4 pw-link polls=9 "
        "bitwig links outside bwtest: dump=0 poll=0; pw-dump exited early: no; pw-link poller died: no; "
        "bitwig links seen: none\n",
        encoding="utf-8",
    )
    (run / "silent-route.json").write_text(SILENT, encoding="utf-8")
    return run


def test_summary_passes_a_clean_run(tmp_path):
    passed, text = evidence.summary(finished_run(tmp_path / "r"))
    assert passed
    assert text.startswith("run r: host 6.1.7 port 61174 extension sha256 e\n")
    assert text.count("PASS") == 9


def test_summary_fails_each_broken_proof(tmp_path):
    run = finished_run(tmp_path / "r", jarSha256="b", strayLinks=["x"], lockReleased=False, quit="alive", mounts=["/x"])
    (run / "real.diff").write_text("+/home/u/.java/f\t1\t2\n", encoding="utf-8")
    (run / "monitor.log").write_text("links: dump=1 poll=0;", encoding="utf-8")
    (run / "silent-route.json").write_text("not json", encoding="utf-8")
    passed, text = evidence.summary(run)
    assert not passed
    assert text.count("FAIL") == 9


# doctor


def test_doctor_checks_tools_pixi_jars_and_homes(tmp_path, monkeypatch):
    root = tmp_path / "root"
    (root / "bin").mkdir(parents=True)
    (root / "bin/bitwig.jar").write_bytes(b"jar")
    cfg = config.parse(
        {
            "state_root": str(tmp_path),
            "display": ":9",
            "port": 61174,
            "fixture_seed": str(tmp_path / "seed/P"),
            "hosts": {"6.1.7": {"root": str(root), "prefs_seed": "x"}, "6.1.4": {"root": "/absent", "prefs_seed": "x"}},
        },
        "",
    )
    monkeypatch.setattr(
        doctor, "shared", lambda name: [{"version": "6.1.7", "jarSha256": evidence.sha256(root / "bin/bitwig.jar")}]
    )
    monkeypatch.setattr(doctor.shutil, "which", lambda tool: None if tool == "magick" else f"/usr/bin/{tool}")
    monkeypatch.setattr(doctor.sandbox, "INSTALL", tmp_path)
    monkeypatch.setenv("PIXI_EXE", "/usr/bin/pixi")
    monkeypatch.setattr(subprocess, "run", lambda c, **k: completed(c, 0, "pixi 0.84.2\n"))
    failed = [text for ok, text in doctor.checks(cfg) if not ok]
    assert failed == [
        "tool magick: None",
        f"6.1.7 isolated home {tmp_path}/iso/6.1.7 (native-init)",
        "6.1.4 jar None expected None",
        f"6.1.4 isolated home {tmp_path}/iso/6.1.4 (native-init)",
        f"fixture {tmp_path}/fixtures/P (native-init)",
    ]
    monkeypatch.delenv("PIXI_EXE")
    monkeypatch.setattr(doctor.shutil, "which", lambda tool: None)
    assert doctor.pixi_version() is None


# the flow


class Desktop:
    """The system boundary of one run: a fake /proc, PipeWire, spawned children and the CLI."""

    def __init__(self, tmp_path, monkeypatch):
        self.proc = tmp_path / "proc"
        self.proc.mkdir()
        self.home = tmp_path / "home"
        self.pw = GuardPW(set(), sink=True)
        self.spawned: list[list[str]] = []
        self.calls: list[list[str]] = []
        self.killed: list[tuple[int, int]] = []
        self.fds: list[int] = []
        self.bitwig_links: set[tuple[str, str]] = set()
        self.ignores: tuple = ()  # signals the sandbox survives
        self.monitor_starts = True
        self.events: list[str] = []
        self.leader = 0
        monkeypatch.setattr(bitwig, "PROC", self.proc)
        monkeypatch.setattr(pipewire, "run", self.run)
        monkeypatch.setattr(harness, "KILL_SECONDS", 0)
        monkeypatch.setattr(harness, "capture", self.capture)
        monkeypatch.setattr(harness, "account", lambda: SimpleNamespace(pw_dir=str(self.home), pw_name="u"))
        monkeypatch.setattr(harness, "java_tmpdir", lambda user: tmp_path / f"tmp-bitwig-{user}")
        monkeypatch.setattr(harness, "spawn", self.spawn)
        monkeypatch.setattr(bitwig, "cli", self.cli)
        monkeypatch.setattr(bitwig, "dismiss_modal", lambda display: None)
        monkeypatch.setattr(harness.time, "sleep", lambda s: None)
        monkeypatch.setattr(os, "killpg", self.killpg)
        monkeypatch.setattr(os, "kill", self.kill)
        monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))

    def spawn(self, argv, output, **options):
        self.spawned.append(argv)
        pid = 100 + len(self.spawned)
        if argv[0] == "bwrap":
            self.fds += options["pass_fds"]
            options_at = [i for i in range(4, len(argv) - 2) if argv[i].startswith("--")]
            targets = [argv[i + 1] if argv[i] == "--tmpfs" else argv[i + 2] for i in options_at]
            mounts = "".join(f"1 1 0:1 / {t.replace(' ', chr(92) + '040')} rw - x y rw\n" for t in targets)
            self.leader = pid
            fake_process(self.proc, pid, comm="bwrap")
            fake_process(self.proc, pid + 1000, session=pid, mounts=mounts, BITWIG_AGENT_PORT=61174)
            self.pw.links |= self.bitwig_links
            output.write_text("started\n", encoding="utf-8")
        else:
            (self.proc / str(pid)).mkdir()
            (self.proc / str(pid) / "comm").write_text("python\n", encoding="utf-8")
            output.write_text(f"[r] {monitor.STARTED}\n" if self.monitor_starts else "", encoding="utf-8")
        return pid

    def run(self, args, timeout=5.0):
        if args[:2] == ["pw-link", "-d"]:
            self.events.append("cut")
        return self.pw.run(args, timeout)

    def capture(self, display, run):
        self.events.append("shot")
        return run / "shot-01.png"

    def killpg(self, session, sig):
        self.killed.append((session, sig))
        self.events.append(f"kill {sig}")
        if sig not in self.ignores:
            self.end_bitwig()

    def end_bitwig(self):
        """Bitwig exits; bwrap follows and stays a zombie, since the harness never reaps it."""
        for comm in self.proc.glob("*/comm"):
            if comm.read_text(encoding="utf-8").strip() == bitwig.COMM:
                shutil.rmtree(comm.parent)
        write_stat(self.proc / str(self.leader), self.leader, self.leader, "Z")
        for fd in self.fds:
            os.close(fd)
        self.fds.clear()

    def cli(self, args, port, run=None):
        self.calls.append(args)
        if args[:1] == ["actions"] and "quit" not in self.ignores:
            self.end_bitwig()
        return completed(args, 0, SILENT if args[:2] == ["audio", "route"] else "{}")

    def kill(self, pid, sig):
        self.killed.append((pid, sig))
        shutil.rmtree(self.proc / str(pid))


@pytest.fixture
def desktop(tmp_path, monkeypatch):
    fake = Desktop(tmp_path, monkeypatch)
    yield fake
    for fd in fake.fds:
        os.close(fd)


def prepared(tmp_path: Path, desktop: Desktop, monkeypatch) -> config.Config:
    root = tmp_path / "root"
    (root / "bin").mkdir(parents=True)
    (root / "bin/bitwig.jar").write_bytes(b"jar")
    monkeypatch.setattr(
        doctor, "shared", lambda name: [{"version": "6.1.7", "jarSha256": evidence.sha256(root / "bin/bitwig.jar")}]
    )
    state = tmp_path / "state"
    (state / "seed/prefs").mkdir(parents=True)
    (state / "seed/prefs/6.1.7.prefs").write_text("jack", encoding="utf-8")
    (state / "seed/BWA Test").mkdir()
    (state / "seed/BWA Test/BWA Test.bwproject").write_bytes(b"p")
    real = desktop.home / ".BitwigStudio"
    real.mkdir(parents=True)
    for name in (".activation-12", ".eula-agreed-X", "has-opened-dashboard", "telemetry-id"):
        (real / name).write_text(name, encoding="utf-8")
    cfg = config.parse(
        {
            "state_root": str(state),
            "display": ":9",
            "port": 61174,
            "fixture_seed": str(state / "seed/BWA Test"),
            "hosts": {"6.1.7": {"root": str(root), "prefs_seed": str(state / "seed/prefs")}},
        },
        "",
    )
    harness.init(cfg, "6.1.7")
    return cfg


def test_init_copies_licence_prefs_and_fixture_once(tmp_path, desktop, monkeypatch, capsys):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    iso = cfg.iso(cfg.host("6.1.7"))
    assert sorted(p.name for p in (iso / "BitwigStudio").iterdir()) == [
        ".activation-12",
        ".eula-agreed-X",
        "has-opened-dashboard",
        "prefs",
    ]
    assert (iso / "BWS/Extensions").is_dir()
    assert (cfg.fixture / "BWA Test.bwproject").is_file()
    capsys.readouterr()
    harness.init(cfg, "6.1.7")
    assert capsys.readouterr().out == f"6.1.7: isolated home {iso}\n"


def test_up_and_down_record_a_passing_run(tmp_path, desktop, monkeypatch, capsys):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    extension = tmp_path / "BitwigAgent.bwextension"
    extension.write_bytes(b"ext")
    monkeypatch.setenv("INIT_CWD", str(tmp_path))
    run = harness.up(cfg, "6.1.7", extension.name)
    assert capsys.readouterr().out.endswith(f"export BWA_NATIVE_RUN={run.name}\n")
    data = evidence.read_run(run)
    assert data["extensionSha256"] == evidence.sha256(extension)
    assert data["pid"] == 1102
    assert data["strayLinks"] == []
    assert desktop.spawned[0][-1] == run.name
    assert desktop.spawned[1][0] == "bwrap"
    assert desktop.calls == [["status"], ["audio", "route", "--mode=silent"]]
    assert (lock.owner() or {})["session"] == 102
    assert lock.held()
    with pytest.raises(SystemExit, match=re.escape("Bitwig already running (pid 1102 on :9)")):
        harness.up(cfg, "6.1.7", "")
    harness.status(cfg)
    assert "managed; missing mounts []" in capsys.readouterr().out
    monkeypatch.setenv("BWA_NATIVE_RUN", run.name)
    assert harness.cli(["tracks", "list"]) == 0
    harness.down(cfg)
    assert desktop.killed == [(101, signal.SIGTERM)]
    assert lock.owner() is None
    assert evidence.read_run(run)["quit"] == "clean"
    proof = (run / "proof.txt").read_text(encoding="utf-8")
    assert "FAIL  audio monitor" in proof  # the fake monitor wrote no summary line
    assert proof.count("FAIL") == 2
    assert harness.proof(cfg, "") is False


def owned(desktop, monkeypatch, cfg):
    run = harness.up(cfg, "6.1.7", "")
    monkeypatch.setenv("BWA_NATIVE_RUN", run.name)
    return run


def test_state_changing_commands_need_the_run_id(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    run = owned(desktop, monkeypatch, cfg)
    monkeypatch.setenv("BWA_NATIVE_RUN", "someone-else")
    for command in (lambda: harness.down(cfg), lambda: harness.cli(["status"]), lambda: harness.shot(cfg)):
        with pytest.raises(SystemExit, match="the current run is not yours"):
            command()
    monkeypatch.delenv("BWA_NATIVE_RUN")
    with pytest.raises(SystemExit, match="the current run is not yours"):
        harness.reset(cfg, "6.1.7", "")
    assert (lock.owner() or {})["run"] == str(run)
    assert bitwig.processes()


@pytest.mark.parametrize("args", [["audio", "route", "--mode=restore"], ["audio", "capture", "--allowAudible=true"]])
def test_cli_refuses_audible_routes(tmp_path, desktop, monkeypatch, args):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    owned(desktop, monkeypatch, cfg)
    desktop.calls.clear()
    with pytest.raises(SystemExit, match="the test instance stays silent"):
        harness.cli(args)
    assert desktop.calls == []


def test_down_keeps_a_run_that_does_not_end(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    run = owned(desktop, monkeypatch, cfg)
    monkeypatch.setattr(harness, "LOCK_SECONDS", 0)
    monkeypatch.setattr(bitwig, "quit_bitwig", lambda process, run: "clean")
    with pytest.raises(SystemExit, match=re.escape("FAIL: the sandbox is still running (quit: clean, lock held)")):
        harness.down(cfg)  # a child of the fake sandbox still holds the lock

    def quit_but_stay(process, run):
        desktop.end_bitwig()  # the lock is released, yet quit reports Bitwig still running
        return "alive"

    monkeypatch.setattr(bitwig, "quit_bitwig", quit_but_stay)
    with pytest.raises(SystemExit, match=re.escape("FAIL: the sandbox is still running (quit: alive")):
        harness.down(cfg)
    assert not lock.held()  # refused on the quit result alone
    assert lock.owner() is not None
    assert bitwig.alive(101)  # the monitor keeps recording
    assert not (run / "real-after.tsv.gz").exists()


def test_up_aborts_on_a_stray_link_by_cutting_it_then_killing_the_sandbox(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    desktop.bitwig_links = {("Bitwig Studio:out1", "alsa_output.pci:playback_FL")}
    desktop.ignores = (signal.SIGTERM,)
    with pytest.raises(SystemExit, match="ABORTED: Bitwig linked outside bwtest") as raised:
        harness.up(cfg, "6.1.7", "")
    run = Path((lock.owner() or {})["run"])
    assert "; sandbox stopped;" in str(raised.value)
    assert str(raised.value).endswith(f"export BWA_NATIVE_RUN={run.name}\npixi run native-down")
    assert ("Bitwig Studio:out1", "alsa_output.pci:playback_FL") not in desktop.pw.links
    assert desktop.events == ["cut", "shot", f"kill {signal.SIGTERM}", f"kill {signal.SIGKILL}"]
    assert not bitwig.session_alive(102)
    assert bitwig.alive(102)  # the leader is a zombie, which counts as ended
    data = evidence.read_run(run)
    assert data["strayLinksCut"] == [["Bitwig Studio:out1", "alsa_output.pci:playback_FL"]]
    assert data["abortShot"] == str(run / "shot-01.png")
    assert data["aborted"].startswith("RuntimeError: Bitwig linked outside bwtest")


def test_a_failed_cut_keeps_the_abort_reason_and_still_kills(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    desktop.bitwig_links = {("Bitwig Studio:out1", "alsa_output.pci:playback_FL")}

    def cut_fails():
        raise bridge_error("job_failed", "pw-link -d failed: 1")

    monkeypatch.setattr(audio, "disconnect_strays", cut_fails)
    with pytest.raises(SystemExit, match="ABORTED: Bitwig linked outside bwtest"):
        harness.up(cfg, "6.1.7", "")
    data = evidence.read_run(Path((lock.owner() or {})["run"]))
    assert data["aborted"].startswith("RuntimeError: Bitwig linked outside bwtest")
    assert data["cutFailed"] == "BridgeError: pw-link -d failed: 1"
    assert desktop.killed == [(102, signal.SIGTERM)]
    assert not bitwig.session_alive(102)


def test_a_cut_that_cannot_run_pw_link_still_kills(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    desktop.bitwig_links = {("Bitwig Studio:out1", "alsa_output.pci:playback_FL")}

    def no_pw_link():
        raise FileNotFoundError(2, "No such file or directory", "pw-link")

    monkeypatch.setattr(audio, "disconnect_strays", no_pw_link)
    with pytest.raises(SystemExit, match=r"ABORTED: Bitwig linked outside bwtest.*; sandbox stopped;"):
        harness.up(cfg, "6.1.7", "")
    data = evidence.read_run(Path((lock.owner() or {})["run"]))
    assert data["cutFailed"].startswith("FileNotFoundError: ")
    assert desktop.killed == [(102, signal.SIGTERM)]
    assert not bitwig.session_alive(102)


def test_a_failed_screenshot_still_kills(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    monkeypatch.setattr(audio, "ports_ready", lambda: False)
    monkeypatch.setattr(harness, "PORTS_SECONDS", 0)

    def no_xdotool(display, run):
        raise FileNotFoundError(2, "No such file or directory", "xdotool")

    monkeypatch.setattr(harness, "capture", no_xdotool)
    with pytest.raises(SystemExit, match="sandbox stopped"):
        harness.up(cfg, "6.1.7", "")
    assert evidence.read_run(Path((lock.owner() or {})["run"]))["abortShot"].startswith("FileNotFoundError: ")
    assert desktop.killed == [(102, signal.SIGTERM)]


def test_an_unkillable_sandbox_is_reported_not_stopped(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    desktop.bitwig_links = {("Bitwig Studio:out1", "alsa_output.pci:playback_FL")}
    desktop.ignores = (signal.SIGTERM, signal.SIGKILL)
    with pytest.raises(SystemExit, match="sandbox NOT stopped: session 102 is still running") as raised:
        harness.up(cfg, "6.1.7", "")
    assert "sandbox stopped" not in str(raised.value)


def test_a_failure_writing_the_owner_still_stops_the_sandbox(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)

    def full_disk(data):
        raise OSError(28, "No space left on device")

    monkeypatch.setattr(lock, "write_owner", full_disk)
    with pytest.raises(SystemExit, match=r"ABORTED: .*No space left on device"):
        harness.up(cfg, "6.1.7", "")
    assert desktop.killed == [(102, signal.SIGTERM)]


def test_kill_tolerates_a_session_that_ends_after_the_check(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    monkeypatch.setattr(audio, "connect", lambda: (_ for _ in ()).throw(RuntimeError("boom")))

    def already_gone(session, sig):
        desktop.end_bitwig()
        raise ProcessLookupError

    monkeypatch.setattr(os, "killpg", already_gone)
    with pytest.raises(SystemExit, match="ABORTED: boom; sandbox stopped"):
        harness.up(cfg, "6.1.7", "")


def fail(*args):
    raise bridge_error("job_failed", "pw-link -l failed: 1")


@pytest.mark.parametrize(
    ("patch", "message"),
    [
        ((audio, "connect", fail), "pw-link -l failed"),
        ((audio, "ports_ready", lambda: False), "no 'Bitwig Studio' output ports within 0s"),
        ((harness, "managed", lambda session: None), "the sandboxed BitwigStudio process is gone"),
        ((harness, "STATUS_SECONDS", 0), "the extension did not answer status on port 61174"),
        ((harness.time, "sleep", lambda s: (_ for _ in ()).throw(KeyboardInterrupt())), "ABORTED"),
    ],
)
def test_every_failure_after_launch_stops_the_sandbox(tmp_path, desktop, monkeypatch, patch, message):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    monkeypatch.setattr(harness, "PORTS_SECONDS", 0)
    monkeypatch.setattr(*patch)
    if patch[1] == "STATUS_SECONDS":
        monkeypatch.setattr(desktop, "cli", lambda args, port, run=None: completed(args, 1))
        monkeypatch.setattr(bitwig, "cli", desktop.cli)
    with pytest.raises(SystemExit, match=re.escape(message)):
        harness.up(cfg, "6.1.7", "")
    assert desktop.killed == [(102, signal.SIGTERM)]
    assert not bitwig.session_alive(102)


def test_up_refuses_when_the_monitor_does_not_start(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    desktop.monitor_starts = False
    monkeypatch.setattr(harness, "MONITOR_SECONDS", 0)
    with pytest.raises(SystemExit, match="the link monitor did not start within 0s"):
        harness.up(cfg, "6.1.7", "")
    assert desktop.killed == [(101, signal.SIGTERM)]
    assert [argv[0] for argv in desktop.spawned] == [sys.executable]


def test_up_refuses_a_running_bitwig_a_held_lock_an_uninitialised_home_and_a_wrong_jar(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    running = fake_process(desktop.proc, 5, DISPLAY=":0")
    with pytest.raises(SystemExit, match=re.escape("Bitwig already running (pid 5 on :0)")):
        harness.up(cfg, "6.1.7", "")
    (running / "comm").write_text("bash\n", encoding="utf-8")
    desktop.fds.append(lock.acquire())
    with pytest.raises(SystemExit, match="another run holds the harness lock"):
        harness.up(cfg, "6.1.7", "")
    shutil.rmtree(cfg.iso(cfg.host("6.1.7")) / "BitwigStudio/prefs")
    with pytest.raises(SystemExit, match=re.escape("is not initialised; run native-init 6.1.7")):
        harness.up(cfg, "6.1.7", "")
    monkeypatch.setattr(doctor, "shared", lambda name: [{"version": "6.1.7", "jarSha256": "other"}])
    with pytest.raises(SystemExit, match=re.escape("is not 6.1.7's other")):
        harness.up(cfg, "6.1.7", "")


def test_down_without_a_run_quits_only_bitwig_on_the_test_display(tmp_path, desktop, monkeypatch):
    cfg = prepared(tmp_path, desktop, monkeypatch)
    fake_process(desktop.proc, 5, DISPLAY=":0")
    fake_process(desktop.proc, 6, DISPLAY=":9")
    quit_calls: list[int] = []

    def quit_bitwig(process, log):
        quit_calls.append(process.pid)
        return "clean"

    monkeypatch.setattr(bitwig, "quit_bitwig", quit_bitwig)
    harness.down(cfg)
    assert quit_calls == [6]


def test_entry_point_dispatches_commands(tmp_path, desktop, monkeypatch, capsys):
    prepared(tmp_path, desktop, monkeypatch)
    monkeypatch.setenv(
        "BWA_NATIVE_CONFIG", str(write_config(tmp_path / "c.toml", tmp_path / "state", tmp_path / "root"))
    )
    monkeypatch.setattr(doctor, "checks", lambda cfg: [(True, "tool bwrap"), (False, "tool magick")])
    capsys.readouterr()
    assert entry.main(["doctor"]) == 1
    assert capsys.readouterr().out == "ok    tool bwrap\nFAIL  tool magick\n"
    assert entry.main(["status"]) == 0
    with pytest.raises(SystemExit, match="no harness run"):
        entry.main(["cli", "status"])
    with pytest.raises(SystemExit, match="no harness run"):
        entry.main(["shot"])
    finished_run(tmp_path / "state/runs/r")
    assert entry.main(["proof", str(tmp_path / "state/runs/r")]) == 0


def test_shot_captures_the_bitwig_window_into_the_run(tmp_path, monkeypatch, quiet):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    lock.write_owner({"run": str(tmp_path), "port": 61174})
    monkeypatch.setenv("BWA_NATIVE_RUN", tmp_path.name)
    monkeypatch.setattr(subprocess, "run", recorder(quiet, "4194305\n"))
    cfg = config.parse({"state_root": "/s", "display": ":9", "port": 1, "fixture_seed": "/s/P", "hosts": {}}, "")
    assert harness.shot(cfg) == tmp_path / "shot-01.png"
    assert quiet[-2] == ["xdotool", "search", "--onlyvisible", "--name", bitwig.WINDOW]
    assert quiet[-1] == ["magick", "import", "-window", "4194305", str(tmp_path / "shot-01.png")]
    monkeypatch.setattr(subprocess, "run", recorder(quiet, ""))
    with pytest.raises(SystemExit, match="no screenshot of display :9"):
        harness.shot(cfg)


def test_capture_reports_a_failed_import(tmp_path, monkeypatch):
    def run(command, **kwargs):
        return completed(command, int(command[0] == "magick"), "4194305\n")

    monkeypatch.setattr(subprocess, "run", run)
    assert harness.capture(":9", tmp_path) is None


def test_snapshots_are_gzip_text(tmp_path):
    evidence.write_snapshot(tmp_path / "s.gz", ["a\t1\t2"])
    assert gzip.decompress((tmp_path / "s.gz").read_bytes()) == b"a\t1\t2\n"
