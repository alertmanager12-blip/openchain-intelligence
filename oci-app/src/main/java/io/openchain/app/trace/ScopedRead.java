package io.openchain.app.trace;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Read-only work in one transaction with {@code oci.scope} set for row-level security. Plain JDBC on purpose: reads share
 * no code path with the writes (static analysis of GET endpoints, CodeQL "unprotected from CSRF", 2026-11-17).
 */
final class ScopedRead {
    private ScopedRead() {}

    static <T> T inScope(NamedParameterJdbcTemplate jdbc, String scope, ConnectionCallback<T> work) {
        return jdbc.getJdbcTemplate().execute((ConnectionCallback<T>) con -> {
            boolean auto = con.getAutoCommit();
            boolean ro = con.isReadOnly();
            con.setAutoCommit(false);
            con.setReadOnly(true);
            try {
                try (var ps = con.prepareStatement("select set_config('oci.scope', ?, true)")) {
                    ps.setString(1, scope);
                    ps.execute();
                }
                T r = work.doInConnection(con);
                con.commit();
                return r;
            } catch (java.sql.SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            } finally {
                con.setReadOnly(ro);
                con.setAutoCommit(auto);
            }
        });
    }
}
