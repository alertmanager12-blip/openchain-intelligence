package io.openchain.intelligence.flow;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One token transfer seen by two sources counts once (Fund Flow Trace P0-B, case C7). Live indexing identifies a token
 * transfer as {@code <net>:<tx>:<logIndex>}; an address history source that has no log index (TronGrid TRC-20) as
 * {@code <net>:<tx>:trc20:<hash>:<n>}. The two ids never match, so the same transfer would be counted twice.
 * <p>Matching key: network, transaction, token contract, sender, recipient, raw amount, and the rank of that same tuple
 * within the transaction (live: by log index; history: the {@code n} of its id, its order of appearance). Native
 * transfers and sources that give the log index use the live id already and are not concerned.
 * <p>Known limit: TronGrid gives two strictly identical transfers of one transaction split across two pages the same
 * {@code n = 0}; the second is then lost (undercount) and nothing here can tell them apart.
 */
public final class TransferIdentity {
    private TransferIdentity() {}

    private static final Pattern HISTORY = Pattern.compile("^[a-z0-9-]+:[^:]+:trc20:[0-9a-f]+:(\\d+)$");
    private static final Pattern LIVE = Pattern.compile("^[a-z0-9-]+:[^:]+:(\\d+)$");

    /** Rank of a history token transfer without log index ({@code n} of its id), empty for any other flow. */
    static OptionalInt historyRank(ValueFlow f) {
        Matcher m = HISTORY.matcher(f.eventId());
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /** Log index of a live token transfer, empty for native transfers and history ids. */
    static OptionalInt liveLogIndex(ValueFlow f) {
        if (f.token().isNative()) return OptionalInt.empty();
        Matcher m = LIVE.matcher(f.eventId());
        return m.matches() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    static boolean sameTransfer(ValueFlow a, ValueFlow b) {
        return a.network() == b.network() && a.txHash().equals(b.txHash()) && Objects.equals(a.token().contract(), b.token().contract())
                && a.from().equals(b.from()) && a.to().equals(b.to()) && a.rawAmount().equals(b.rawAmount());
    }

    /**
     * The flow already held that is {@code f} seen by the other source (a live flow for a history flow, a history flow
     * for a live one), if any. Reads only the sender's outgoing flows.
     */
    public static Optional<ValueFlow> counterpart(ValueFlow f, FlowStore store) {
        OptionalInt rank = historyRank(f);
        OptionalInt logIndex = liveLogIndex(f);
        if (rank.isEmpty() && logIndex.isEmpty()) return Optional.empty();
        List<ValueFlow> same = store.outgoing(f.from()).stream()
                .filter(g -> !g.eventId().equals(f.eventId()) && sameTransfer(f, g)).toList();
        if (same.isEmpty()) return Optional.empty();
        if (rank.isPresent()) {
            List<ValueFlow> live = same.stream().filter(g -> liveLogIndex(g).isPresent())
                    .sorted(Comparator.comparingInt(g -> liveLogIndex(g).getAsInt())).toList();
            return rank.getAsInt() < live.size() ? Optional.of(live.get(rank.getAsInt())) : Optional.empty();
        }
        int myRank = (int) same.stream().filter(g -> liveLogIndex(g).isPresent() && liveLogIndex(g).getAsInt() < logIndex.getAsInt()).count();
        return same.stream().filter(g -> historyRank(g).isPresent() && historyRank(g).getAsInt() == myRank).findFirst();
    }
}
