"""Versioned prompt registry.

Prompts live as text files under app/prompts/. Each file's SHA-256 hash
(first 12 chars) is its version. Loaded once at startup; never mutated
afterward."""

from __future__ import annotations

import hashlib
import logging
from pathlib import Path

from pydantic import BaseModel

log = logging.getLogger(__name__)


class Prompt(BaseModel):
    """A single loaded prompt and its version."""

    name: str
    body: str
    version: str


class PromptNotFound(KeyError):
    """Raised when an agent asks for a prompt that doesn't exist."""


class PromptRegistry:
    """Loads and caches prompts from a directory. Immutable after construction."""

    def __init__(self, prompts_dir: Path) -> None:
        self._prompts: dict[str, Prompt] = {}
        self._load(prompts_dir)

    def _load(self, prompts_dir: Path) -> None:
        if not prompts_dir.is_dir():
            raise FileNotFoundError(f"prompts dir not found: {prompts_dir}")

        for path in sorted(prompts_dir.glob("*.txt")):
            body = path.read_text(encoding="utf-8")
            version = hashlib.sha256(body.encode("utf-8")).hexdigest()[:12]
            name = path.stem
            self._prompts[name] = Prompt(name=name, body=body, version=version)
            log.info("loaded prompt %s @ %s", name, version)

        if not self._prompts:
            log.warning("prompt registry is empty — %s had no *.txt files", prompts_dir)

    def get(self, name: str) -> Prompt:
        """Return the named prompt. Raises PromptNotFound if missing."""
        try:
            return self._prompts[name]
        except KeyError as exc:
            raise PromptNotFound(name) from exc

    def all(self) -> list[Prompt]:
        """All loaded prompts (used at startup to upsert into Postgres)."""
        return list(self._prompts.values())

    def __len__(self) -> int:
        return len(self._prompts)
