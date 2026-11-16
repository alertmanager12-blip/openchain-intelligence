-- Indexing coverage (Fund Flow Trace P0-B, docs/FUND_FLOW_TRACE_P0_B_COVERAGE.md § 2.3). Until now a block range skipped
-- at a resume (max-resume-gap) was only a WARN log line, and the start of continuous live indexing was not kept: an
-- answer could not say which blocks it had seen. Metadata only, no flow data.

-- Block ranges that were NOT indexed (inclusive bounds).
create table indexer_gaps (
  network      text not null,
  from_block   bigint not null,
  to_block     bigint not null check (to_block >= from_block),
  cause        text not null check (cause in ('RESUME_GAP')),
  detected_at  timestamptz not null,
  primary key (network, from_block)
);

-- First block of the current run of continuous live indexing, per network. exact = false when it was set at the first
-- start after this migration on an already indexed network: blocks before it may have been indexed too, but their
-- continuity is not known (gaps before this migration were only logged).
create table indexer_live_coverage (
  network     text primary key,
  from_block  bigint not null,
  exact       boolean not null,
  since       timestamptz not null
);
