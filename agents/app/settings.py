from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration, loaded from environment / .env."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

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
    incident_token_budget: int = 20_000


settings = Settings()
