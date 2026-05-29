import json
import time
import uuid
from collections.abc import Generator

import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from fastapi import FastAPI
from fastapi.testclient import TestClient
from testcontainers.kafka import KafkaContainer

from app.models import AgentResult, AgentTask
from app.settings import Settings
from app.worker import KafkaWorker


@pytest.fixture(scope="module")
def kafka_bootstrap() -> Generator[str, None, None]:
    with KafkaContainer() as k:
        yield k.get_bootstrap_server()


async def _wait_for_message(
    bootstrap: str, topic: str, timeout_s: float = 10.0
) -> bytes:
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
