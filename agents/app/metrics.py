from prometheus_client import Counter, Histogram

AGENT_TIMEOUTS = Counter(
    "sentinel_agent_timeouts_total",
    "LLM call timeouts per agent",
    ["agent_name"],
)

AGENT_LLM_LATENCY = Histogram(
    "sentinel_agent_llm_latency_seconds",
    "LLM call latency per agent (wall-clock, including retries)",
    ["agent_name"],
    buckets=(1, 5, 10, 30, 60, 120, 300, 600),
)
