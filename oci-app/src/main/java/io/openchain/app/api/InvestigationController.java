package io.openchain.app.api;

import io.openchain.app.analysis.InvestigationService;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.app.labels.LabelReportStore;
import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Network;
import io.openchain.core.model.NetworkStatus;
import io.openchain.core.model.Page;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Wallets, transactions, graph, networks, signals and labels (scope wallets:read; label reports: labels:report). */
@RestController
@RequestMapping("/api/v1")
public class InvestigationController {

    private final InvestigationService investigation;
    private final ChainIngestionService chains;
    private final LabelCatalog labels;
    private final LabelReportStore reports;

    public InvestigationController(InvestigationService investigation, ChainIngestionService chains, LabelCatalog labels,
                                   LabelReportStore reports) {
        this.investigation = investigation;
        this.chains = chains;
        this.labels = labels;
        this.reports = reports;
    }

    // ---- wallets ------------------------------------------------------------------------------------------------

    @GetMapping("/wallets/{address}/analysis")
    public Dto.WalletAnalysisDto analysis(@PathVariable String address, @RequestParam String network) {
        Network n = investigation.network(network);
        return investigation.wallet(investigation.address(n, address));
    }

    @GetMapping("/wallets/{address}/transactions")
    public CursorPage<Dto.TxDto> transactions(@PathVariable String address, @RequestParam String network,
                                              @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        Network n = investigation.network(network);
        Address a = investigation.address(n, address);
        investigation.ensureHistory(a);
        int l = CursorPage.limit(limit);
        // the provider's own cursor, wrapped opaquely
        String providerCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                providerCursor = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid cursor");
            }
        }
        Page<ChainTransaction> p;
        try {
            p = chains.provider(n).orElseThrow().getAddressTransactions(a, providerCursor, l);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid cursor");
        }
        String next = p.nextCursor() == null ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(p.nextCursor().getBytes(StandardCharsets.UTF_8));
        return new CursorPage<>(p.items().stream().map(ApiMapper::tx).toList(), next, false);
    }

    @GetMapping("/wallets/{address}/risk")
    public Dto.RiskScoreDto risk(@PathVariable String address, @RequestParam String network) {
        Network n = investigation.network(network);
        Address a = investigation.address(n, address);
        investigation.ensureHistory(a);
        return investigation.risk(a, true);
    }

    @GetMapping("/wallets/{address}/exposure")
    public Dto.ExposureDto exposure(@PathVariable String address, @RequestParam String network,
                                    @RequestParam(defaultValue = "3") int depth) {
        if (depth < 1 || depth > 3) throw ApiException.badRequest("depth must be between 1 and 3");
        Network n = investigation.network(network);
        return investigation.exposure(investigation.address(n, address), depth);
    }

    @GetMapping("/transactions/{hash}/analysis")
    public Dto.TxAnalysisDto transaction(@PathVariable String hash, @RequestParam String network) {
        Network n = investigation.network(network);
        return investigation.transaction(n, investigation.txHash(hash));
    }

    @GetMapping("/graph/{address}")
    public Dto.GraphDto graph(@PathVariable String address, @RequestParam String network, @RequestParam(defaultValue = "1") int depth,
                              @RequestParam(required = false) String token, @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to, @RequestParam(defaultValue = "80") int limit) {
        if (depth < 1 || depth > 3) throw ApiException.badRequest("depth must be between 1 and 3");
        if (limit < 2 || limit > 500) throw ApiException.badRequest("limit must be between 2 and 500");
        Network n = investigation.network(network);
        return investigation.graph(investigation.address(n, address), depth, token, instant(from, "from"), instant(to, "to"), limit);
    }

    // ---- networks / signals -------------------------------------------------------------------------------------

    @GetMapping("/networks")
    public List<Dto.NetworkDto> networks(@RequestParam(defaultValue = "false") boolean all) {
        List<Dto.NetworkDto> out = new ArrayList<>();
        for (Network n : Network.values()) {
            var rt = chains.runtime(n);
            if (rt.isEmpty()) {
                if (all) out.add(new Dto.NetworkDto(n.id(), false, 0, 0, false, null, 0, null, "not enabled on this instance", -1, 0, false));
                continue;
            }
            NetworkStatus s = chains.status(rt.get());
            long indexed = rt.get().lastIndexedBlock();
            String detail = rt.get().lastError() != null ? rt.get().lastError() : s.detail();
            out.add(new Dto.NetworkDto(n.id(), true, s.headBlock(), s.finalizedBlock(), s.healthy() && rt.get().lastError() == null,
                    s.providerId(), s.latencyMillis(), s.checkedAt(), detail, indexed, indexed < 0 ? s.headBlock() : Math.max(0, s.headBlock() - indexed),
                    rt.get().simulator().isPresent()));
        }
        return out;
    }

    @GetMapping("/signals")
    public CursorPage<Dto.SignalDto> signals(@RequestParam(required = false) String network, @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var list = chains.recentSignals().stream()
                .filter(s -> network == null || network.isBlank() || s.address().network().id().equalsIgnoreCase(network))
                .filter(s -> type == null || type.isBlank() || s.type().name().equalsIgnoreCase(type))
                .map(ApiMapper::signal).toList();
        return CursorPage.of(list, cursor, limit);
    }

    // ---- labels -------------------------------------------------------------------------------------------------

    @GetMapping("/labels")
    public CursorPage<Dto.LabelDto> labels(@RequestParam(required = false) String network, @RequestParam(required = false) String address,
                                           @RequestParam(required = false) String category, @RequestParam(required = false) String cursor,
                                           @RequestParam(required = false) Integer limit) {
        Network n = network == null || network.isBlank() ? null : parseNetwork(network);
        LabelCategory c = category == null || category.isBlank() ? null : ApiMapper.category(category);
        List<AddressLabel> src;
        if (address != null && !address.isBlank()) {
            if (n == null) throw ApiException.badRequest("'network' is required with 'address'");
            src = labels.findByAddress(new Address(n, address.trim()));
        } else {
            src = labels.all();
        }
        var list = src.stream().filter(l -> n == null || l.address().network() == n).filter(l -> c == null || l.category() == c)
                .map(ApiMapper::label).toList();
        return CursorPage.of(list, cursor, limit);
    }

    public record LabelReportRequest(String network, String address, String category, String description, List<String> evidenceUrls,
                                     String reference) {}

    @PostMapping("/labels/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Dto.LabelReportDto report(@RequestBody LabelReportRequest r, Authentication auth) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        if (r.network() == null || r.network().isBlank()) errors.add(new ApiException.FieldError("/network", "required", "network is required"));
        if (r.address() == null || r.address().isBlank()) errors.add(new ApiException.FieldError("/address", "required", "address is required"));
        if (r.category() == null || r.category().isBlank()) errors.add(new ApiException.FieldError("/category", "required", "category is required"));
        if (r.description() == null || r.description().strip().length() < 20)
            errors.add(new ApiException.FieldError("/description", "min_length", "description needs at least 20 characters"));
        List<String> urls = new ArrayList<>(r.evidenceUrls() == null ? List.of() : r.evidenceUrls());
        if (r.reference() != null && !r.reference().isBlank()) urls.add(r.reference());
        for (int i = 0; i < urls.size(); i++) {
            try {
                URI u = URI.create(urls.get(i));
                if (!"https".equalsIgnoreCase(u.getScheme()) && !"http".equalsIgnoreCase(u.getScheme())) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                errors.add(new ApiException.FieldError("/evidenceUrls/" + i, "invalid_url", "must be an http(s) URL"));
            }
        }
        if (!errors.isEmpty()) throw ApiException.validation("label report is invalid", errors);
        Network n = parseNetwork(r.network());
        String category = ApiMapper.category(r.category()).name();
        var saved = reports.submit(n.id(), r.address().trim(), category, r.description().strip(), urls, auth.getName());
        return reportDto(saved);
    }

    @GetMapping("/labels/reports")
    public CursorPage<Dto.LabelReportDto> reports(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return CursorPage.of(reports.list().stream().map(InvestigationController::reportDto).toList(), cursor, limit);
    }

    private static Dto.LabelReportDto reportDto(LabelReportStore.Report r) {
        return new Dto.LabelReportDto(r.id(), r.network(), r.address(), r.category().toLowerCase().replace('_', '-'), r.description(),
                r.evidenceUrls(), r.evidenceUrls().isEmpty() ? null : r.evidenceUrls().getFirst(), r.status(), r.createdAt());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static Network parseNetwork(String id) {
        try {
            return Network.fromId(id.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown network '" + id + "'");
        }
    }

    static Instant instant(String v, String name) {
        if (v == null || v.isBlank()) return null;
        try {
            return Instant.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("'" + name + "' must be an RFC 3339 timestamp");
        }
    }
}
