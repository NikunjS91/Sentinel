from ..llm.base import LLMClient
from ..models import AgentResult, AgentTask


async def echo_agent(task: AgentTask, llm: LLMClient) -> AgentResult:
    """Sprint 1 agent: acknowledge the incident in one sentence via the LLM.
    Proves the LLM path end to end. Real diagnostic agents arrive in Sprint 2."""
    prompt = (
        "You are an incident assistant. In one short sentence, acknowledge "
        f"that an incident was received for service '{task.service}'. "
        "Do not speculate about causes."
    )
    try:
        resp = await llm.complete(prompt)
        return AgentResult(
            incident_id=task.incident_id,
            agent_name=task.agent_name,
            output={"message": resp.text},
            tokens_used=resp.tokens,
            latency_ms=resp.latency_ms,
            status="ok",
        )
    except Exception as e:
        return AgentResult(
            incident_id=task.incident_id,
            agent_name=task.agent_name,
            output={"error": str(e)},
            status="error",
        )
