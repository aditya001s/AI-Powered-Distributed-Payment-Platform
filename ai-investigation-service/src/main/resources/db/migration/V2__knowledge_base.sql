-- =============================================================================
-- ai-investigation-service : the fraud-policy knowledge base
--
-- Two tables and one index, and the index is the interesting part.
--
-- ---- Why documents AND chunks ----
--
-- Retrieval works on chunks, but a citation has to point at something a human can
-- read and verify. A chunk alone is a fragment with no title and no version; a
-- document alone is too large to embed usefully and would drown the specific
-- paragraph that matters in a page of unrelated policy.
--
-- So a document is the unit of authorship and citation, and a chunk is the unit
-- of retrieval. Every chunk knows its document, so a retrieved fragment always
-- resolves to a citable source.
--
-- ---- Why the embedding is versioned ----
--
-- A vector is only comparable to vectors produced by the same embedder. Change
-- the embedder and every stored vector becomes noise -- retrieval keeps working,
-- keeps returning results, and the results are meaningless. Nothing errors.
--
-- So each chunk records which embedder produced its vector, and retrieval filters
-- on the embedder currently in use. A re-embed is then a visible migration rather
-- than a silent corruption: after an embedder change, retrieval returns nothing
-- until the corpus is rebuilt, which is loud and correct.
--
-- Platform-wide conventions:
--   * TIMESTAMPTZ, never TIMESTAMP.
--   * Public identifiers are opaque and stable, so a citation survives a reseed.
--   * Flyway owns the schema; Hibernate runs with ddl-auto=validate.
-- =============================================================================

-- The vector extension is created by infrastructure/docker/postgres/00-init-service-databases.sh,
-- because CREATE EXTENSION needs superuser and the application role does not have
-- it. Asserted here rather than assumed: without it the CREATE TABLE below fails
-- with "type vector does not exist", which is a far less useful message than this.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION
            'The pgvector extension is not installed in this database. It is created by '
            'infrastructure/docker/postgres/00-init-service-databases.sh, which needs superuser. '
            'Run: CREATE EXTENSION vector;';
    END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- policy_documents
--
-- The fraud policies an investigation is allowed to reason from. Deliberately a
-- closed corpus: this service must not be able to cite something nobody wrote.
-- -----------------------------------------------------------------------------
CREATE TABLE policy_documents (
    -- Stable and human-meaningful, e.g. 'POLICY-BUST-OUT'. Chosen rather than
    -- generated because it appears in a citation an analyst reads and a dispute
    -- quotes, and a random id would make every reseed produce different-looking
    -- evidence for the same policy.
    document_id     VARCHAR(64)  PRIMARY KEY,

    title           VARCHAR(200) NOT NULL,

    -- What this policy is about, as a controlled category rather than free text,
    -- so retrieval can be filtered and coverage can be measured.
    category        VARCHAR(48)  NOT NULL,

    -- The rule ids this policy explains, comma-separated. The bridge between
    -- fraud-service's machine vocabulary and the human policy: an investigation
    -- for RULE_BUST_OUT_PATTERN can find the document that explains it directly,
    -- without depending on the embedder having learned the association.
    related_rule_ids VARCHAR(512),

    -- The authored text. Kept whole alongside the chunks so a citation can be
    -- expanded to its full context, and so a re-chunk needs no re-authoring.
    body            TEXT         NOT NULL,

    -- Bumped by whoever edits the policy. A citation records the version it read,
    -- so an investigation written against version 2 does not silently appear to
    -- have cited version 3.
    version         INTEGER      NOT NULL DEFAULT 1,

    source          VARCHAR(200) NOT NULL,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_policy_body_present   CHECK (length(trim(body)) > 0),
    CONSTRAINT ck_policy_title_present  CHECK (length(trim(title)) > 0),
    CONSTRAINT ck_policy_version_positive CHECK (version >= 1)
);

COMMENT ON TABLE policy_documents IS
    'Closed corpus of fraud policies. An investigation may cite nothing outside this table.';
COMMENT ON COLUMN policy_documents.related_rule_ids IS
    'Bridges fraud-service rule ids to human policy, so retrieval does not depend on the embedder learning the association.';

CREATE INDEX idx_policy_documents_category ON policy_documents (category);

-- -----------------------------------------------------------------------------
-- policy_chunks
--
-- The retrieval unit. One row per passage, with the vector that represents it.
-- -----------------------------------------------------------------------------
CREATE TABLE policy_chunks (
    chunk_id        VARCHAR(80)  PRIMARY KEY,

    document_id     VARCHAR(64)  NOT NULL,

    -- Position within the document, so a retrieved passage can be shown in order
    -- and a citation can name where in the policy it came from.
    ordinal         INTEGER      NOT NULL,

    content         TEXT         NOT NULL,

    -- Token-ish length, recorded at index time. Used to keep the assembled prompt
    -- inside its budget without re-measuring every chunk on every request.
    content_tokens  INTEGER      NOT NULL,

    -- Which embedder produced `embedding`. Retrieval filters on this: a vector
    -- from a different embedder is not comparable, and comparing it anyway would
    -- return confident nonsense.
    embedder        VARCHAR(64)  NOT NULL,

    -- 384 dimensions, matching the conventional sentence-embedding size so the
    -- default local embedder and a hosted one are interchangeable without a
    -- schema change.
    embedding       vector(384)  NOT NULL,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id)
        REFERENCES policy_documents (document_id) ON DELETE CASCADE,

    -- One chunk per position per document per embedder. Re-seeding with the same
    -- embedder is then an upsert rather than a duplicate, and two embedders can
    -- coexist during a migration.
    CONSTRAINT uq_chunk_position UNIQUE (document_id, ordinal, embedder),

    CONSTRAINT ck_chunk_content_present CHECK (length(trim(content)) > 0),
    CONSTRAINT ck_chunk_ordinal_non_negative CHECK (ordinal >= 0),
    CONSTRAINT ck_chunk_tokens_positive CHECK (content_tokens > 0)
);

COMMENT ON TABLE policy_chunks IS
    'Retrieval units with their embeddings. Filtered by embedder, because vectors from different embedders are not comparable.';

CREATE INDEX idx_policy_chunks_document ON policy_chunks (document_id, ordinal);
CREATE INDEX idx_policy_chunks_embedder ON policy_chunks (embedder);

-- -----------------------------------------------------------------------------
-- The vector index.
--
-- HNSW rather than IVFFlat: IVFFlat has to be built AFTER the data is loaded and
-- must be rebuilt as the corpus grows, and an IVFFlat index built on an empty
-- table silently returns nothing useful. This corpus is seeded at startup, so an
-- index that has to be created after loading would either be missing on a fresh
-- database or need application code to issue DDL. HNSW is correct when empty and
-- stays correct as rows are added.
--
-- vector_cosine_ops, matching the retrieval operator (<=>). The operator class
-- and the query operator MUST agree: a cosine query against an L2 index does not
-- error, it just stops using the index and silently degrades to a sequential
-- scan over the whole corpus.
--
-- Embeddings are L2-normalised by the embedder, so cosine and inner product rank
-- identically here. Cosine is kept because it stays correct if a future embedder
-- forgets to normalise.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_policy_chunks_embedding
    ON policy_chunks USING hnsw (embedding vector_cosine_ops);

COMMENT ON INDEX idx_policy_chunks_embedding IS
    'HNSW/cosine. The operator class must match the query operator (<=>), or the query silently degrades to a sequential scan.';
