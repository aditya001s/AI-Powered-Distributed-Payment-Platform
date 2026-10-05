-- =============================================================================
-- ai-investigation-service : investigations, citations and human decisions
--
-- ---- The authority boundary, in the schema ----
--
-- There is no column here that any consumer executes. `recommendation` is a
-- VARCHAR from a CHECK-constrained vocabulary, and every value in that vocabulary
-- is phrased as advice to a person rather than as a command to a system.
--
-- The boundary is also enforced by absence: this service has no wallet client, no
-- ledger client, and its configuration disables service-to-service authentication
-- entirely, so it holds no credential that any internal write API would accept.
-- A schema comment is a hope; not having the credentials is a guarantee.
--
-- ---- Why an investigation is immutable ----
--
-- An investigation is a statement about a payment at a moment in time, generated
-- from a specific evidence pack by a specific model build. Editing it would
-- destroy the thing that makes it worth anything: that it can be shown to a
-- regulator alongside the inputs that produced it.
--
-- A revised view of the same payment is a NEW investigation, which is why
-- payment_id is not unique -- see the partial unique index below for how "one
-- automatic investigation per payment" is enforced without forbidding a re-run.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- investigations
-- -----------------------------------------------------------------------------
CREATE TABLE investigations (
    id                      BIGSERIAL    PRIMARY KEY,

    investigation_id        VARCHAR(48)  NOT NULL,

    payment_id              VARCHAR(48)  NOT NULL,
    transaction_id          VARCHAR(48),
    user_id                 VARCHAR(40)  NOT NULL,

    amount_minor            BIGINT       NOT NULL,
    currency                VARCHAR(3)   NOT NULL,

    -- ---- what fraud-service reported, retained verbatim ----
    -- Copied rather than fetched. The evidence an investigation reasoned from must
    -- be exactly what it was given, and re-reading it from fraud-service later
    -- would show whatever that service says today.
    risk_level              VARCHAR(16)  NOT NULL,
    fraud_probability       DOUBLE PRECISION NOT NULL,
    rule_hits               VARCHAR(512),
    model_reasons           TEXT,
    model_version           VARCHAR(64),
    -- The exact feature vector the score came from. JSONB so a specific feature can
    -- be queried across investigations when reviewing a pattern.
    feature_snapshot        JSONB,

    -- ---- the generated report ----
    narrative               TEXT         NOT NULL,
    findings                TEXT,

    -- Advice to a human, never an instruction to a system. Constrained to a fixed
    -- vocabulary so a generator cannot invent an action -- and so a successful
    -- prompt injection cannot produce one either.
    recommendation          VARCHAR(48)  NOT NULL,
    recommendation_rationale TEXT        NOT NULL,

    confidence              DOUBLE PRECISION NOT NULL,

    -- ---- provenance ----
    -- Which generator wrote this, e.g. 'local-composer/1' or 'openai/gpt-4o-mini'.
    generated_by            VARCHAR(96)  NOT NULL,

    -- TRUE when written by the offline deterministic composer rather than a
    -- language model. Analysts must know which they are reading: the composer
    -- states only what the evidence says, an LLM may also be wrong fluently.
    deterministic_fallback  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Which embedder retrieved the cited policy. Part of reproducing the report:
    -- the same prompt over a different retrieval set is a different investigation.
    embedder                VARCHAR(64),

    -- How many chunks cleared the similarity floor. Zero means the narrative had to
    -- say "no policy matched" rather than reach for one, which is a legitimate
    -- outcome and a signal the knowledge base has drifted from the traffic.
    retrieved_chunks        INTEGER      NOT NULL DEFAULT 0,

    -- Citations the generator produced that were NOT in its retrieved context.
    -- Detectable because the documents put into the prompt are known exactly.
    -- Non-zero means the generator invented evidence.
    hallucinated_citations  INTEGER      NOT NULL DEFAULT 0,

    generation_latency_ms   BIGINT       NOT NULL DEFAULT 0,

    -- TRUE when this was requested by an analyst rather than by a risk verdict.
    -- Kept because an on-demand re-run of a payment that was never automatically
    -- investigated is a different fact from the automatic pass.
    on_demand               BOOLEAN      NOT NULL DEFAULT FALSE,

    requested_by            VARCHAR(40),

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_investigation_id UNIQUE (investigation_id),

    CONSTRAINT ck_investigation_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ck_investigation_probability CHECK (
        fraud_probability >= 0.0 AND fraud_probability <= 1.0),
    CONSTRAINT ck_investigation_confidence CHECK (
        confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT ck_investigation_risk_level CHECK (
        risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),

    -- The permitted vocabulary. Every value is advice; none is a command, and none
    -- names an operation this platform would execute on its own.
    --
    -- Enforced in the database as well as in the aggregate because this is the
    -- field a prompt injection would target. A model that returned
    -- 'RELEASE_IMMEDIATELY' is refused here even if application code somehow let it
    -- through.
    CONSTRAINT ck_investigation_recommendation CHECK (recommendation IN (
        'NO_ACTION_REQUIRED',
        'MONITOR_ACCOUNT',
        'REQUEST_CUSTOMER_VERIFICATION',
        'HOLD_AND_REQUEST_VERIFICATION',
        'ESCALATE_TO_SENIOR_ANALYST',
        'RECOMMEND_REJECT_AND_REVIEW_ACCOUNT',
        'INSUFFICIENT_EVIDENCE')),

    CONSTRAINT ck_investigation_narrative_present CHECK (length(trim(narrative)) > 0),
    CONSTRAINT ck_investigation_rationale_present CHECK (
        length(trim(recommendation_rationale)) > 0),

    CONSTRAINT ck_investigation_chunks_non_negative CHECK (retrieved_chunks >= 0),
    CONSTRAINT ck_investigation_hallucinations_non_negative CHECK (
        hallucinated_citations >= 0),
    CONSTRAINT ck_investigation_latency_non_negative CHECK (generation_latency_ms >= 0),

    -- A deterministic report must not claim a language model wrote it, and an LLM
    -- report must not hide behind the composer's name. Either would misrepresent
    -- how much to trust the narrative.
    CONSTRAINT ck_investigation_fallback_attributed CHECK (
        NOT deterministic_fallback OR generated_by LIKE 'local%'),

    -- An analyst-requested investigation must name the analyst.
    CONSTRAINT ck_investigation_on_demand_attributed CHECK (
        NOT on_demand OR requested_by IS NOT NULL)
);

COMMENT ON TABLE investigations IS
    'Immutable generated reports. Recommendations only: no column here is executed by any consumer.';
COMMENT ON COLUMN investigations.deterministic_fallback IS
    'TRUE when the offline composer wrote it. Analysts must know whether they are reading evidence or prose.';
COMMENT ON COLUMN investigations.hallucinated_citations IS
    'Citations the generator invented. Non-zero means it cited a policy that was never retrieved.';
COMMENT ON CONSTRAINT ck_investigation_recommendation ON investigations IS
    'The field a prompt injection would target. Constrained in the database, not only in code.';

-- One AUTOMATIC investigation per payment. A partial index rather than a plain
-- UNIQUE, so an analyst can legitimately re-run an investigation on demand while a
-- redelivered investigation-requested event still cannot produce a second
-- automatic one -- and a duplicate report would double the LLM cost and give two
-- analysts two different narratives about the same payment.
CREATE UNIQUE INDEX uq_investigation_automatic_per_payment
    ON investigations (payment_id) WHERE NOT on_demand;

CREATE INDEX idx_investigations_payment ON investigations (payment_id, created_at DESC);
CREATE INDEX idx_investigations_user ON investigations (user_id, created_at DESC);
CREATE INDEX idx_investigations_created_at ON investigations (created_at DESC);
CREATE INDEX idx_investigations_recommendation
    ON investigations (recommendation, created_at DESC);

-- Operators watch this: a rising count means the knowledge base no longer covers
-- the traffic, so narratives are being written with no policy behind them.
CREATE INDEX idx_investigations_ungrounded ON investigations (created_at DESC)
    WHERE retrieved_chunks = 0;

-- Expected to be empty. Every row is a report that cited evidence which did not
-- exist.
CREATE INDEX idx_investigations_hallucinated ON investigations (created_at DESC)
    WHERE hallucinated_citations > 0;

-- -----------------------------------------------------------------------------
-- investigation_citations
--
-- Which policy passage supported which finding.
--
-- A separate table rather than a JSON blob on the report, because this is what
-- makes a narrative checkable: an analyst reading a claim needs to open the exact
-- passage behind it, and a reviewer needs to ask "was anything cited that was
-- never retrieved". Both are queries, so they need rows.
-- -----------------------------------------------------------------------------
CREATE TABLE investigation_citations (
    id                BIGSERIAL    PRIMARY KEY,

    investigation_id  VARCHAR(48)  NOT NULL,

    document_id       VARCHAR(64)  NOT NULL,
    chunk_id          VARCHAR(80),

    -- The document version that was read. Without it, a policy edited after the
    -- fact would make the investigation appear to have cited text it never saw.
    document_version  INTEGER      NOT NULL,

    -- Cosine similarity at retrieval time, in [0,1] after the distance is
    -- converted. Retained so a weak citation is visibly weak rather than looking
    -- as authoritative as a strong one.
    similarity        DOUBLE PRECISION NOT NULL,

    -- Rank in the retrieved set, 1 being closest.
    rank              INTEGER      NOT NULL,

    -- TRUE when the generator referenced this document but retrieval never
    -- returned it. Recorded rather than dropped: the fact that a generator
    -- invented a citation is itself evidence about the generator, and deleting it
    -- would hide the only trace.
    hallucinated      BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT fk_citation_investigation FOREIGN KEY (investigation_id)
        REFERENCES investigations (investigation_id) ON DELETE CASCADE,

    -- No FK to policy_documents on purpose. A hallucinated citation names a
    -- document that does not exist, and an FK would make it impossible to record
    -- the very thing worth recording. Real citations are validated in application
    -- code against the retrieved set, which is a stronger check than existence.

    CONSTRAINT uq_citation_per_investigation UNIQUE (investigation_id, document_id, chunk_id),
    CONSTRAINT ck_citation_similarity CHECK (similarity >= -1.0 AND similarity <= 1.0),
    CONSTRAINT ck_citation_rank_positive CHECK (rank >= 1),

    -- A hallucinated citation cannot have a real similarity: nothing was retrieved
    -- to measure it against.
    CONSTRAINT ck_citation_hallucinated_has_no_similarity CHECK (
        NOT hallucinated OR similarity = 0.0)
);

COMMENT ON TABLE investigation_citations IS
    'Which policy passage supported which report. Deliberately has NO foreign key to policy_documents, so a hallucinated citation can be recorded rather than silently dropped.';

CREATE INDEX idx_citations_investigation ON investigation_citations (investigation_id, rank);
CREATE INDEX idx_citations_document ON investigation_citations (document_id);

-- -----------------------------------------------------------------------------
-- investigation_reviews
--
-- What a human decided after reading a recommendation. Append-only.
--
-- This table is the authority boundary made visible. The AI recommends; a person
-- agrees or disagrees, and their agreement is what any action rests on. Storing
-- the human decision here rather than only in fraud-service means the question
-- "was this recommendation followed?" is answerable, which is how you find out
-- whether the AI is any good.
-- -----------------------------------------------------------------------------
CREATE TABLE investigation_reviews (
    id                BIGSERIAL    PRIMARY KEY,

    review_id         VARCHAR(48)  NOT NULL,
    investigation_id  VARCHAR(48)  NOT NULL,
    payment_id        VARCHAR(48)  NOT NULL,

    -- Whether the analyst agreed with the recommendation. The measure of the
    -- model's value: a recommendation nobody follows is worse than none, because
    -- it costs a read and teaches distrust.
    agreed            BOOLEAN      NOT NULL,

    -- What the analyst actually judged. Same closed vocabulary as the
    -- recommendation, so agreement is comparable rather than a free-text guess.
    analyst_assessment VARCHAR(48) NOT NULL,

    -- Mandatory. The only record of why a person overrode or accepted a machine's
    -- reading of a customer's payment.
    notes             VARCHAR(1024) NOT NULL,

    -- From the validated token, never from a request field.
    reviewed_by       VARCHAR(40)  NOT NULL,
    reviewed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_review_id UNIQUE (review_id),
    CONSTRAINT fk_review_investigation FOREIGN KEY (investigation_id)
        REFERENCES investigations (investigation_id) ON DELETE RESTRICT,

    CONSTRAINT ck_review_notes_present CHECK (length(trim(notes)) > 0),
    CONSTRAINT ck_review_assessment CHECK (analyst_assessment IN (
        'NO_ACTION_REQUIRED',
        'MONITOR_ACCOUNT',
        'REQUEST_CUSTOMER_VERIFICATION',
        'HOLD_AND_REQUEST_VERIFICATION',
        'ESCALATE_TO_SENIOR_ANALYST',
        'RECOMMEND_REJECT_AND_REVIEW_ACCOUNT',
        'INSUFFICIENT_EVIDENCE'))
);

COMMENT ON TABLE investigation_reviews IS
    'Append-only human verdicts on AI recommendations. UPDATE and DELETE are refused by trigger.';
COMMENT ON COLUMN investigation_reviews.agreed IS
    'The measure of whether this service is worth running. A recommendation nobody follows teaches distrust.';

CREATE INDEX idx_reviews_investigation ON investigation_reviews (investigation_id, reviewed_at);
CREATE INDEX idx_reviews_payment ON investigation_reviews (payment_id, reviewed_at);
CREATE INDEX idx_reviews_by ON investigation_reviews (reviewed_by, reviewed_at DESC);
CREATE INDEX idx_reviews_agreement ON investigation_reviews (agreed, reviewed_at DESC);

-- -----------------------------------------------------------------------------
-- Append-only enforcement
--
-- The same pattern ledger-service uses for its journal and fraud-service for its
-- analyst decisions. Both tables below record a judgement somebody will be asked
-- to justify later, and a comment saying "append-only" is a hope where a trigger
-- is a guarantee -- including against psql.
--
-- Investigations are covered too, not just reviews. An editable narrative would
-- let the record of what the AI said be brought into line with what turned out to
-- be true, which is the most tempting and least defensible edit available.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION refuse_investigation_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        '% is append-only: % refused. A generated report and a human review of it are '
        'statements about a moment in time; record a new one instead of rewriting either.',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_investigations_append_only
    BEFORE UPDATE OR DELETE ON investigations
    FOR EACH ROW EXECUTE FUNCTION refuse_investigation_mutation();

CREATE TRIGGER trg_investigation_reviews_append_only
    BEFORE UPDATE OR DELETE ON investigation_reviews
    FOR EACH ROW EXECUTE FUNCTION refuse_investigation_mutation();

-- Citations are covered as well. They are the evidence trail for a narrative, and
-- a deletable citation would let a hallucination be tidied away after the fact --
-- which is exactly the record that must survive.
CREATE TRIGGER trg_investigation_citations_append_only
    BEFORE UPDATE OR DELETE ON investigation_citations
    FOR EACH ROW EXECUTE FUNCTION refuse_investigation_mutation();
