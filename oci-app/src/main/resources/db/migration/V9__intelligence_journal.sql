-- Chain-derived intelligence (ADR-0024 step 2), written behind the hot path and reloaded at start-up: value flows (which
-- rebuild wallet profiles), detection signals and risk scores. Kept for oci.persistence.intelligence-retention (7 days).
create table value_flows (
  event_id        text primary key,
  network         text not null,
  tx_hash         text not null,
  ts              timestamptz not null,
  from_address    text not null,
  to_address      text not null,
  token_contract  text,
  token_symbol    text not null,
  token_decimals  int not null,
  raw_amount      numeric not null
);
create index value_flows_ts on value_flows (ts);

create table signals (
  id           text primary key,
  network      text not null,
  address      text not null,
  detected_at  timestamptz not null,
  payload      jsonb not null
);
create index signals_detected_at on signals (detected_at);

create table risk_scores (
  id           bigserial primary key,
  network      text not null,
  address      text not null,
  computed_at  timestamptz not null,
  payload      jsonb not null
);
create index risk_scores_computed_at on risk_scores (computed_at);
