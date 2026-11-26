import asyncio
import json
import re
from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent import client_side
from bitwig_agent.client import MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES, BridgeClient, configured_port, package_version
from bitwig_agent.errors import BridgeError
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, validate_call
from bitwig_agent.protocol.parameters import SetArgs

pytestmark = pytest.mark.anyio

SET: dict[str, Any] = {
    "trackId": "12345678-1234-1234-1234-123456789abc",
    "deviceIndex": 0,
    "expectedDeviceName": "Polymer",
    "contextToken": "c",
    "parameterId": "p",
    "value": 0.5,
}


async def test_timeout_unknown_and_no_retry(host):
    host.delay = 1
    client = BridgeClient(host.port, response_grace_seconds=0.01)
    with pytest.raises(BridgeError) as caught:
        await client.call("parameters.set", SET, timeout_ms=100)
    assert caught.value.error["code"] == "deadline_exceeded"
    assert caught.value.error["outcome"] == "unknown"
    await asyncio.sleep(0.05)
    assert host.connections == 1
    assert len(host.requests) == 1


@pytest.mark.parametrize(
    "reply",
    [
        lambda request: {"id": "wrong-id", "result": {}},
        lambda request: {"id": request["id"], "result": {}, "error": {}},
        lambda request: {"id": request["id"]},
        lambda request: {"id": request["id"], "result": []},
        lambda request: {"id": request["id"], "error": {"code": 1, "message": "bad"}},
        lambda request: {"id": request["id"], "error": {"code": "bad", "message": "missing outcome"}},
        lambda request: {
            "id": request["id"],
            "error": {"code": "bad", "message": "invalid outcome", "outcome": "success"},
        },
        lambda request: ('{"id":"' + request["id"] + '","result":{"value":1e9999}}\n').encode(),
        lambda request: b"not JSON\n",
        lambda request: b"\xff\n",
        lambda request: b'{"id":"first","id":"second","result":{}}\n',
        lambda request: b'{"unfinished":',
        lambda request: None,
        lambda request: (json.dumps({"id": request["id"], "result": {"value": float("nan")}}) + "\n").encode(),
        lambda request: b"x" * (MAX_RESPONSE_BYTES + 1) + b"\n",
    ],
)
async def test_untrustworthy_response_rejected_without_retry(host, reply):
    host.reply = reply
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("parameters.set", SET)
    assert caught.value.error["outcome"] == "unknown"
    assert host.connections == 1


@pytest.mark.parametrize("timeout", [99, 10001, True, 5000.0])
async def test_invalid_timeout_rejected_before_connection(host, timeout):
    with pytest.raises(ValueError, match=re.escape("timeoutMs must be an integer in 100..10000")):
        await BridgeClient(host.port).call("status", {}, timeout)
    assert host.connections == 0


async def test_topology_edits_allow_a_60_second_timeout(host):
    target = {"trackId": "12345678-1234-1234-1234-123456789abc", "deviceIndex": 0, "expectedDeviceName": "Poly Grid"}
    with pytest.raises(ValueError, match=r"100\.\.60000"):
        await BridgeClient(host.port).call("grid.modules.insert", {**target, "typeName": "Add", "x": 0, "y": 0}, 60001)
    with pytest.raises(ValueError, match=r"100\.\.10000"):
        await BridgeClient(host.port).call(
            "grid.parameters.get", {**target, "moduleId": target["trackId"], "parameterId": "P"}, 60000
        )
    assert host.connections == 0


