import uuid

import pytest

from app.agents.echo import echo_agent
from app.llm.base import LLMResponse
from app.llm.factory import make_llm_client
from app.llm.ollama import OllamaClient
from app.models import AgentTask
from app.settings import Settings


class _FakeLLM:
    async def complete(self, prompt: str) -> LLMResponse:
        return LLMResponse(text="Acknowledged.", tokens=12, latency_ms=5)


class _ErrorLLM:
    async def complete(self, prompt: str) -> LLMResponse:
        raise RuntimeError("LLM unavailable")


# TC-1.8.1: factory selects correct backend
def test_tc_1_8_1_factory_ollama() -> None:
    s = Settings(llm_backend="ollama", kafka_bootstrap="localhost:9092")
    client = make_llm_client(s)
    assert isinstance(client, OllamaClient)


def test_tc_1_8_1_factory_unknown_raises() -> None:
    s = Settings(llm_backend="unknown", kafka_bootstrap="localhost:9092")
    with pytest.raises(ValueError, match="unknown LLM_BACKEND"):
        make_llm_client(s)


# TC-1.8.2: echo agent returns a valid result
async def test_tc_1_8_2_echo_agent_ok() -> None:
    task = AgentTask(incident_id=uuid.uuid4(), agent_name="echo", service="order-api")
    result = await echo_agent(task, _FakeLLM())
    assert result.status == "ok"
    assert result.output["message"] == "Acknowledged."
    assert result.tokens_used == 12
    assert result.latency_ms == 5
    assert result.incident_id == task.incident_id
    assert result.agent_name == "echo"


# TC-1.8.4: echo agent survives LLM failure
async def test_tc_1_8_4_echo_agent_llm_error() -> None:
    task = AgentTask(incident_id=uuid.uuid4(), agent_name="echo", service="order-api")
    result = await echo_agent(task, _ErrorLLM())
    assert result.status == "error"
    assert "error" in result.output
    assert result.incident_id == task.incident_id
