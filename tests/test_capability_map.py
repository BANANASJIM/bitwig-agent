import json
import re
import sys
from pathlib import Path

import pytest
from pydantic import ValidationError

from bitwig_agent.protocol.bundle import export_bundle
from bitwig_agent.protocol.capabilities import CAPABILITIES, PARTIAL, TAXONOMY
from bitwig_agent.protocol.shared import shared
from bitwig_agent.protocol.verification import Record, Verdicts, contract_hash, contract_hashes, headline_host

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import capabilities as generator

HASHES = contract_hashes()
JARS = {entry["version"]: entry["jarSha256"] for entry in shared("host")}
HEADLINE = "6.1.7 linux"
TITLES = {capability.title: capability.id for capability in TAXONOMY}


def tagged(capability):
    return [name for name, ids in CAPABILITIES.items() if capability in ids]


def record(checks, *, version="6.1.7", platform="linux", jar=None, date="2026-11-27"):
    return Record.model_validate(
        {
            "format": 1,
            "date": date,
            "host": {"version": version, "jarSha256": jar or JARS[version], "platform": platform},
            "extension": {"commit": "2e1bd8c9", "build": "2e1bd8c9+dev"},
            "checks": checks,
            "evidence": "e2e-2026-11-27-post-x8",
        }
    )


def check(capability, result="pass", **hashes):
    methods = {name: HASHES[name] for name in tagged(capability)} | hashes
    return {"capability": capability, "methods": methods, "result": result, "judgement": "tester"}


def render(records):
    verdicts = Verdicts(records)
    anchors = generator.protocol_anchors(generator.PROTOCOL.read_text(encoding="utf-8"))
    return generator.render(verdicts.standings(), headline_host(), len(records), anchors)


def test_generated_map_is_current():
    assert generator.MAP.read_text(encoding="utf-8") == render(generator.load_records()), "run `pixi run capabilities`"


def test_the_first_map_claims_nothing_verified_and_counts_by_task_and_by_audit_row():
    text = render([])
    assert "**Verified on Bitwig 6.1.7 (Linux): 0 of 116 common tasks (0%).**" in text
    assert "| ◔ Available, not yet verified | 86 | 107 |" in text
    assert "| ◑ Partial | 13 | 18 |" in text
    assert "| ○ Not supported | 17 | 20 |" in text
    assert "| Duplicate a track | ◔ Available, not yet verified | [`tracks.duplicate`](../PROTOCOL.md#" in text
    assert "| Split a timeline clip | ○ Not supported | — |" in text
    assert f"◑ Partial: {PARTIAL['project.export-audio'].missing} |" in text
    assert "sha256" not in text
    assert "evidence" not in text.lower()


def test_the_contract_hash_ignores_the_description_but_not_the_schema_or_requirements():
    entry = export_bundle()["methods"]["tracks.set"]
    assert HASHES["tracks.set"] == contract_hash(entry) == contract_hash({**entry, "description": "reworded"})
    assert contract_hash({**entry, "requires": ["engine"]}) != HASHES["tracks.set"]
    assert contract_hash({**entry, "inputSchema": {}}) != HASHES["tracks.set"]
    assert all(re.fullmatch(r"sha256:[0-9a-f]{64}", value) for value in HASHES.values())


def test_a_record_holds_only_the_verdict_and_an_opaque_evidence_reference():
    good = record([check("arrange.tracks.duplicate")]).model_dump(mode="json")
    good["checks"][0]["steps"] = [{"argv": ["bitwig-agent", "tracks", "duplicate"]}]
    with pytest.raises(ValidationError, match="Extra inputs"):
        Record.model_validate(good)
    with pytest.raises(ValidationError, match="unknown capability"):
        record([check("arrange.tracks.fold")])


def test_a_current_pass_on_the_headline_host_verifies_a_capability():
    verdicts = Verdicts([record([check("arrange.tracks.duplicate")])])
    standing = next(s for s in verdicts.standings() if s.capability.id == "arrange.tracks.duplicate")
    assert standing.verified_on == (HEADLINE,)
    assert "arrange.tracks.duplicate" not in {gap.capability.id for gap in verdicts.gaps(HEADLINE)}


def gap(records, capability):
    return next(g for g in Verdicts(records).gaps(HEADLINE) if g.capability.id == capability)


