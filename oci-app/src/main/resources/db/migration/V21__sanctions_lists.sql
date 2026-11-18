-- Official sanctions lists with their provenance (ADR-0027). Every download is recorded: accepted publications with their
-- addresses (the last accepted one is applied at start, so an outage of the source never empties screening), refused ones
-- with the reason (never applied). Only the addresses of indexed networks are kept: a few hundred rows per publication.
create table sanctions_publications (
  id            bigserial primary key,
  source        text not null,
  issued        date,                      -- null when the document did not parse
  fetched_at    timestamptz not null,
  sha256        text,
  address_count int not null default 0,
  status        text not null check (status in ('ACCEPTED', 'REFUSED')),
  reason        text
);
create index sanctions_publications_accepted on sanctions_publications (source, id) where status = 'ACCEPTED';

create table sanctions_addresses (
  publication_id bigint not null references sanctions_publications (id) on delete cascade,
  ord            int not null,             -- order in the publication
  network        text not null,
  address        text not null,
  currencies     text not null,            -- as listed, comma separated (e.g. TRX,USDT)
  sdn_uid        text not null,
  name           text not null,
  programs       text not null,            -- comma separated (e.g. CYBER2)
  primary key (publication_id, network, address)
);
