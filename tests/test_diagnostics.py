"""diagnostics.get against synthetic Bitwig logs (no real log, no Bitwig)."""

import asyncio
import re
from datetime import datetime, timedelta

import pytest

from bitwig_agent import diagnostics
from bitwig_agent.core import composed
from bitwig_agent.errors import bridge_error
from bitwig_agent.protocol import CLIENT_SIDE, validate_call


def stamp(t):
    return (
        t.strftime("%Y-%m-%d %H:%M:") + f"{t.second}.{t.microsecond // 1000:03d}"
    )  # unpadded seconds as Bitwig writes some


def line(t, component, level, msg):
    return f"[{stamp(t)} {component} {level}] {msg}\n"


class Bridge:
    def __init__(self, instance=None, fail=False):
        self.instance, self.fail = instance, fail

    async def call(self, method, params, timeout_ms=5000):
        assert method == "status"
        if self.fail:
            raise bridge_error("not_ready", "no host")
        return {"instance": self.instance}


@pytest.fixture
def log(tmp_path, monkeypatch):
    path = tmp_path / "BitwigStudio.log"
    monkeypatch.setenv("BITWIG_LOG_PATH", str(path))
    monkeypatch.setattr(diagnostics, "process_start", lambda pid: None)
    monkeypatch.setattr(diagnostics, "bitwig_processes", lambda: 1)
    return path


def run(args=None, bridge=None):
    params, timeout = validate_call("diagnostics.get", args or {})
    return asyncio.run(composed.run("diagnostics.get", bridge or Bridge({"pid": 1}), params, timeout))


NOW = datetime.now().replace(microsecond=0)


def header(t):
    return line(t, "float-main-app", "info", "Starting application version 6.1.4 revision abc")


def test_registered():
    assert "diagnostics.get" in CLIENT_SIDE
    for bad in ({"sinceSeconds": 0}, {"sinceSeconds": 5, "sinceMarker": "x"}, {"maxEvents": 0}):
        with pytest.raises(ValueError, match=re.escape("1 validation error for DiagnosticsGetArgs")):
            validate_call("diagnostics.get", bad)


def test_missing_log_is_unsupported(log):
    result = run()
    assert result["supported"] is False
    assert result["searched"] == [str(log)]


def test_classifies_and_dedups(log):
    t = NOW - timedelta(seconds=60)
    log.write_text(
        header(t - timedelta(seconds=5))
        + line(
            t,
            "float-document-master",
            "error",
            "component nitro should be rejected from the network because it doesn't have all required in-port connected",
        )
        + line(
            t,
            "float-document-master",
            "error",
            "component nitro should be rejected from the network because it doesn't have all required in-port connected",
        )
        + line(
            t,
            "float-document-master",
            "error",
            "component flt should be rejected from the network because it doesn't have all required in-port connected",
        )
        + line(t, "float-document-master", "info", "FloatDocument.isEngineReadyChanged(true->false)")
        + line(t, "notifications", "info", "Update Available - v6.1.5: A new release of Bitwig Studio is available.")
        + line(t, "notifications", "info", "Analyzing audio...: ")
        + line(t, "float-extension-host", "error", "Extension 'Bitwig Agent' failed to flush")
        + line(t, "float-main", "error", "java.lang.IllegalStateException: boom")
        + "\tat a.b.C.d(C.java:1)\n\tat a.b.C.e(C.java:2)\n"
        + line(t, "audio", "warn", "Cannot use realtime priority")
        + line(t, "audio", "warn", "something odd")
        + line(t, "audio", "info", "chatter")
    )
    result = run()
    events = result["events"]
    assert [e["category"] for e in events] == [
        "grid_rejected",
        "grid_rejected",
        "engine",
        "dialog",
        "extension",
        "exception",
        "other",
    ]
    assert events[0]["count"] == 2
    assert events[0]["gridComponent"] == "nitro"
    assert events[5]["detailLines"] == 2
    assert "at a.b" not in events[5]["message"]
    assert result["summary"]["counts"]["grid_rejected"] == 3
    assert result["summary"]["gridRejectedComponents"] == ["flt", "nitro"]
    assert result["summary"]["engineReady"] is False
    assert "Dashboard" in result["dialogs"]


