-- Phase 1 — database foundation.
--
-- PostGIS provides the geometry types and spatial indexes used from Phase 4.
-- btree_gist is required by the exclusion constraints added in Phase 9, because
-- they mix an equality column with a range column in one GiST index.
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Identifier convention.
--
-- Entities take BIGINT ids from sequences with a pooled Hibernate allocator
-- (allocationSize = 50). IDENTITY is deliberately avoided: it disables Hibernate
-- JDBC batching, and a single scheduling run writes tens of thousands of rows.
--
-- INCREMENT BY must equal the allocationSize on the entity, or the two
-- allocators will hand out colliding values.
CREATE SEQUENCE IF NOT EXISTS app_user_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS refresh_token_seq INCREMENT BY 50 START WITH 1;
CREATE SEQUENCE IF NOT EXISTS audit_log_seq INCREMENT BY 50 START WITH 1;
