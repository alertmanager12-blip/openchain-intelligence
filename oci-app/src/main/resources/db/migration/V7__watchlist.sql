-- Operator watchlist: the organisation's own wallets (deposit addresses, treasury, hot wallets). Behavioural rules of the
-- rule pack are scoped to them (and their direct counterparties) as soon as the list is not empty.
create table watchlist (
  id          text primary key,
  network     text not null,
  address     text not null,             -- canonical form (EVM: lowercase)
  name        text,
  created_by  text,
  created_at  timestamptz not null,
  unique (network, address)
);
