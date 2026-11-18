-- Predictions whose condition counts distinct recipients rather than transfers (DISPERSION_REPEATS, ADR-0023 amendment 2).
-- The append-only trigger of V18 refuses updates of rows, not this change of table: existing rows take the default.
alter table predictions add column distinct_recipients boolean not null default false;
