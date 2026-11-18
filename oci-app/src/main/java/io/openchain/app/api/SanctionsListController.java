package io.openchain.app.api;

import io.openchain.app.config.OciProperties;
import io.openchain.app.sanctions.SanctionsListService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which official sanctions lists screening uses, of when, and whether they are current (ADR-0027). */
@RestController
@RequestMapping("/api/v1/labels/sanctions-lists")
public class SanctionsListController {
    private final SanctionsListService service;
    private final OciProperties props;

    public SanctionsListController(SanctionsListService service, OciProperties props) {
        this.service = service;
        this.props = props;
    }

    @GetMapping
    public Map<String, Object> list() {
        var s = service.status();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", s.source());
        item.put("state", s.state().name());
        item.put("fetchEnabled", props.getSanctions().isOfacEnabled() && !props.getDemo().isEnabled());
        item.put("issued", s.issued() == null ? null : s.issued().toString());
        item.put("fetchedAt", s.fetchedAt());
        item.put("checkedAt", s.checkedAt());
        item.put("tron", s.tron());
        item.put("ethereum", s.ethereum());
        item.put("lastError", s.lastError());
        return Map.of("items", List.of(item));
    }
}
