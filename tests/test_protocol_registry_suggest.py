from bitwig_agent.protocol import unknown_method_message


def test_a_wrong_resource_with_a_real_verb_suggests_that_verb_elsewhere():
    message = unknown_method_message("launcher.launch")
    assert "clips.launch" in message
    assert "launcher.list" not in message


def test_a_typo_keeps_the_single_close_spelling():
    assert "(did you mean tracks.set?)" in unknown_method_message("tracks.sett")
    assert "(did you mean tracks.get?)" in unknown_method_message("track.get")


def test_nothing_close_suggests_nothing():
    assert "did you mean" not in unknown_method_message("zzzz.qqqq")