def test_a_verdict_goes_stale_when_a_tagged_methods_contract_changes_or_was_never_checked():
    changed = gap([record([check("arrange.markers", **{"markers.set": "sha256:" + "0" * 64})])], "arrange.markers")
    assert (changed.methods, changed.reason) == (("markers.set",), "stale: contract changed (markers.set)")
    partial = check("arrange.markers")
    del partial["methods"]["markers.delete"]
    assert gap([record([partial])], "arrange.markers").reason == "stale: contract changed (markers.delete)"
    assert gap([], "arrange.markers").reason == "never verified"


def test_a_verdict_on_a_jar_no_longer_supported_or_another_platform_does_not_count():
    unknown_jar = gap([record([check("project.save")], jar="f" * 64)], "project.save")
    assert unknown_jar.reason == "stale: host jar not in host.json"
    verdicts = Verdicts(
        [
            record([check("project.save")], platform="windows"),
            record([check("project.save")], version="6.1.4"),
        ]
    )
    standing = next(s for s in verdicts.standings() if s.capability.id == "project.save")
    assert standing.verified_on == ("6.1.7 windows", "6.1.4 linux")
    assert "project.save" in {g.capability.id for g in verdicts.gaps(HEADLINE)}


def test_the_newest_current_verdict_decides_and_a_failure_wins_a_tie():
    passed, failed = check("project.save"), check("project.save", "fail")
    assert gap([record([passed]), record([failed], date="2026-11-28")], "project.save").reason == "failed"
    assert gap([record([passed, failed])], "project.save").reason == "failed"
    assert Verdicts([record([failed]), record([passed], date="2026-11-28")]).passes("project.save", HEADLINE)


def test_the_queue_lists_every_fully_covered_unverified_capability_common_tasks_first():
    gaps = Verdicts([]).gaps(HEADLINE)
    assert len(gaps) == 95
    assert all(g.capability.id not in PARTIAL and tagged(g.capability.id) for g in gaps)
    tiers = [g.capability.tier for g in gaps]
    assert tiers == sorted(tiers, key=["high", "medium", "low"].index)


def test_the_map_never_claims_verified_without_a_current_pass_for_every_tagged_method():
    records = [
        record([check(c.id) for c in TAXONOMY if tagged(c.id) and c.id != "arrange.markers"]),
        record([check("arrange.markers", **{"markers.set": "sha256:" + "0" * 64})]),
        record([check("project.save", "fail")], date="2026-11-28"),
        record([check("arrange.tracks.duplicate")], jar="f" * 64, date="2026-11-29"),
    ]
    claimed = []
    for line in render(records).splitlines():
        cells = line.split(" | ")
        if len(cells) == 3 and "Verified on 6.1.7 Linux" in cells[1]:
            claimed.append(TITLES[cells[0].removeprefix("| ").removesuffix(" †")])
    assert "arrange.markers" not in claimed
    assert "project.save" not in claimed
    assert len(claimed) > 90
    for capability in claimed:
        assert any(
            c.capability == capability
            and c.result == "pass"
            and (r.host.version, r.host.platform) == ("6.1.7", "linux")
            and JARS.get(r.host.version) == r.host.jarSha256
            and all(c.methods.get(name) == HASHES[name] for name in tagged(capability))
            for r in records
            for c in r.checks
        ), capability


def test_rows_name_other_hosts_and_partial_rows_their_verification():
    text = render([record([check("project.save"), check("project.export-audio")], version="6.1.4")])
    assert "| Save the project | ◔ Available, not yet verified (also verified on 6.1.4 Linux) |" in text
    text = render([record([check("project.export-audio")])])
    assert f"◑ Partial: {PARTIAL['project.export-audio'].missing} Verified on 6.1.7 Linux. |" in text


def test_protocol_anchors_skip_code_blocks_and_number_repeated_headings():
    text = "# Intro\n```sh\n# not a heading\n```\n## Tracks\n| `tracks.list` | x |\n## Tracks\n`batch` runs calls\n"
    assert generator.protocol_anchors(text) == {"tracks.list": "tracks", "batch": "tracks-1"}


def test_the_queue_prints_as_text_and_json(monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["capabilities.py", "--to-verify", "--json"])
    generator.main()
    queue = json.loads(capsys.readouterr().out)
    assert queue["host"] == HEADLINE
    assert queue["capabilities"][0] == {
        "id": "concepts.transport",
        "title": TAXONOMY[0].title,
        "tier": "high",
        "methods": ["transport.get", "transport.set"],
        "reason": "never verified",
    }
    monkeypatch.setattr(sys, "argv", ["capabilities.py", "--to-verify"])
    generator.main()
    assert capsys.readouterr().out.startswith("To verify on 6.1.7 Linux: 95\n")
