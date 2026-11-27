from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.base import NOT_RECORDING, STOPPED


def test_a_write_declares_at_most_one_transport_requirement_and_reads_declare_none():
    for name, spec in TOOLS.items():
        declared = set(spec.requires) & {NOT_RECORDING[0], STOPPED[0]}
        assert len(declared) <= 1, name
        if spec.read_only or not spec.host:
            assert not declared, name


def test_the_recording_controls_declare_none():
    for name in ("transport.set", "clips.record", "launcher.stop", "tracks.set"):
        assert not set(TOOLS[name].requires) & {NOT_RECORDING[0], STOPPED[0]}, name
    assert STOPPED[0] in TOOLS["tracks.routing.set"].requires
    assert NOT_RECORDING[0] in TOOLS["devices.sidechain.set"].requires
