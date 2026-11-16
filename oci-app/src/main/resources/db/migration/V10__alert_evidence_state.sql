-- Chain reorganisations (docs/P0_REORG_DESIGN.md § 3.3): an alert is never deleted, its evidence is qualified.
-- Additive and backward compatible: existing alerts become VALID with no withdrawn event.
alter table alerts
  add column evidence_state      text  not null default 'VALID'
      check (evidence_state in ('VALID', 'PARTIALLY_WITHDRAWN', 'WITHDRAWN')),
  add column withdrawn_event_ids jsonb not null default '[]';   -- matched events that left the canonical chain

-- alerts to re-check after a reorganisation (console filter, API)
create index alerts_evidence_withdrawn on alerts (evidence_state, updated_at) where evidence_state <> 'VALID';
