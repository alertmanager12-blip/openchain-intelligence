package io.openchain.app.api;

import io.openchain.app.analysis.AddressCoverage;
import io.openchain.app.analysis.InvestigationService;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.simulation.WhatIf;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulations (ADR-0023 amendment 2): results of an explicit hypothesis, level SIMULATED, computed on request and never
 * stored nor mixed with observed or inferred facts. Every response carries its hypothesis and its level.
 */
@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationController {
    private final InvestigationService investigation;

    public SimulationController(InvestigationService investigation) { this.investigation = investigation; }

    public record HypothesisDto(String network, String address, String category) {}

    public record ExposedDto(String address, int hops, String level, List<String> txHashes, double confidence, List<String> alreadyExposedTo) {}

    /**
     * @param coverage   coverage of the hypothetical address; with anything but COMPLETE, an empty list means "nobody in the
     *                   covered data", never "nobody"
     * @param truncated  the candidate limit was reached: the list may be incomplete
     */
    public record WhatIfDto(String level, HypothesisDto hypothesis, String statement, String coverage, List<String> coverageReasons,
                            int candidates, boolean truncated, List<ExposedDto> exposed) {}

    @GetMapping("/exposure")
    public WhatIfDto exposure(@RequestParam String network, @RequestParam String address, @RequestParam String category,
                              @RequestParam(defaultValue = "2") int depth) {
        if (depth < 1 || depth > 4) throw ApiException.badRequest(ApiCode.PARAM_OUT_OF_RANGE, Map.of("param", "depth", "min", 1, "max", 4));
        Network n = investigation.network(network);
        Address x = investigation.address(n, address);
        LabelCategory c = ApiMapper.category(category);
        if (!c.risky()) throw ApiException.badRequest(ApiCode.SIMULATION_NO_EXPOSURE, Map.of("category", c));
        WhatIf.Result r = investigation.whatIf(x, c, depth);
        AddressCoverage.Result cov = investigation.addressCoverage(x);
        String statement = r.exposed().isEmpty()
                ? (cov.state() == AddressCoverage.State.COMPLETE && !r.truncated()
                    ? "Simulation: no wallet would be exposed to this address."
                    : "Simulation: no wallet would be exposed in the data covered; this is not proof that none would be.")
                : "Simulation: " + r.exposed().size() + " wallet(s) would be exposed if this address were listed as "
                    + c.name().toLowerCase().replace('_', ' ') + ". This is a hypothesis, not a listing.";
        return new WhatIfDto(r.level(), new HypothesisDto(n.id(), x.value(), c.name()), statement, cov.state().name(), cov.reasons(),
                r.candidates(), r.truncated(), r.exposed().stream().map(e -> new ExposedDto(e.address().value(), e.hops(), e.level(), e.txHashes(),
                        e.confidence(), e.alreadyExposedTo())).toList());
    }
}
