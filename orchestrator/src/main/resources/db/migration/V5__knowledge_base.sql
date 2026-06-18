-- V5 — Knowledge base schema for Sprint-4 agents.
-- pgvector is required; install it as part of this migration.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS knowledge_base;

-- 1. kb_documents: text chunks (past incident summaries, FAQ entries,
--    domain docs) with embeddings for vector similarity search.
CREATE TABLE knowledge_base.kb_documents (
    id              UUID PRIMARY KEY,
    source_type     TEXT NOT NULL,           -- 'past_incident' | 'faq' | 'domain_doc'
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    embedding       vector(384),              -- all-MiniLM-L6-v2 produces 384-dim
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- IVFFlat index for fast cosine-similarity search.
-- lists = 100 is a fine starting point for a few thousand rows.
CREATE INDEX idx_kb_documents_embedding
    ON knowledge_base.kb_documents
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE INDEX idx_kb_documents_source_type
    ON knowledge_base.kb_documents (source_type);


-- 2. kb_links: service-topology graph as adjacency rows.
CREATE TABLE knowledge_base.kb_links (
    id              UUID PRIMARY KEY,
    from_service    TEXT NOT NULL,
    to_service      TEXT NOT NULL,
    relationship    TEXT NOT NULL,           -- 'calls' | 'depends_on' | 'reads_from' | ...
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (from_service, to_service, relationship)
);

CREATE INDEX idx_kb_links_from ON knowledge_base.kb_links (from_service);
CREATE INDEX idx_kb_links_to   ON knowledge_base.kb_links (to_service);


-- 3. kb_runbooks: operational documents, full-text searchable.
CREATE TABLE knowledge_base.kb_runbooks (
    id              UUID PRIMARY KEY,
    title           TEXT NOT NULL,
    summary         TEXT NOT NULL,           -- short description
    body            TEXT NOT NULL,            -- full markdown
    tags            TEXT[] NOT NULL DEFAULT '{}',
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- GIN index over the `body` + `title` for full-text search.
CREATE INDEX idx_kb_runbooks_fts
    ON knowledge_base.kb_runbooks
    USING gin (to_tsvector('english', title || ' ' || body));

CREATE INDEX idx_kb_runbooks_tags
    ON knowledge_base.kb_runbooks
    USING gin (tags);


COMMENT ON SCHEMA knowledge_base IS
  'Reference data agents consult during triage. Lifecycle-separated '
  'from the incidents schema (event data).';
