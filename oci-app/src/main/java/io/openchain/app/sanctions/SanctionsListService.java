package io.openchain.app.sanctions;

import io.openchain.app.labels.LabelCatalog;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.label.ofac.OfacSdnParser;
import io.openchain.intelligence.label.ofac.SdnPublication;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OFAC SDN list in force (ADR-0027): fetched as a stream (never a temporary file), checked, stored, applied as
 * {@code SANCTIONED} labels. A refused or failed download never empties screening: the last accepted publication stays.
 */
public final class SanctionsListService {
    private static final Logger log = LoggerFactory.getLogger(SanctionsListService.class);

    public enum Result { ACCEPTED, UNCHANGED, REFUSED, FAILED }

    /** NONE: no list ever accepted. STALE: the list in force was not confirmed for longer than the allowed age. */
    public enum State { NONE, CURRENT, STALE }

    public record Status(State state, String source, LocalDate issued, Instant fetchedAt, Instant checkedAt, int tron,
                         int ethereum, String lastError) {}

    private final SanctionsListStore store;
    private final LabelCatalog labels;
    private final Supplier<InputStream> download;
    private final Clock clock;
    private final double maxDrop;
    private final Duration staleAfter;
    private volatile SanctionsListStore.Stored current;
    private volatile Instant checkedAt;
    private volatile String lastError;

    public SanctionsListService(SanctionsListStore store, LabelCatalog labels, Supplier<InputStream> download, Clock clock,
                                double maxDrop, Duration staleAfter) {
        this.store = store;
        this.labels = labels;
        this.download = download;
        this.clock = clock;
        this.maxDrop = maxDrop;
        this.staleAfter = staleAfter;
    }

    /** At start, before any network call: the last accepted publication is applied again. */
    public synchronized void loadStored() {
        store.latestAccepted().ifPresent(s -> {
            apply(null, s.publication());
            current = s;
            checkedAt = s.fetchedAt();
            log.info("sanctions list: OFAC SDN publication of {} re-applied from storage ({} address(es))", s.publication().issued(),
                    s.publication().addresses().size());
        });
    }

    public synchronized Result refresh() {
        Instant now = clock.instant();
        SdnPublication fetched;
        String sha;
        try (InputStream raw = download.get(); DigestInputStream in = new DigestInputStream(raw, sha256())) {
            try {
                // the XML parser closes its input at the end of the document; the source must stay open for the digest
                fetched = OfacSdnParser.parse(new java.io.FilterInputStream(in) { @Override public void close() { } });
            } catch (IllegalArgumentException e) {
                return refuse(null, now, null, "unreadable document: " + e.getMessage());
            }
            in.transferTo(java.io.OutputStream.nullOutputStream()); // the digest covers the whole document
            sha = HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (IOException | UncheckedIOException e) {
            lastError = "download failed: " + (e instanceof UncheckedIOException u ? u.getCause().getMessage() : e.getMessage());
            log.warn("sanctions list: {}; the list in force stays", lastError);
            return Result.FAILED;
        } catch (RuntimeException e) {
            lastError = "download failed: " + e.getMessage();
            log.warn("sanctions list: {}; the list in force stays", lastError);
            return Result.FAILED;
        }
        SanctionsListStore.Stored prev = current;
        if (prev != null && prev.sha256().equals(sha)) {
            checkedAt = now;
            lastError = null;
            return Result.UNCHANGED;
        }
        if (prev != null && fetched.issued().isBefore(prev.publication().issued())) {
            return refuse(fetched.issued(), now, sha, "publication of " + fetched.issued() + " is older than the one in force ("
                    + prev.publication().issued() + ")");
        }
        int before = prev == null ? 0 : prev.publication().addresses().size(), after = fetched.addresses().size();
        if (after == 0) return refuse(fetched.issued(), now, sha, "no address of an indexed network in the publication");
        if (before > 0 && (before - after) > before * maxDrop) {
            return refuse(fetched.issued(), now, sha, "address count would drop from " + before + " to " + after
                    + " (more than " + Math.round(maxDrop * 100) + " % at once)");
        }
        SanctionsListStore.Stored saved = store.saveAccepted(fetched, now, sha);
        apply(prev == null ? null : prev.publication(), fetched);
        current = saved;
        checkedAt = now;
        lastError = null;
        log.info("sanctions list: OFAC SDN publication of {} accepted: {} address(es) ({} before)", fetched.issued(), after, before);
        return Result.ACCEPTED;
    }

    public Status status() {
        SanctionsListStore.Stored s = current;
        if (s == null) return new Status(State.NONE, SdnPublication.SOURCE, null, null, checkedAt, 0, 0, lastError);
        int tron = (int) s.publication().addresses().stream().filter(a -> a.address().network() == Network.TRON).count();
        int eth = s.publication().addresses().size() - tron;
        State state = checkedAt != null && Duration.between(checkedAt, clock.instant()).compareTo(staleAfter) > 0 ? State.STALE : State.CURRENT;
        return new Status(state, SdnPublication.SOURCE, s.publication().issued(), s.fetchedAt(), checkedAt, tron, eth, lastError);
    }

    private Result refuse(LocalDate issued, Instant at, String sha, String reason) {
        lastError = "publication refused: " + reason;
        try {
            store.saveRefused(issued, at, sha, reason);
        } catch (RuntimeException e) {
            log.warn("sanctions list: refused publication not recorded: {}", e.toString());
        }
        log.warn("sanctions list: {}; the list in force stays", lastError);
        return Result.REFUSED;
    }

    /** Labels of {@code neu} in force; addresses of {@code old} no longer listed lose their OFAC label (delisting). */
    private void apply(SdnPublication old, SdnPublication neu) {
        Set<io.openchain.core.model.Address> keep = neu.addresses().stream().map(a -> a.address()).collect(Collectors.toSet());
        if (old != null) {
            old.addresses().stream().map(a -> a.address()).filter(a -> !keep.contains(a))
                    .forEach(a -> labels.remove(a, LabelCategory.SANCTIONED, SdnPublication.SOURCE));
        }
        for (AddressLabel l : neu.toLabels()) labels.save(l);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
