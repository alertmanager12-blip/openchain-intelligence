package io.openchain.app.api;

import io.openchain.app.chain.ChainIngestionService;
import io.openchain.core.detection.Signal;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.flow.NativeTokens;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Domain -> wire conversions shared by the controllers. */
public final class ApiMapper {

    private ApiMapper() {}

    public static Dto.TokenDto token(Token t) { return new Dto.TokenDto(t.network().id(), t.contract(), t.symbol(), t.decimals()); }

    public static String raw(BigInteger v) { return v == null ? "0" : v.toString(); }

    public static String amount(BigInteger raw, int decimals) {
        return raw == null ? "0" : new BigDecimal(raw, decimals).stripTrailingZeros().toPlainString();
    }

    public static Dto.SignalDto signal(Signal s) {
        return new Dto.SignalDto(s.id(), s.type().name(), s.address().value(), s.address().network().id(), s.txHash(),
                s.severity().name(), s.confidence(), s.evidenceLevel().name(),
                s.evidence().stream().map(e -> new Dto.EvidenceDto(e.kind(), e.description(), e.reference(), e.source(), e.observedAt())).toList(),
                s.detectorId(), s.detectedAt(), s.title(), s.summary());
    }

    /**
     * @param evidenceLevelOf resolves the evidence level of a contribution (from its signal or label)
     */
    public static Dto.RiskScoreDto risk(RiskScore r, List<RiskScore> history, Function<RiskScore.Contribution, String> evidenceLevelOf) {
        return new Dto.RiskScoreDto(r.address().value(), r.address().network().id(), r.score(), r.level().name(),
                r.contributions().stream().map(c -> new Dto.ContributionDto(c.factor(), c.signalId(), c.source(), round(c.weight()),
                        round(c.confidence()), c.ageSeconds(), round(c.points()), c.explanation(), evidenceLevelOf.apply(c),
                        c.title(), c.summary())).toList(),
                r.method(), r.modelVersion(), r.computedAt(),
                history == null ? null : history.stream().map(h -> new Dto.RiskHistoryDto(h.computedAt(), h.score(), h.level().name())).toList(),
                io.openchain.intelligence.risk.RiskScoringEngine.METHOD_SUMMARY);
    }

    public static Dto.LabelDto label(AddressLabel l) {
        String id = UUID.nameUUIDFromBytes(l.key().getBytes(StandardCharsets.UTF_8)).toString();
        return new Dto.LabelDto(id, l.address().value(), l.address().network().id(), ChainIngestionService.categoryId(l.category()),
                l.name() == null ? l.category().name() : l.name(), l.source(), l.sourceReliability(), l.evidenceLevel().name(),
                l.observedAt(), l.reference(), "PUBLISHED");
    }

    public static LabelCategory category(String s) {
        try {
            return LabelCategory.valueOf(s.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown label category '" + s + "'");
        }
    }

    public static Dto.TxDto tx(ChainTransaction t) {
        Token n = NativeTokens.of(t.network());
        return new Dto.TxDto(t.network().id(), t.hash(), t.blockNumber(), t.timestamp(), t.from() == null ? null : t.from().value(),
                t.to() == null ? null : t.to().value(), raw(t.value()), raw(t.fee()), t.success(), t.methodSelector(),
                t.contractInteraction(), n.symbol(), n.decimals());
    }

    public static Dto.TransferDto transfer(TokenTransfer t) {
        return new Dto.TransferDto(t.network().id(), t.txHash(), t.logIndex(), t.blockNumber(), t.timestamp(), t.from().value(),
                t.to().value(), token(t.token()), raw(t.rawAmount()), amount(t.rawAmount(), t.token().decimals()));
    }

    /** Event field values made JSON-safe: decimals as plain strings, instants as RFC 3339 strings. */
    public static Map<String, Object> jsonSafe(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, jsonValue(v)));
        return out;
    }

    public static Object jsonValue(Object v) {
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        if (v instanceof BigInteger bi) return bi.toString();
        if (v instanceof Instant i) return i.toString();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), jsonValue(x)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(ApiMapper::jsonValue).toList();
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Number) return v;
        return v.toString();
    }

    public static double round(double d) { return Math.round(d * 100) / 100.0; }
}
