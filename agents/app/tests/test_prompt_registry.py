import asyncpg
import pytest

from app.db import upsert_prompt_versions
from app.prompt_registry import Prompt, PromptNotFound, PromptRegistry
from app.settings import settings


async def _postgres_reachable() -> bool:
    try:
        conn: asyncpg.Connection = await asyncpg.connect(settings.database_url, timeout=2)
        await conn.close()
        return True
    except Exception:
        return False


# TC-2.7.1: prompts load with stable 12-char version hashes
def test_tc_2_7_1_prompts_load_and_version() -> None:
    registry = PromptRegistry(settings.prompts_dir)

    assert len(registry) == 6
    for prompt in registry.all():
        assert isinstance(prompt, Prompt)
        assert len(prompt.version) == 12
        assert prompt.name in (
            "log_analyzer",
            "metrics_agent",
            "synthesizer",
            "history",
            "topology",
            "runbook",
        )


# TC-2.7.2: same content yields same version across registry instances
def test_tc_2_7_2_stable_hash() -> None:
    r1 = PromptRegistry(settings.prompts_dir)
    r2 = PromptRegistry(settings.prompts_dir)

    for name in ("log_analyzer", "metrics_agent", "synthesizer"):
        assert r1.get(name).version == r2.get(name).version


# TC-2.7.3: different content yields different version
def test_tc_2_7_3_changed_content_changes_version(tmp_path: pytest.TempdirFactory) -> None:
    prompt_file = tmp_path / "test_prompt.txt"  # type: ignore[operator]
    prompt_file.write_text("original content", encoding="utf-8")

    r1 = PromptRegistry(tmp_path)  # type: ignore[arg-type]
    v1 = r1.get("test_prompt").version

    prompt_file.write_text("modified content", encoding="utf-8")
    r2 = PromptRegistry(tmp_path)  # type: ignore[arg-type]
    v2 = r2.get("test_prompt").version

    assert v1 != v2


# TC-2.7.4: missing prompt raises PromptNotFound (a KeyError subclass)
def test_tc_2_7_4_missing_prompt_raises(tmp_path: pytest.TempdirFactory) -> None:
    (tmp_path / "real.txt").write_text("content", encoding="utf-8")  # type: ignore[operator]

    registry = PromptRegistry(tmp_path)  # type: ignore[arg-type]

    with pytest.raises(PromptNotFound):
        registry.get("nonexistent")

    with pytest.raises(KeyError):
        registry.get("nonexistent")


# TC-2.7.5: Postgres upsert is idempotent (skipped when Postgres absent)
@pytest.mark.asyncio
@pytest.mark.skipif(
    not __import__("asyncio").run(_postgres_reachable()),
    reason="Postgres not available",
)
async def test_tc_2_7_5_upsert_idempotent() -> None:
    import asyncpg as apg

    registry = PromptRegistry(settings.prompts_dir)

    await upsert_prompt_versions(registry)
    await upsert_prompt_versions(registry)

    conn: apg.Connection = await apg.connect(settings.database_url)
    try:
        rows = await conn.fetch(
            "SELECT version FROM prompt_versions WHERE agent_name = ANY($1)",
            [p.name for p in registry.all()],
        )
    finally:
        await conn.close()

    versions_in_db = {r["version"] for r in rows}
    for prompt in registry.all():
        assert prompt.version in versions_in_db
