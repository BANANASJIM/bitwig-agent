import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import validate_call


def test_actions_list_filters_and_invoke_consent_flag_serialize_only_when_given():
    params, _ = validate_call("actions.list", {"query": "save", "category": "File", "limit": 5})
    assert params == {"query": "save", "category": "File", "limit": 5}
    for bad in ({"query": ""}, {"category": ""}, {"limit": 0}, {"limit": 1001}, {"query": None}):
        with pytest.raises(ValidationError):
            validate_call("actions.list", bad)
    assert validate_call("actions.invoke", {"actionId": "Quit"})[0] == {"actionId": "Quit"}
    assert (
        validate_call("actions.invoke", {"actionId": "Quit", "discardUnsavedChanges": True})[0]["discardUnsavedChanges"]
        is True
    )
    with pytest.raises(ValidationError):
        validate_call("actions.invoke", {"actionId": "Quit", "discardUnsavedChanges": "yes"})
