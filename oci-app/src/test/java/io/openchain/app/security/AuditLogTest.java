package io.openchain.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.openchain.app.api.ApiException;
import io.openchain.app.api.CursorPage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** CodeQL alerts on PR #18: log injection in the audit log, overflow of a forged pagination cursor. */
class AuditLogTest {

    @Test void aValueWithLineBreaksCannotForgeAnAuditLine() {
        Logger logger = (Logger) LoggerFactory.getLogger("oci.audit.test");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            new AuditLog(logger).info("watchlist.add name={} actor={}", "Hot\nrule.delete id=x actor=admin\r\t\u0007", null);
        } finally {
            logger.detachAppender(captured);
        }
        assertThat(captured.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("watchlist.add name=Hot\\nrule.delete id=x actor=admin\\r\\t\\u0007 actor=null");
    }

    @Test void ordinaryValuesAreUnchanged() {
        assertThat(AuditLog.oneLine("TQhbMYQdBr827qh1LU4Qf47RCKQXGnDpd5 é ü 漢")).isEqualTo("TQhbMYQdBr827qh1LU4Qf47RCKQXGnDpd5 é ü 漢");
        assertThat(AuditLog.oneLine("a b")).isEqualTo("a\\u2028b");
    }

    static String cursor(long offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    @Test void aForgedCursorNearTheIntegerLimitIsRefusedInsteadOfOverflowing() {
        assertThat(CursorPage.decode(cursor(400))).isEqualTo(400);
        assertThatThrownBy(() -> CursorPage.decode(cursor(Integer.MAX_VALUE))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CursorPage.decode(cursor(Integer.MAX_VALUE - CursorPage.MAX_LIMIT))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CursorPage.decode(cursor(-1))).isInstanceOf(ApiException.class);
    }
}