def test_window_and_max_events(log):
    log.write_text(
        header(NOW - timedelta(hours=2))
        + line(NOW - timedelta(hours=1), "x", "error", "old problem")
        + "".join(line(NOW - timedelta(seconds=10 - i), "x", "error", f"problem {i}") for i in range(5))
    )
    assert [e["message"] for e in run()["events"]] == [f"problem {i}" for i in range(5)]
    assert "old problem" in [e["message"] for e in run({"sinceSeconds": 7200})["events"]]
    capped = run({"maxEvents": 2})
    assert [e["message"] for e in capped["events"]] == ["problem 3", "problem 4"]
    assert capped["droppedOlderEvents"] == 3


def test_marker_returns_only_new_events(log):
    log.write_text(header(NOW - timedelta(minutes=1)) + line(NOW, "x", "error", "first"))
    first = run()
    assert [e["message"] for e in first["events"]] == ["first"]
    with log.open("a") as f:
        f.write(line(NOW, "x", "error", "second"))
    second = run({"sinceMarker": first["marker"]})
    assert [e["message"] for e in second["events"]] == ["second"]
    assert second["window"] == {"sinceMarker": True}
    assert run({"sinceMarker": second["marker"]})["events"] == []


def test_stale_marker_falls_back(log):
    log.write_text(header(NOW - timedelta(minutes=1)) + line(NOW, "x", "error", "one"))
    marker = run()["marker"]
    log.write_text(header(NOW - timedelta(seconds=30)) + line(NOW, "x", "error", "fresh start"))
    result = run({"sinceMarker": marker})
    assert [e["message"] for e in result["events"]] == ["fresh start"]
    assert "stale" in result["warnings"][0]
    assert run({"sinceMarker": "garbage!"})["warnings"]


def test_attribution_by_process_start(log, monkeypatch):
    a, b = NOW - timedelta(minutes=30), NOW - timedelta(minutes=10)
    log.write_text(
        header(a)
        + line(a + timedelta(seconds=5), "x", "error", "from A")
        + header(b)
        + line(b + timedelta(seconds=5), "x", "error", "from B")
    )
    monkeypatch.setattr(diagnostics, "process_start", lambda pid: a - timedelta(seconds=2))
    result = run({"sinceSeconds": 7200})
    assert [e["message"] for e in result["events"]] == ["from A"]
    att = result["attribution"]
    assert att["method"] == "pid_start_time"
    assert att["certain"] is False
    assert "later Bitwig start" in att["uncertainty"][0]
    monkeypatch.setattr(diagnostics, "process_start", lambda pid: b - timedelta(seconds=2))
    result = run({"sinceSeconds": 7200})
    assert [e["message"] for e in result["events"]] == ["from B"]
    assert result["attribution"]["certain"] is True


def test_no_instance_is_uncertain_latest_section(log):
    a, b = NOW - timedelta(minutes=30), NOW - timedelta(minutes=10)
    log.write_text(header(a) + line(a, "x", "error", "A") + header(b) + line(b, "x", "error", "B"))
    result = run({"sinceSeconds": 7200}, Bridge(fail=True))
    assert [e["message"] for e in result["events"]] == ["B"]
    att = result["attribution"]
    assert att["method"] == "latest_section"
    assert att["certain"] is False
    assert att["instance"] is None
    assert "status unavailable" in att["uncertainty"][0]


def test_large_log_reads_from_the_end(log, monkeypatch):
    monkeypatch.setattr(diagnostics, "CHUNK", 4096)
    t = NOW - timedelta(seconds=30)
    log.write_text(
        header(NOW - timedelta(days=1))
        + "".join(line(NOW - timedelta(hours=5), "x", "info", "filler" * 20) for _ in range(2000))
        + header(t)
        + line(t, "x", "error", "needle")
    )
    result = run()
    assert [e["message"] for e in result["events"]] == ["needle"]
    assert result["attribution"]["sectionsSeen"] == 1  # stopped at the newest header, did not read the whole file
