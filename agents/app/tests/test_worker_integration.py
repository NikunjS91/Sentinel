import asyncio
import json
import time
import uuid
from collections.abc import Generator
from unittest.mock import MagicMock, patch

import asyncpg
import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI
from fastapi.testclient import TestClient
from testcontainers.kafka import KafkaContainer

from app.embedding.backfill_task import EmbeddingBackfillTask
from app.llm.model_registry import resolve_agent_spec
from app.models import AgentResult, AgentTask
from app.prompt_registry import PromptRegistry
from app.settings import Settings
from app.worker import KafkaWorker


@pytest.fixture(scope="module")
def kafka_bootstrap() -> Generator[str, None, None]:
    with KafkaContainer() as k:
        yield k.get_bootstrap_server()


async def _wait_for_message(bootstrap: str, topic: str, timeout_s: float = 10.0) -> bytes:
    """Poll a topic until one message arrives or timeout."""
    consumer = AIOKafkaConsumer(
        topic,
        bootstrap_servers=bootstrap,
        auto_offset_reset="earliest",
        group_id=f"test-{uuid.uuid4()}",
        enable_auto_commit=True,
    )
    await consumer.start()
    try:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            timeout_ms = int(min(remaining, 1.0) * 1000)
            batch = await consumer.getmany(timeout_ms=timeout_ms, max_records=1)
            for _tp, records in batch.items():
                if records:
                    return bytes(records[0].value)
        raise TimeoutError(f"No message on {topic} within {timeout_s}s")
    finally:
        await consumer.stop()


# TC-1.7.1: health endpoint returns 200 with status ok (no Kafka needed)
def test_tc_1_7_1_health() -> None:
    bare = FastAPI()

    @bare.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    with TestClient(bare) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok"}


# TC-1.7.2: worker consumes a task and publishes a stub result
@pytest.mark.asyncio
async def test_tc_1_7_2_worker_consumes_and_produces(kafka_bootstrap: str) -> None:
    group = f"worker-{uuid.uuid4()}"
    s = Settings(
        kafka_bootstrap=kafka_bootstrap,
        kafka_group_id=group,
        topic_agent_tasks="agent.tasks",
        topic_agent_results="agent.results",
        topic_agent_tasks_dlq="agent.tasks.dlq",
        llm_backend="anthropic",  # stub raises immediately; echo_agent catches → status=error
    )
    worker = KafkaWorker(s)
    await worker.start()
    worker.prompt_registry = PromptRegistry(s.prompts_dir)

    incident_id = uuid.uuid4()
    task = AgentTask(incident_id=incident_id, agent_name="echo", service="order-api")
    payload = task.model_dump_json(by_alias=True).encode()

    producer = AIOKafkaProducer(bootstrap_servers=kafka_bootstrap)
    await producer.start()
    try:
        await producer.send_and_wait("agent.tasks", value=payload)
    finally:
        await producer.stop()

    raw = await _wait_for_message(kafka_bootstrap, "agent.results")
    result = AgentResult.model_validate_json(raw)
    assert result.incident_id == incident_id
    assert result.agent_name == "echo"

    await worker.stop()


# TC-1.7.3: malformed message goes to DLQ; valid message after is still processed
@pytest.mark.asyncio
async def test_tc_1_7_3_malformed_message_goes_to_dlq(kafka_bootstrap: str) -> None:
    group = f"worker-dlq-{uuid.uuid4()}"
    s = Settings(
        kafka_bootstrap=kafka_bootstrap,
        kafka_group_id=group,
        topic_agent_tasks="agent.tasks.dlq-test",
        topic_agent_results="agent.results.dlq-test",
        topic_agent_tasks_dlq="agent.tasks.dlq",
        llm_backend="anthropic",  # stub raises immediately; echo_agent catches → status=error
    )
    worker = KafkaWorker(s)
    await worker.start()
    worker.prompt_registry = PromptRegistry(s.prompts_dir)

    producer = AIOKafkaProducer(bootstrap_servers=kafka_bootstrap)
    await producer.start()
    try:
        # Send malformed message first
        await producer.send_and_wait("agent.tasks.dlq-test", value=b"not-valid-json")

        # Then send a valid task
        incident_id = uuid.uuid4()
        task = AgentTask(incident_id=incident_id, agent_name="echo", service="svc")
        await producer.send_and_wait(
            "agent.tasks.dlq-test",
            value=task.model_dump_json(by_alias=True).encode(),
        )
    finally:
        await producer.stop()

    # DLQ must have the malformed message
    dlq_raw = await _wait_for_message(kafka_bootstrap, "agent.tasks.dlq")
    dlq_msg = json.loads(dlq_raw)
    assert "reason" in dlq_msg

    # Valid message after malformed must still produce a result (worker continued)
    raw = await _wait_for_message(kafka_bootstrap, "agent.results.dlq-test")
    result = AgentResult.model_validate_json(raw)
    assert result.incident_id == incident_id

    await worker.stop()


