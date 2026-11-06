package io.openchain.app.persistence;

/**
 * Publicly known, DEVELOPMENT-ONLY secrets. They let the Docker Compose demo run with a persistent database out of
 * the box; the application refuses them when demo mode is off ({@code OCI_DEMO_MODE=false}).
 */
public final class DevDefaults {

    private DevDefaults() {}

    /** base64("oci-dev-only-master-key-00000000"), the default of OCI_CRYPTO_MASTER_KEY in docker-compose.yml. */
    public static final String DEV_MASTER_KEY = "b2NpLWRldi1vbmx5LW1hc3Rlci1rZXktMDAwMDAwMDA=";

    /** API key pepper used in demo mode with PostgreSQL when OCI_SECURITY_API_KEY_PEPPER is not set. */
    public static final String DEV_API_KEY_PEPPER = "oci-demo-only-api-key-pepper-NOT-A-SECRET";
}
