"""Keep every test away from the user's real config and state: point the XDG dirs (and Windows'
LOCALAPPDATA) at a throwaway folder before any bridge module computes its paths at import."""
import os
import tempfile

_ROOT = tempfile.mkdtemp(prefix="hermes-remote-tests-")
for _k, _sub in (("XDG_CONFIG_HOME", "config"), ("XDG_STATE_HOME", "state"), ("XDG_DATA_HOME", "data"),
                 ("LOCALAPPDATA", "localappdata")):
    os.environ[_k] = os.path.join(_ROOT, _sub)
