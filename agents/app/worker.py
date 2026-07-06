import asyncio
import json
import logging
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from pydantic import ValidationError

from .agents._context import AgentContext
from .agents._registry import AGENTS
from .embedding.base import EmbeddingClient
from .llm.base import LLMClient
from .llm.factory import make_llm_client
from .models import AgentResult, AgentTask
from .prompt_registry import PromptRegistry
from .settings import Settings

log = logging.getLogger(__name__)


class KafkaWorker:
    """Consumes agent.tasks, routes to the appropriate agent via AGENTS dict."""

    def __init__(self, settings: Settings) -> None:
        self.s = settings
        self._llm: LLMClient = make_llm_client(settings)
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._task: asyncio.Task[None] | None = None
        self.prompt_registry: PromptRegistry | None = None
        self.embedder: EmbeddingClient | None = None

    async def start(self) -> None:
        self._consumer = AIOKafkaConsumer(
            self.s.topic_agent_tasks,
            bootstrap_servers=self.s.kafka_bootstrap,
            group_id=self.s.kafka_group_id,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            max_poll_interval_ms=600_000,   # 10 min — headroom for slow NIM + synthesizer
            max_poll_records=1,             # one task at a time; no batch surprises
            session_timeout_ms=45_000,
            heartbeat_interval_ms=15_000,
        )
        self._producer = AIOKafkaProducer(bootstrap_servers=self.s.kafka_bootstrap)
        await self._consumer.start()
        await self._producer.start()
        self._task = asyncio.create_task(self._run(), name="kafka-worker")
        log.info("KafkaWorker started")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()
        log.info("KafkaWorker stopped")

    async def _run(self) -> None:
        """Outer supervisor: retries _consume_loop on Kafka-level crashes with backoff."""
        delay = 1.0
        while True:
            try:
                await self._consume_loop()
                return
            except asyncio.CancelledError:
                return
            except Exception:
                log.exception("worker loop crashed — restarting in %.0fs", delay)
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30.0)

    async def _consume_loop(self) -> None:
        assert self._consumer is not None
        async for msg in self._consumer:
            try:
                await self._handle(msg.value)
            except Exception:
                log.exception("_handle failed — skipping commit, message will redeliver")
                continue
            await self._consumer.commit()

    async def _handle(self, raw: bytes) -> None:
        try:
            task = AgentTask.model_validate_json(raw)
        except (ValidationError, json.JSONDecodeError) as e:
            await self._to_dlq(raw, reason=f"parse_error: {e}")
            return

        if self.prompt_registry is None:
            raise RuntimeError("worker has no prompt_registry — lifespan wiring is broken")

        ctx = AgentContext(llm=self._llm, prompts=self.prompt_registry, embedder=self.embedder)
        agent_fn = AGENTS.get(task.agent_name)

        if agent_fn is None:
            result = AgentResult(
                incident_id=task.incident_id,
                agent_name=task.agent_name,
                output={"error": f"unknown agent: {task.agent_name}"},
                status="error",
            )
        else:
            try:
                result = await agent_fn(task, ctx)
            except Exception as exc:
                log.exception("agent %s failed for incident %s", task.agent_name, task.incident_id)
                result = AgentResult(
                    incident_id=task.incident_id,
                    agent_name=task.agent_name,
                    output={"error": str(exc)},
                    status="error",
                )

        assert self._producer is not None
        await self._producer.send_and_wait(
            self.s.topic_agent_results,
            key=str(task.incident_id).encode(),
            value=result.model_dump_json(by_alias=True).encode(),
        )

    async def _to_dlq(self, raw: bytes, reason: str) -> None:
        assert self._producer is not None
        envelope: dict[str, Any] = {"reason": reason, "payload_b64": raw.hex()}
        await self._producer.send_and_wait(
            self.s.topic_agent_tasks_dlq,
            value=json.dumps(envelope).encode(),
        )
        log.warning("message sent to DLQ: %s", reason)
