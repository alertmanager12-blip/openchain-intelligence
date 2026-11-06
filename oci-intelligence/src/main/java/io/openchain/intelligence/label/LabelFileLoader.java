package io.openchain.intelligence.label;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads label lists (e.g. an OFAC-style sanctions list converted to this format).
 *
 * <p>CSV: header {@code network,address,category,name,source,reliability,observedAt,evidenceLevel,reference};
 * lines starting with {@code #} are comments; fields must not contain commas.
 * <p>JSON: array of objects with the same field names.
 * <p>Invalid rows are rejected with their line/index (fail fast: a silently half-loaded sanctions list is worse than none).
 */
public final class LabelFileLoader {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Row(String network, String address, String category, String name, String source,
                      Double reliability, String observedAt, String evidenceLevel, String reference) {}

    private LabelFileLoader() {}

    public static List<AddressLabel> readCsv(Reader reader) throws IOException {
        List<AddressLabel> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line; int n = 0; boolean header = true;
            while ((line = br.readLine()) != null) {
                n++;
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (header) { header = false; if (t.toLowerCase().startsWith("network,")) continue; }
                String[] c = t.split(",", -1);
                if (c.length < 7) throw new IllegalArgumentException("line " + n + ": expected at least 7 columns");
                try {
                    out.add(toLabel(new Row(c[0], c[1], c[2], c[3], c[4], Double.valueOf(c[5].strip()), c[6],
                            c.length > 7 ? c[7] : null, c.length > 8 ? c[8] : null)));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("line " + n + ": " + e.getMessage(), e);
                }
            }
        }
        return out;
    }

    public static List<AddressLabel> readJson(InputStream in) {
        Row[] rows = JSON.readValue(in, Row[].class);
        List<AddressLabel> out = new ArrayList<>(rows.length);
        for (int i = 0; i < rows.length; i++) {
            try { out.add(toLabel(rows[i])); }
            catch (RuntimeException e) { throw new IllegalArgumentException("entry " + i + ": " + e.getMessage(), e); }
        }
        return out;
    }

    /** Loads a classpath resource, choosing the format from its extension. */
    public static List<AddressLabel> fromClasspath(String resource) throws IOException {
        try (InputStream in = LabelFileLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("resource not found: " + resource);
            return resource.endsWith(".json") ? readJson(in) : readCsv(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    static AddressLabel toLabel(Row r) {
        if (r.address() == null || r.network() == null || r.category() == null || r.source() == null)
            throw new IllegalArgumentException("network, address, category and source are required");
        double rel = r.reliability() == null ? 0.5 : r.reliability();
        EvidenceLevel lvl = blank(r.evidenceLevel()) ? EvidenceLevel.RISK_SIGNAL : EvidenceLevel.valueOf(r.evidenceLevel().strip().toUpperCase());
        Instant at = blank(r.observedAt()) ? Instant.EPOCH : Instant.parse(r.observedAt().strip());
        return new AddressLabel(new Address(Network.fromId(r.network().strip()), r.address().strip()),
                LabelCategory.valueOf(r.category().strip().toUpperCase()), blank(r.name()) ? null : r.name().strip(),
                r.source().strip(), rel, at, lvl, blank(r.reference()) ? null : r.reference().strip());
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
