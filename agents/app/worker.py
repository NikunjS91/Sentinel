import asyncio
import json
import logging
from typing import Any

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from pydantic import ValidationError

from .models import AgentResult, AgentTask
from .settings import Settings

log = logging.getLogger(__name__)


class KafkaWorker:
    """Consumes agent.tasks, produces stub agent.results (Day 7).
    Day 8 replaces the stub with a real LLM call."""

    def __init__(self, settings: Settings) -> None:
        self.s = settings
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        self._consumer = AIOKafkaConsumer(
            self.s.topic_agent_tasks,
            bootstrap_servers=self.s.kafka_bootstrap,
            group_id=self.s.kafka_group_id,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
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
        assert self._consumer is not None
        try:
            async for msg in self._consumer:
                await self._handle(msg.value)
                await self._consumer.commit()
        except asyncio.CancelledError:
            return
        except Exception:
            log.exception("worker loop crashed")
            raise

    async def _handle(self, raw: bytes) -> None:
        try:
            task = AgentTask.model_validate_json(raw)
        except (ValidationError, json.JSONDecodeError) as e:
            await self._to_dlq(raw, reason=f"parse_error: {e}")
            return

        result = self._stub_result(task)

        assert self._producer is not None
        await self._producer.send_and_wait(
            self.s.topic_agent_results,
            key=str(task.incident_id).encode(),
            value=result.model_dump_json(by_alias=True).encode(),
        )

    def _stub_result(self, task: AgentTask) -> AgentResult:
        """Sprint 1 stub. Replaced Day 8 with a real LLM call."""
        return AgentResult(
            incident_id=task.incident_id,
            agent_name=task.agent_name,
            output={"message": f"stub ack for {task.agent_name}"},
            tokens_used=0,
            latency_ms=0,
            status="ok",
        )

    async def _to_dlq(self, raw: bytes, reason: str) -> None:
        assert self._producer is not None
        envelope: dict[str, Any] = {"reason": reason, "payload_b64": raw.hex()}
        await self._producer.send_and_wait(
            self.s.topic_agent_tasks_dlq,
            value=json.dumps(envelope).encode(),
        )
        log.warning("message sent to DLQ: %s", reason)
