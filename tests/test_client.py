import asyncio
import json

import pytest
from pydantic import ValidationError

from bitwig_agent.client import BridgeClient, BridgeError, MAX_BYTES, configured_port
from bitwig_agent.catalog import TOOLS, validate_call
from bitwig_agent.schema import SetArgs

pytestmark = pytest.mark.anyio

SET = {"trackId": "12345678-1234-1234-1234-123456789abc", "deviceIndex": 0,
       "expectedDeviceName": "Polymer", "contextToken": "c", "parameterId": "p", "value": 0.5}


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


@pytest.mark.parametrize("reply", [
    lambda request: {"id": "wrong-id", "result": {}},
    lambda request: {"id": request["id"], "result": {}, "error": {}},
    lambda request: {"id": request["id"]},
    lambda request: {"id": request["id"], "result": []},
    lambda request: {"id": request["id"], "error": {"code": 1, "message": "bad"}},
    lambda request: {"id": request["id"], "error": {"code": "bad", "message": "missing outcome"}},
    lambda request: {"id": request["id"], "error": {"code": "bad", "message": "invalid outcome", "outcome": "success"}},
    lambda request: ('{"id":"' + request["id"] + '","result":{"value":1e9999}}\n').encode(),
    lambda request: b"not JSON\n",
    lambda request: b"\xff\n",
    lambda request: b'{"id":"first","id":"second","result":{}}\n',
    lambda request: b'{"unfinished":',
    lambda request: None,
    lambda request: (json.dumps({"id": request["id"], "result": {"value": float("nan")}}) + "\n").encode(),
    lambda request: b"x" * (MAX_BYTES + 1) + b"\n",
])
async def test_untrustworthy_response_rejected_without_retry(host, reply):
    host.reply = reply
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("parameters.set", SET)
    assert caught.value.error["outcome"] == "unknown"
    assert host.connections == 1


@pytest.mark.parametrize("timeout", [99, 10001, True, 5000.0])
async def test_invalid_timeout_rejected_before_connection(host, timeout):
    with pytest.raises(ValueError):
        await BridgeClient(host.port).call("status", {}, timeout)
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
    for params in [{"large": "x" * MAX_BYTES}, {"value": float("nan")}]:
        with pytest.raises(ValueError):
            await BridgeClient(host.port).call("parameters.set", params)
    assert host.connections == 0


def test_nonfinite_parameter_values_rejected():
    args = {"trackId": "12345678-1234-1234-1234-123456789abc", "deviceIndex": 0, "expectedDeviceName": "Polymer", "contextToken": "c", "parameterId": "p"}
    for value in [float("nan"), float("inf"), -float("inf")]:
        with pytest.raises(ValidationError):
            SetArgs.model_validate({**args, "value": value})


def test_port_environment(monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "62222")
    assert configured_port() == 62222
    for value in ["0", "65536", "not-a-port"]:
        monkeypatch.setenv("BITWIG_AGENT_PORT", value)
        with pytest.raises(ValueError):
            configured_port()


@pytest.mark.parametrize("lost_response", [False, True])
async def test_layer_client_unknown_write_is_not_retried(host, lost_response):
    error = {"code": "deadline_exceeded", "message": "Inspect child before another write",
             "outcome": "unknown", "details": {"dispatched": True}}
    host.reply = lambda request: None if lost_response else {"id": request["id"], "error": error}
    params = TOOLS["parameters.layer.set"].examples[0]
    with pytest.raises(BridgeError) as caught:
        await BridgeClient(host.port).call("parameters.layer.set", params)
    assert caught.value.error["outcome"] == "unknown"
    if not lost_response:
        assert caught.value.error == error
    assert host.requests[0]["params"] == params
    assert len(host.requests) == host.connections == 1


@pytest.mark.parametrize("changes", [
    {"parent": None}, {"childDeviceIndex": 2}, {"childDeviceIndex": 1.0},
    {"layerId": "1-1-1-1-1"}, {"discoveryToken": ""}, {"value": float("nan")},
    {"value": float("inf")}, {"value": True}, {"padIndex": 0}, {"slotIndex": 0},
])
async def test_invalid_layer_client_arguments_do_not_connect(host, changes):
    with pytest.raises(ValidationError):
        await BridgeClient(host.port).call("parameters.layer.set", {
            **TOOLS["parameters.layer.set"].examples[0], **changes})
    assert host.connections == 0
