# Database backup and restore

The operator state (API keys, rules and their versions, alerts and their history, connectors with encrypted secrets,
deliveries, watchlist, users, label reports, address histories, indexer checkpoints) lives in the PostgreSQL volume
`pgdata`. **A Docker Desktop "Reset to factory defaults", `docker compose down -v` or a lost Docker disk deletes it.**
Keep dumps outside Docker.

## Backup

```bash
deploy/backup/pg-backup.sh                      # ~/Backups/chainsentinel, keep 14 days
deploy/backup/pg-backup.sh /path/to/dir 30      # other directory, keep 30 days
```

One compressed `pg_dump` (custom format) per run, written under a temporary name, checked with `pg_restore -l`, then
renamed; failures are logged in `backup.log` and never leave a file that looks valid. No credential is stored: the
database user and name are read inside the container.

Schedule it: `io.openchain.pg-backup.plist` (macOS launchd, daily at 03:30; install steps in the file) or cron on Linux.
Copy the backup directory off the machine as well (another disk or an encrypted cloud folder): a backup on the same
disk does not survive the loss of that disk.

**Also back up `OCI_CRYPTO_MASTER_KEY` and `OCI_SECURITY_API_KEY_PEPPER` separately** (secret manager): without them a
restored database cannot decrypt connector secrets or verify API keys (`docs/guides/security-hardening.md`).

## Restore

On an empty database (fresh volume), with the application stopped so it does not migrate an empty schema first:

```bash
docker compose stop app
docker compose up -d postgres
F=~/Backups/chainsentinel/oci-YYYYMMDD-HHMMSS.dump
U=$(docker exec openchain-intelligence-postgres-1 printenv POSTGRES_USER)
D=$(docker exec openchain-intelligence-postgres-1 printenv POSTGRES_DB)
docker exec -i openchain-intelligence-postgres-1 pg_restore -U "$U" -d "$D" --clean --if-exists --no-owner < "$F"
docker compose up -d app
```

Then check `docker compose logs app | grep -E "Persistence mode|schema v"` and that alerts, rules and keys are back.
Indexing resumes from the restored checkpoints (`max-resume-gap` applies if the dump is old).
