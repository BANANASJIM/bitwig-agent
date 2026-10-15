"""Native preflight safety parsing only; no host, /proc or desktop access."""
import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/smoke-native.py"
spec = importlib.util.spec_from_file_location("native_smoke", SCRIPT)
native_smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native_smoke)

HEADER = "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"


def table(address, *, port="EEF4", state="0A", inode="16083494"):
    # Proc rows combine tx/rx and tr/tm fields; inode is the tenth whitespace field.
    return HEADER + (f"  5: {address}:{port} 00000000000000000000000000000000:0000 "
                     f"{state} 00000000:00000000 00:00000000 00000000 1000 0 {inode} 1\n")


@pytest.mark.parametrize("address,byteorder", [
    ("0100007F", "little"),
    # Native host observation supplied by root: TCP4 connections work while the
    # listening inode is listed only in tcp6 with this mapped address.
    ("0000000000000000FFFF00000100007F", "little"),
    ("7F000001", "big"),
    ("00000000000000000000FFFF7F000001", "big"),
])
def test_accepts_exact_owned_ipv4_or_mapped_loopback(address, byteorder):
    assert native_smoke.has_owned_loopback_listener(
        table(address), 61172, {"16083494"}, byteorder=byteorder)


@pytest.mark.parametrize("address", [
    "00000000",  # IPv4 wildcard
    "00000000000000000000000000000000",  # IPv6 wildcard
    "0000000000000000FFFF000000000000",  # mapped IPv4 wildcard
    "0200007F",  # 127.0.0.2 is not the configured 127.0.0.1 endpoint
    "00000000000000000000000001000000",  # native IPv6 ::1, not mapped IPv4
    "0101A8C0",  # 192.168.1.1
    "not_hex!",
])
def test_rejects_wildcard_other_addresses_and_malformed_addresses(address):
    assert not native_smoke.has_owned_loopback_listener(
        table(address), 61172, {"16083494"}, byteorder="little")


@pytest.mark.parametrize("changes,owners", [
    ({"inode": "999999"}, {"16083494"}),
    ({}, set()),
    ({"port": "EEF5"}, {"16083494"}),
    ({"state": "01"}, {"16083494"}),
])
def test_rejects_foreign_socket_wrong_port_or_non_listener(changes, owners):
    assert not native_smoke.has_owned_loopback_listener(
        table("0000000000000000FFFF00000100007F", **changes),
        61172, owners, byteorder="little")


def test_ignores_short_rows_and_finds_later_owned_listener():
    rows = HEADER + "  0: incomplete\n"
    assert not native_smoke.has_owned_loopback_listener(rows, 61172, {"16083494"})
    rows += table("00000000").splitlines()[1] + "\n"
    rows += table("0000000000000000FFFF00000100007F").splitlines()[1] + "\n"
    assert native_smoke.has_owned_loopback_listener(rows, 61172, {"16083494"}, byteorder="little")


def test_nested_task_group_failures_retain_actionable_leaf_causes():
    error = ExceptionGroup("task group", [
        ExceptionGroup("session", [RuntimeError("devices.remote.get failed")]),
        TimeoutError("tracks.get deadline exceeded"),
    ])
    detail = native_smoke.describe_error(error)
    assert "devices.remote.get" in detail
    assert "tracks.get" in detail
    assert "RuntimeError" in detail and "TimeoutError" in detail
