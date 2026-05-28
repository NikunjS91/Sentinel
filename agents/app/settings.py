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

    # LLM (Day 8) — declared now so .env stays one file
    llm_backend: str = "ollama"
    ollama_host: str = "http://localhost:11434"
    ollama_model: str = "mistral"
    incident_token_budget: int = 20_000


settings = Settings()
