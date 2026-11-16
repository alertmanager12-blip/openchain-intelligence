#!/usr/bin/env bash
# Daily logical backup of the ChainSentinel PostgreSQL database, stored OUTSIDE Docker.
#
# Why: a Docker Desktop factory reset (or a lost Docker volume) deletes the database. A dump on the host survives it.
# What: pg_dump in custom format (compressed, restorable with pg_restore), one file per run, older files pruned.
# No credential is stored here: the user and database are read inside the container, and pg_dump connects through
# the container's local socket.
#
# Usage:   deploy/backup/pg-backup.sh [backup_dir] [keep_days]
# Default: ~/Backups/chainsentinel, keep 14 days.
# Restore: see deploy/backup/README.md.
set -euo pipefail

CONTAINER="${OCI_PG_CONTAINER:-openchain-intelligence-postgres-1}"
DIR="${1:-$HOME/Backups/chainsentinel}"
KEEP_DAYS="${2:-14}"
# launchd starts jobs with a minimal PATH: add the usual Docker Desktop locations
export PATH="$PATH:/usr/local/bin:/opt/homebrew/bin:/Applications/Docker.app/Contents/Resources/bin"

mkdir -p "$DIR"
chmod 700 "$DIR"
log() { echo "$(date '+%F %T') $*" | tee -a "$DIR/backup.log"; }

if ! docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null | grep -q true; then
  log "ERROR: container $CONTAINER is not running; no backup taken"
  exit 1
fi

DB_USER="$(docker exec "$CONTAINER" printenv POSTGRES_USER)"
DB_NAME="$(docker exec "$CONTAINER" printenv POSTGRES_DB)"
STAMP="$(date '+%Y%m%d-%H%M%S')"
OUT="$DIR/oci-$STAMP.dump"
TMP="$OUT.partial"

# custom format, compressed; written to a temporary name first so a failed run never looks like a good backup
if docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -Z 6 > "$TMP"; then
  # sanity check: the archive must be readable and list the Flyway history table
  # (no `| grep -q`: with pipefail, grep exiting early makes the pipeline fail with SIGPIPE and discards a good dump)
  toc="$(docker exec -i "$CONTAINER" pg_restore -l < "$TMP" || true)"
  if grep -q flyway_schema_history <<<"$toc"; then
    mv "$TMP" "$OUT"
    chmod 600 "$OUT"
    log "OK: $OUT ($(du -h "$OUT" | cut -f1))"
  else
    rm -f "$TMP"
    log "ERROR: dump produced but not readable by pg_restore; discarded"
    exit 2
  fi
else
  rm -f "$TMP"
  log "ERROR: pg_dump failed"
  exit 3
fi

# prune old dumps, never the newest one
find "$DIR" -name 'oci-*.dump' -type f -mtime +"$KEEP_DAYS" ! -name "$(basename "$OUT")" -print -delete | while read -r f; do log "pruned $f"; done