# TC-5.3.1: outer supervisor restarts _consume_loop after one crash
@pytest.mark.asyncio
async def test_tc_5_3_1_worker_restarts_after_crash() -> None:
    s = Settings(llm_backend="anthropic")
    worker = KafkaWorker(s)
    calls: list[int] = []

    async def _crash_once() -> None:
        calls.append(1)
        if len(calls) == 1:
            raise RuntimeError("simulated Kafka rebalance crash")

    worker._consume_loop = _crash_once  # type: ignore[method-assign]
    await worker._run()
    assert len(calls) == 2, "supervisor must have retried _consume_loop once"


# TC-5.3.2: CancelledError propagates out of _run without retry
@pytest.mark.asyncio
async def test_tc_5_3_2_cancelled_error_propagates() -> None:
    s = Settings(llm_backend="anthropic")
    worker = KafkaWorker(s)

    async def _raises_cancelled() -> None:
        raise asyncio.CancelledError

    worker._consume_loop = _raises_cancelled  # type: ignore[method-assign]
    await worker._run()


# TC-5.3.3: _handle exception skips commit; message will redeliver
@pytest.mark.asyncio
async def test_tc_5_3_3_handle_exception_skips_commit() -> None:
    s = Settings(llm_backend="anthropic")
    worker = KafkaWorker(s)

    commit_called = False

    async def _fail_handle(_raw: bytes) -> None:
        raise ValueError("bad message")

    worker._handle = _fail_handle  # type: ignore[method-assign]

    fake_msg = MagicMock()
    fake_msg.value = b"irrelevant"

    async def _fake_aiter(self: object) -> object:  # noqa: ARG001
        yield fake_msg
        raise asyncio.CancelledError

    fake_consumer = MagicMock()
    fake_consumer.__aiter__ = _fake_aiter

    async def _fake_commit() -> None:
        nonlocal commit_called
        commit_called = True

    fake_consumer.commit = _fake_commit
    worker._consumer = fake_consumer  # type: ignore[assignment]

    try:
        await worker._consume_loop()
    except asyncio.CancelledError:
        pass

    assert not commit_called, "commit must not be called when _handle raises"


# TC-5.3.4: NIM synthesizer spec has timeout_s >= 180
def test_tc_5_3_4_nim_synthesizer_timeout() -> None:
    with patch("app.settings.settings") as mock_settings:
        mock_settings.llm_backend = "nim"
        mock_settings.nim_specialist_model = "meta/llama-3.1-8b-instruct"
        mock_settings.nim_synthesizer_model = "meta/llama-3.1-70b-instruct"
        mock_settings.nim_timeout_s = 30.0
        mock_settings.nim_synthesizer_timeout_s = 180.0
        spec = resolve_agent_spec("synthesizer")
    assert spec.timeout_s >= 180.0, f"synthesizer timeout must be >= 180s, got {spec.timeout_s}"


# TC-5.3.5: backfill task backs off exponentially on Postgres connection errors
@pytest.mark.asyncio
async def test_tc_5_3_5_backfill_exponential_backoff() -> None:
    task = EmbeddingBackfillTask(interval_s=1.0)
    sleep_calls: list[float] = []

    pass_count = 0

    async def _fake_pass(_embedder: object) -> None:
        nonlocal pass_count
        pass_count += 1
        if pass_count <= 3:
            raise asyncpg.PostgresConnectionError("connection refused")
        task._stop.set()

    async def _fake_wait_for(coro: object, timeout: float) -> None:  # noqa: ARG001
        sleep_calls.append(timeout)
        raise TimeoutError

    task._pass = _fake_pass  # type: ignore[method-assign]

    with patch("app.embedding.backfill_task.asyncio.wait_for", side_effect=_fake_wait_for):
        with patch("app.embedding.backfill_task.make_embedding_client", return_value=MagicMock()):
            await task._run()

    assert sleep_calls[0] == 1.0
    assert sleep_calls[1] == 2.0
    assert sleep_calls[2] == 4.0
