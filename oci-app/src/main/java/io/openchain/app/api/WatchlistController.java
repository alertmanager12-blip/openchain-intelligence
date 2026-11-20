package io.openchain.app.api;

import io.openchain.app.analysis.InvestigationService;
import io.openchain.app.watchlist.WatchlistRepository;
import io.openchain.app.watchlist.WatchlistService;
import io.openchain.core.model.Address;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The organisation's own wallets. Behavioural rules of the pack (fund-splitting, suspicious-wallet, suspicious-approval)
 * only consider watched wallets and their direct counterparties as soon as the list is not empty.
 */
@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {
    private final WatchlistService watchlist;
    private final InvestigationService investigation;

    public WatchlistController(WatchlistService watchlist, InvestigationService investigation) {
        this.watchlist = watchlist;
        this.investigation = investigation;
    }

    public record AddRequest(String network, String address, String name) {}

    @GetMapping
    public List<WatchlistRepository.Entry> list() { return watchlist.list(); }

    @PostMapping
    public ResponseEntity<WatchlistRepository.Entry> add(@RequestBody AddRequest req, Authentication auth) {
        if (req == null || req.network() == null || req.address() == null) throw ApiException.badRequest(ApiCode.WATCHLIST_FIELDS_REQUIRED);
        if (req.name() != null && req.name().length() > 120) throw ApiException.badRequest(ApiCode.WATCHLIST_NAME_TOO_LONG, Map.of("max", 120));
        Address a = investigation.address(investigation.network(req.network()), req.address()); // validated, canonical
        return watchlist.add(a, req.name(), auth == null ? null : auth.getName())
                .map(e -> ResponseEntity.status(HttpStatus.CREATED).body(e))
                .orElseThrow(() -> ApiException.conflict(ApiCode.WATCHLIST_DUPLICATE));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable String id, Authentication auth) {
        if (!watchlist.remove(id, auth == null ? null : auth.getName())) throw ApiException.notFound(ApiCode.WATCHLIST_NOT_FOUND, Map.of("id", id));
        return ResponseEntity.noContent().build();
    }
}
