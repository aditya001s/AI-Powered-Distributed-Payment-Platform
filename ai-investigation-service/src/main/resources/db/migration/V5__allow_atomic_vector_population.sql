-- PolicyWriter inserts JPA-managed chunk metadata and fills the pgvector column
-- through native SQL in the same transaction. Hibernate cannot include the unmapped
-- vector in its INSERT, so the column must permit the transaction's intermediate
-- state. The transaction never commits without VectorStore storing the embedding,
-- and startup validation/repair refuses to serve an unusable corpus.
ALTER TABLE policy_chunks
    ALTER COLUMN embedding DROP NOT NULL;