async def test_connection_refused_is_not_dispatched():
    server = await asyncio.start_server(lambda reader, writer: writer.close(), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    server.close()
    await server.wait_closed()
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(port).call("status", {})
    assert caught.value.error["outcome"] == "not_dispatched"


async def test_large_or_nonfinite_request_rejected_before_connection(host):
    unsendable: list[dict[str, Any]] = [{"large": "x" * MAX_REQUEST_BYTES}, {"value": float("nan")}]
    for params in unsendable:
        with pytest.raises(ValueError, match=r"validation errors? for SetArgs"):
            await BridgeClient(host.port).call("parameters.set", params)
    assert host.connections == 0


def test_nonfinite_parameter_values_rejected():
    args = {
        "trackId": "12345678-1234-1234-1234-123456789abc",
        "deviceIndex": 0,
        "expectedDeviceName": "Polymer",
        "contextToken": "c",
        "parameterId": "p",
    }
    for value in [float("nan"), float("inf"), -float("inf")]:
        with pytest.raises(ValidationError):
            SetArgs.model_validate({**args, "value": value})


def test_port_environment(monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "62222")
    assert configured_port() == 62222
    for value in ["0", "65536", "not-a-port"]:
        monkeypatch.setenv("BITWIG_AGENT_PORT", value)
        with pytest.raises(ValueError, match=r"BITWIG_AGENT_PORT must be in|invalid literal"):
            configured_port()


@pytest.mark.parametrize("lost_response", [False, True])
async def test_layer_client_unknown_write_is_not_retried(host, lost_response):
    error = {
        "code": "deadline_exceeded",
        "message": "Inspect child before another write",
        "outcome": "unknown",
        "details": {"dispatched": True},
    }
    host.reply = lambda request: None if lost_response else {"id": request["id"], "error": error}
    params = TOOLS["parameters.layer.set"].examples[0]
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("parameters.layer.set", params)
    assert caught.value.error["outcome"] == "unknown"
    if not lost_response:
        assert caught.value.error == error
    assert host.requests[0]["params"] == params
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize(
    "changes",
    [
        {"parent": None},
        {"childDeviceIndex": 2},
        {"childDeviceIndex": 1.0},
        {"layerId": "1-1-1-1-1"},
        {"discoveryToken": ""},
        {"value": float("nan")},
        {"value": float("inf")},
        {"value": True},
        {"padIndex": 0},
        {"slotIndex": 0},
    ],
)
async def test_invalid_layer_client_arguments_do_not_connect(host, changes):
    with pytest.raises(ValidationError):
        await BridgeClient(host.port).call(
            "parameters.layer.set", {**TOOLS["parameters.layer.set"].examples[0], **changes}
        )
    assert host.connections == 0


async def test_status_flags_extension_version_mismatch(host):

    host.reply = lambda r: {
        "id": r["id"],
        "result": {"status": "observed", "extension": {"version": "0.0.1", "build": "abc"}},
    }
    result = await BridgeClient(host.port).call("status", {})
    assert result["extensionMismatch"]["extensionVersion"] == "0.0.1"
    assert result["extensionMismatch"]["clientVersion"] == package_version()
    host.reply = lambda r: {
        "id": r["id"],
        "result": {"status": "observed", "extension": {"version": package_version(), "build": "abc"}},
    }
    assert "extensionMismatch" not in await BridgeClient(host.port).call("status", {})
    host.reply = lambda r: {"id": r["id"], "result": {"status": "observed"}}
    assert (await BridgeClient(host.port).call("status", {}))["extensionMismatch"]["extensionVersion"] is None


async def test_extension_restart_params(host):
    host.reply = lambda r: {"id": r["id"], "result": {"status": "dispatched"}}
    await BridgeClient(host.port).call("extension.restart", {"discardUnresolved": True})
    assert host.requests[-1]["params"] == {"discardUnresolved": True}
    assert TOOLS["extension.restart"].read_only is False
    for bad in ({"discardUnresolved": 1}, {"discardUnresolved": None}, {"x": 1}):
        with pytest.raises(ValidationError):
            validate_call("extension.restart", bad)


async def test_oversized_grid_get_response_says_to_lower_module_limit(host):
    host.reply = lambda request: b"x" * (MAX_RESPONSE_BYTES + 1) + b"\n"
    params = {"trackId": SET["trackId"], "deviceIndex": 0, "expectedDeviceName": "Grid", "moduleLimit": 256}
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("grid.get", params)
    assert "lower moduleLimit" in caught.value.error["message"]
    assert "includeParameters=false" in caught.value.error["message"]


def test_client_side_set_matches_handlers():
    assert frozenset(client_side.HANDLERS) == CLIENT_SIDE
