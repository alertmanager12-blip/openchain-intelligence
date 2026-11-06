package io.openchain.intelligence;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test fixture: a small in-memory world. All addresses are fictional. */
public final class Fx {
    public static final Network NET = Network.ETHEREUM;
    public static final Token USDT = new Token(NET, "0xdemo00000000000000000000000000000000usdt", "USDT", 6);
    public static final Token DAI = new Token(NET, "0xdemo000000000000000000000000000000000dai", "DAI", 18);
    public static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    public final InMemoryFlowStore flows = new InMemoryFlowStore();
    public final InMemoryLabelRepository labels = new InMemoryLabelRepository();
    public DetectionConfig config = DetectionConfig.defaults();
    private final AtomicInteger seq = new AtomicInteger();

    public static Address a(String s) { return new Address(NET, "0x" + s); }

    public static Instant t(Duration d) { return T0.plus(d); }
    public static Instant min(long m) { return T0.plus(Duration.ofMinutes(m)); }
    public static Instant days(long d) { return T0.plus(Duration.ofDays(d)); }

    public static BigInteger raw(String human, Token tok) { return new BigDecimal(human).movePointRight(tok.decimals()).toBigIntegerExact(); }

    public static TokenTransfer transfer(String tx, int log, Address from, Address to, String amount, Token tok, Instant at) {
        return new TokenTransfer(NET, tx, log, 1, "0xblock", at, from, to, tok, raw(amount, tok));
    }

    /** Creates and stores a USDT flow. */
    public ValueFlow flow(Address from, Address to, String amount, Instant at) { return flow(from, to, amount, USDT, at); }

    public ValueFlow flow(Address from, Address to, String amount, Token tok, Instant at) {
        int n = seq.incrementAndGet();
        ValueFlow f = ValueFlow.of(transfer("0xtx" + n, 0, from, to, amount, tok, at));
        flows.add(f);
        return f;
    }

    public AddressLabel label(Address a, LabelCategory c, double reliability, EvidenceLevel lvl) {
        AddressLabel l = new AddressLabel(a, c, "DEMO " + c, "demo-source-" + c.name().toLowerCase(), reliability, T0.minus(Duration.ofDays(10)), lvl, "demo://" + a.value());
        labels.save(l);
        return l;
    }

    public DetectionContext ctx(Address subject, ValueFlow trigger) {
        return new DetectionContext(subject, trigger, null, null, flows, labels, config, T0.plus(Duration.ofDays(400)));
    }

    public DetectionContext ctx(Address subject, ChainEvent e) {
        return new DetectionContext(subject, null, e, null, flows, labels, config, T0.plus(Duration.ofDays(400)));
    }

    public static ChainEvent event(String id, ChainEvent.EventType type, Map<String, Object> fields) {
        return new ChainEvent(id, type, NET, 1, "0xblock", T0, T0, false, fields);
    }
}
