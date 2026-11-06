package io.openchain.app.rules;

import io.openchain.alerting.rule.RuleParser.Format;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Rule storage ({@link InMemoryRuleRepository}, {@link JdbcRuleRepository}): a rule row (current version pointer,
 * enabled flag, soft-delete tombstone, {@code rowVersion} for optimistic locking) and its immutable versions.
 */
public interface RuleRepository {

    record VersionRow(int number, String source, Format format, String compiledHash, String ruleVersion, Instant createdAt) {}

    /**
     * @param rowVersion version read (0 for a rule never saved); {@link #save} rejects a stale one
     * @param deletedAt  tombstone of a deleted rule (kept so that repository rules are not re-imported)
     */
    record RuleRow(String id, String origin, boolean enabled, List<VersionRow> versions, Instant createdAt, Instant updatedAt,
                   Instant deletedAt, long rowVersion) {
        public RuleRow { versions = List.copyOf(versions); }
        public VersionRow current() { return versions.getLast(); }
        public boolean deleted() { return deletedAt != null; }
    }

    /** All rules, deleted ones included. */
    List<RuleRow> findAll();

    Optional<RuleRow> find(String id);

    /**
     * Inserts ({@code rowVersion == 0}) or updates the rule row and appends its new versions (versions are immutable:
     * stored ones are never rewritten), atomically.
     * @return the row with its new {@code rowVersion}
     * @throws io.openchain.app.persistence.OptimisticLockException when {@code rowVersion} is stale
     */
    RuleRow save(RuleRow row);
}
