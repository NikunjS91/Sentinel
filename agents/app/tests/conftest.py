"""Keep the unit suite hermetic.

Settings now load the repo-root .env deterministically (anchored, not
CWD-relative), so without this fixture the suite would inherit whatever
backend the developer's .env selects. Pin the volatile fields to the
field defaults; tests that need something else monkeypatch it themselves.
"""

from __future__ import annotations

import pytest

from app.settings import settings


@pytest.fixture(autouse=True)
def _hermetic_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(settings, "llm_backend", "ollama")
    monkeypatch.setattr(settings, "tool_mode", "live")
    monkeypatch.setattr(settings, "nim_api_key", "")
