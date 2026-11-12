package io.openchain.app.chain;

import io.openchain.app.history.AddressHistoryService;
import io.openchain.app.history.AddressHistoryStore.Progress;
import io.openchain.app.history.AddressHistoryStore.Status;
import io.openchain.app.watchlist.WatchlistRepository;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OBSERVATION ONLY (docs/EXEMPT_ADDRESSES_ANALYSIS.md, step 2 point 1): measures the addresses exempt from the working-set
 * trim (watchlist and address histories), per category and per address. It changes nothing: no exemption, purge or cap
 * depends on it. The proposed caps (10,000 flows per address, 150,000 in all) are reported as information only.
 */
public final class ExemptWorkingSetMonitor {
    /** Heap retained per flow and per signal, measured in the lab (FlowFootprintProbe, 2026-11-11): estimates only. */
    public static final long BYTES_PER_FLOW = 703, BYTES_PER_SIGNAL = 1_057;
    /** Proposed caps, NOT enforced: reported to calibrate them on production data. */
    public static final int PROPOSED_FLOWS_PER_ADDRESS = 10_000, PROPOSED_EXEMPT_FLOWS_TOTAL = 150_000;

    /**
     * Why an address is exempt. WATCHLIST wins when an address is also a history (flagged {@code alsoHistory}).
     * History: LOADED = complete with events, PARTIAL = truncated with events, IN_PROGRESS = pending or running,
     * EMPTY = finished with no event, FAILED.
     */
    public enum Category { WATCHLIST, HISTORY_LOADED, HISTORY_PARTIAL, HISTORY_IN_PROGRESS, HISTORY_EMPTY, HISTORY_FAILED }

    /** One exempt address as measured. {@code viewAge} is null when not consulted since start-up ({@code recordAge} then). */
    public record Entry(String network, String address, Category category, boolean alsoHistory, String historyStatus,
                        long historyEvents, int flowsInMemory, int signalsInMemory, boolean profileInMemory, long estimatedBytes,
                        Instant lastViewedAt, Long viewAgeSeconds, long recordAgeSeconds, boolean overProposedPerAddressCap) {}

    public record CategorySummary(int addresses, long flows, long signals, long estimatedBytes, long maxFlowsPerAddress,
                                  Long oldestViewAgeSeconds, int notViewedSinceStart) {}

    /**
     * @param distinctExemptFlows distinct flows touching at least one exempt address (a flow between two of them counts once)
     * @param overProposedTotal   distinct exempt flows (watchlist excluded) beyond the proposed total, 0 if none
     */
    public record Snapshot(Instant at, List<Entry> entries, Map<Category, CategorySummary> byCategory, long distinctExemptFlows,
                           long distinctExemptFlowsOutsideWatchlist, int addressesOverProposedPerAddressCap, long overProposedTotal,
                           long millis) {}

    private final IntelligencePipeline intelligence;
    private final AddressHistoryService history;
    private final java.util.function.Supplier<List<WatchlistRepository.Entry>> watchlist;
    private final Clock clock;
    private volatile Snapshot last;

    public ExemptWorkingSetMonitor(IntelligencePipeline intelligence, AddressHistoryService history,
                                   java.util.function.Supplier<List<WatchlistRepository.Entry>> watchlist, Clock clock) {
        this.intelligence = intelligence;
        this.history = history;
        this.watchlist = watchlist;
        this.clock = clock;
    }

    /** The latest snapshot ({@link #refresh}), or null before the first one. */
    public Snapshot last() { return last; }

    static Category historyCategory(Progress p) {
        Status s = p.status();
        if (s == Status.FAILED) return Category.HISTORY_FAILED;
        if (s == Status.PENDING || s == Status.RUNNING) return Category.HISTORY_IN_PROGRESS;
        if (p.eventsFound() <= 0) return Category.HISTORY_EMPTY;
        return s == Status.TRUNCATED ? Category.HISTORY_PARTIAL : Category.HISTORY_LOADED;
    }

    /** Measures every exempt address now (reads the stores; called after each trim and on demand). */
    public Snapshot refresh() {
        long t0 = System.nanoTime();
        Instant now = clock.instant();
        Map<Address, Progress> histories = new LinkedHashMap<>();
        for (Progress p : history.progressRows()) {
            try {
                histories.put(new Address(p.network(), p.address()), p);
            } catch (RuntimeException e) { // malformed row (e.g. an EVM address recorded on TRON): still reported
                histories.put(new Address(p.network(), p.address().strip()), p);
            }
        }
        Set<Address> watched = new HashSet<>();
        for (var w : watchlist.get()) watched.add(new Address(Network.fromId(w.network()), w.address()));
        Set<Address> exempt = new java.util.LinkedHashSet<>(watched);
        exempt.addAll(histories.keySet());

        List<Entry> entries = new ArrayList<>();
        Set<String> distinct = new HashSet<>(), distinctOutsideWatchlist = new HashSet<>();
        for (Address a : exempt) {
            Progress p = histories.get(a);
            boolean isWatched = watched.contains(a);
            Category c = isWatched ? Category.WATCHLIST : historyCategory(p);
            List<ValueFlow> flows = intelligence.flows().involving(a);
            for (ValueFlow f : flows) {
                distinct.add(f.eventId());
                if (!isWatched) distinctOutsideWatchlist.add(f.eventId());
            }
            int signals = intelligence.signals().findByAddress(a).size();
            Instant viewed = history.lastViewed(a).orElse(null);
            Instant recorded = p == null ? now : p.updatedAt() == null ? p.createdAt() : p.updatedAt();
            entries.add(new Entry(a.network().id(), a.value(), c, isWatched && p != null, p == null ? null : p.status().name(),
                    p == null ? 0 : p.eventsFound(), flows.size(), signals, intelligence.profiles().has(a),
                    flows.size() * BYTES_PER_FLOW + signals * BYTES_PER_SIGNAL, viewed,
                    viewed == null ? null : Duration.between(viewed, now).toSeconds(),
                    recorded == null ? 0 : Duration.between(recorded, now).toSeconds(), flows.size() > PROPOSED_FLOWS_PER_ADDRESS));
        }
        entries.sort(Comparator.comparingInt(Entry::flowsInMemory).reversed());

        Map<Category, CategorySummary> byCategory = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            List<Entry> in = entries.stream().filter(e -> e.category() == c).toList();
            byCategory.put(c, new CategorySummary(in.size(), in.stream().mapToLong(Entry::flowsInMemory).sum(),
                    in.stream().mapToLong(Entry::signalsInMemory).sum(), in.stream().mapToLong(Entry::estimatedBytes).sum(),
                    in.stream().mapToLong(Entry::flowsInMemory).max().orElse(0),
                    in.stream().map(Entry::viewAgeSeconds).filter(java.util.Objects::nonNull).max(Long::compare).orElse(null),
                    (int) in.stream().filter(e -> e.lastViewedAt() == null).count()));
        }
        int over = (int) entries.stream().filter(Entry::overProposedPerAddressCap).count();
        long overTotal = Math.max(0, distinctOutsideWatchlist.size() - PROPOSED_EXEMPT_FLOWS_TOTAL);
        Snapshot s = new Snapshot(now, List.copyOf(entries), byCategory, distinct.size(), distinctOutsideWatchlist.size(), over,
                overTotal, (System.nanoTime() - t0) / 1_000_000);
        last = s;
        return s;
    }
}
