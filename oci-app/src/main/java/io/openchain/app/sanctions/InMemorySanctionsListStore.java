package io.openchain.app.sanctions;

import io.openchain.intelligence.label.ofac.SdnPublication;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory mode (no database): the list is fetched again at each start. */
public final class InMemorySanctionsListStore implements SanctionsListStore {
    private final List<Stored> accepted = new ArrayList<>();
    private final AtomicInteger refused = new AtomicInteger();

    @Override public synchronized Optional<Stored> latestAccepted() {
        return accepted.isEmpty() ? Optional.empty() : Optional.of(accepted.getLast());
    }

    @Override public synchronized Stored saveAccepted(SdnPublication publication, Instant fetchedAt, String sha256) {
        Stored s = new Stored(accepted.size() + 1, fetchedAt, sha256, publication);
        accepted.add(s);
        return s;
    }

    @Override public void saveRefused(LocalDate issued, Instant at, String sha256, String reason) { refused.incrementAndGet(); }

    public synchronized List<Stored> accepted() { return List.copyOf(accepted); }

    public int refusedCount() { return refused.get(); }
}
