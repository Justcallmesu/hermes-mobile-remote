from hermes_remote_bridge import cli


def test_desktop_app_env_name_is_stable():
    # The desktop app's unit and child set this exact name (linux_service.rs, run_mode.rs).
    assert cli.NO_PROMPT_ENV == "HERMES_REMOTE_NO_TRUST_PROMPT"


def test_tests_never_see_the_real_config_dir():
    from hermes_remote_bridge.config import CONFIG_DIR
    assert "hermes-remote-tests-" in str(CONFIG_DIR)
