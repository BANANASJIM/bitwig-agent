import asyncio
import json
import re
from typing import Any

import pytest
from pydantic import ValidationError

from bitwig_agent.client import BridgeClient, configured_port, package_version
from bitwig_agent.core import composed
from bitwig_agent.errors import BridgeError
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, validate_call
from bitwig_agent.protocol.base import PROTOCOL
from bitwig_agent.protocol.limits import MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES
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


async def test_write_timeout_is_an_unknown_result_and_never_retried(host):
    host.delay = 1
    client = BridgeClient(host.port, response_grace_seconds=0.01)
    result = await client.call("parameters.set", SET, timeout_ms=100)
    assert result["outcome"] == "unknown"
    assert result["reason"] == "deadline"
    assert "not retried" in result["message"]
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
        lambda request: {"id": request["id"], "error": {"code": "stale", "message": "missing reason"}},
        lambda request: {
            "id": request["id"],
            "error": {"code": "stale", "reason": "busy", "message": "reason of another code"},
        },
        lambda request: {"id": request["id"], "error": {"code": "x", "reason": "x", "message": "unknown reason"}},
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
    result = await BridgeClient(host.port).call("parameters.set", SET)
    assert (result["outcome"], result["reason"]) == ("unknown", "transport")
    assert host.connections == 1


async def test_an_untrustworthy_read_response_is_an_error(host):
    host.reply = lambda request: b"not JSON\n"
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("status", {})
    assert (caught.value.error["code"], caught.value.error["reason"]) == ("unavailable", "transport")
    assert "outcome" not in caught.value.error


async def test_requests_carry_the_protocol_version(host):
    await BridgeClient(host.port).call("status", {})
    assert host.requests[0]["protocol"] == PROTOCOL


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


async def test_connection_refused_is_an_error_even_for_a_write():
    server = await asyncio.start_server(lambda reader, writer: writer.close(), "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    server.close()
    await server.wait_closed()
    for method, params in (("status", {}), ("parameters.set", SET)):
        with pytest.raises(BridgeError) as caught:
            await BridgeClient(port).call(method, params)
        assert (caught.value.error["code"], caught.value.error["reason"]) == ("unavailable", "transport")


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
    unknown = {"outcome": "unknown", "reason": "deadline", "message": "Inspect child before another write"}
    host.reply = lambda request: None if lost_response else {"id": request["id"], "result": unknown}
    params = TOOLS["parameters.layer.set"].examples[0]
    result = await BridgeClient(host.port).call("parameters.layer.set", params)
    assert result["outcome"] == "unknown"
    if not lost_response:
        assert result == unknown
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


async def test_recovery_methods_take_one_id(host):
    host.reply = lambda r: {"id": r["id"], "result": {"outcome": "applied"}}
    await BridgeClient(host.port).call("ack", {"id": "routing.quarantine"})
    assert host.requests[-1]["params"] == {"id": "routing.quarantine"}
    assert TOOLS["ack"].read_only is False
    assert TOOLS["jobs.get"].read_only is True
    for method in ("ack", "jobs.get"):
        for bad in ({}, {"id": ""}, {"id": 1}, {"id": "x", "x": 1}):
            with pytest.raises(ValidationError):
                validate_call(method, bad)
    assert not {"extension.restart", "grid.topology.reconcile", "render.status"} & set(TOOLS)


async def test_oversized_grid_get_response_says_to_lower_module_limit(host):
    host.reply = lambda request: b"x" * (MAX_RESPONSE_BYTES + 1) + b"\n"
    params = {"trackId": SET["trackId"], "deviceIndex": 0, "expectedDeviceName": "Grid", "moduleLimit": 256}
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("grid.get", params)
    assert "lower moduleLimit" in caught.value.error["message"]
    assert "includeParameters=false" in caught.value.error["message"]


def test_client_side_set_matches_handlers():
    assert frozenset(composed.HANDLERS) == CLIENT_SIDE
