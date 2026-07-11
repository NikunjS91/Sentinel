from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

_AGENTS_DIR = Path(__file__).resolve().parents[1]
_REPO_ROOT = _AGENTS_DIR.parent


class Settings(BaseSettings):
    """Runtime configuration, loaded from environment / .env.

    env_file paths are anchored to this file, not the CWD — a bare ".env"
    silently loads nothing when the service is started from agents/ while
    the canonical .env lives at the repo root, flipping llm_backend back
    to its ollama default. Later files override earlier ones; real
    environment variables override both."""

    model_config = SettingsConfigDict(
        env_file=(_REPO_ROOT / ".env", _AGENTS_DIR / ".env"),
        extra="ignore",
    )

    # Kafka
    kafka_bootstrap: str = "localhost:9092"
    kafka_group_id: str = "agents"
    topic_agent_tasks: str = "agent.tasks"
    topic_agent_results: str = "agent.results"
    topic_agent_tasks_dlq: str = "agent.tasks.dlq"

    # LLM (Day 8)
    llm_backend: str = "ollama"
    ollama_host: str = "http://localhost:11434"
    ollama_model: str = "qwen3:14b"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-opus-4-7"
    groq_api_key: str = ""
    groq_model: str = "llama3-70b-8192"
    # NVIDIA NIM (OpenAI-compatible cloud inference)
    nim_api_key: str = ""
    nim_base_url: str = "https://integrate.api.nvidia.com/v1"
    nim_specialist_model: str = "meta/llama-3.1-8b-instruct"
    nim_synthesizer_model: str = "meta/llama-3.1-70b-instruct"
    nim_timeout_s: float = 30.0
    nim_synthesizer_timeout_s: float = 180.0
    incident_token_budget: int = 20_000

    # Tools (Day 14+)
    loki_url: str = "http://localhost:3100"
    tool_mode: str = "live"  # "live" | "fixture" — governs both log and metric tools
    log_sample_limit: int = 50
    log_query_timeout_s: float = 5.0

    # Metrics tool (Day 15)
    prometheus_url: str = "http://localhost:9090"
    metric_query_timeout_s: float = 5.0

    # Postgres (Day 16)
    database_url: str = "postgresql://sentinel:sentinel@localhost:5432/sentinel"

    # Prompts (Day 16)
    prompts_dir: Path = Path(__file__).parent / "prompts"

    # Embeddings (Day 27)
    embedding_backend: str = "fixture"

    # Orchestrator base URL for inter-service HTTP calls (Day 28)
    orchestrator_url: str = "http://localhost:8080"


settings = Settings()
