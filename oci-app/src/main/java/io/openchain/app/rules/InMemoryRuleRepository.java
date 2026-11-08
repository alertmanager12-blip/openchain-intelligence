package io.openchain.app.rules;

import io.openchain.app.persistence.OptimisticLockException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRuleRepository implements RuleRepository {

    private final Map<String, RuleRow> rows = new ConcurrentHashMap<>();
    private final Map<String, String> actionsOverrides = new ConcurrentHashMap<>();

    @Override public Optional<String> actionsOverride(String id) { return Optional.ofNullable(actionsOverrides.get(id)); }

    @Override public void setActionsOverride(String id, String actionsJson) {
        if (actionsJson == null) actionsOverrides.remove(id); else actionsOverrides.put(id, actionsJson);
    }

    @Override public List<RuleRow> findAll() { return new ArrayList<>(rows.values()); }

    @Override public Optional<RuleRow> find(String id) { return Optional.ofNullable(rows.get(id)); }

    @Override public synchronized RuleRow save(RuleRow r) {
        RuleRow stored = rows.get(r.id());
        long current = stored == null ? 0 : stored.rowVersion();
        if (current != r.rowVersion()) throw new OptimisticLockException("rule '" + r.id() + "' was modified concurrently");
        RuleRow saved = new RuleRow(r.id(), r.origin(), r.enabled(), r.versions(), r.createdAt(), r.updatedAt(), r.deletedAt(),
                r.rowVersion() + 1);
        rows.put(r.id(), saved);
        return saved;
    }
}
