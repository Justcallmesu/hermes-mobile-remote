import pytest

from hermes_remote_bridge.profiles import ProfileRefused, is_visible, resolve_profile


@pytest.mark.parametrize("value", [None, "", "  ", "default", "Default", "DEFAULT"])
def test_no_name_or_default_means_the_default_profile(value):
    assert resolve_profile(value) is None
    assert resolve_profile(value, ["developer"]) is None  # always reachable, even when restricted


def test_named_profile_is_normalised_like_hermes_does():
    assert resolve_profile("developer") == "developer"
    assert resolve_profile(" Developer ") == "developer"
    assert resolve_profile("bkm-reports-bot") == "bkm-reports-bot"


@pytest.mark.parametrize("value", ["../etc", "a b", "-p", "x" * 65, "dev/../pm", "_lead"])
def test_malformed_names_are_refused_as_bad_requests(value):
    with pytest.raises(ProfileRefused) as err:
        resolve_profile(value)
    assert err.value.status == 400


def test_allowlist_limits_named_profiles():
    assert resolve_profile("developer", ["developer", "pm"]) == "developer"
    with pytest.raises(ProfileRefused) as err:
        resolve_profile("bkm-reports-bot", ["developer", "pm"])
    assert err.value.status == 403


def test_visibility_follows_the_same_allowlist():
    assert is_visible("default", ["developer"]) and is_visible("developer", ["developer"])
    assert not is_visible("pm", ["developer"])
    assert is_visible("pm", [])
