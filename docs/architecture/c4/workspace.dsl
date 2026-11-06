workspace "OpenChain Intelligence" "Open source blockchain investigation, risk intelligence and programmable monitoring platform." {

    !identifiers hierarchical

    model {
        analyst = person "Analyst / Investigator" "Compliance analyst, investigator or security engineer."
        ruleAuthor = person "Rule author" "Writes and tests monitoring rules."
        admin = person "Administrator" "Operates the platform, manages tenants, keys and connectors."

        idp = softwareSystem "Identity Provider" "Any OIDC provider (reference: Keycloak)." "External"
        chainProviders = softwareSystem "Blockchain data providers" "TronGrid-compatible HTTP API, own java-tron node, EVM JSON-RPC endpoints." "External"
        labelSources = softwareSystem "Label sources" "Sanctions lists (e.g. OFAC SDN), community reports, partner feeds." "External"
        notificationTargets = softwareSystem "Notification targets" "Customer webhooks, SMTP, Slack, Telegram." "External"
        customerSystems = softwareSystem "Customer systems" "Back-office, case management, SIEM calling the API." "External"

        oci = softwareSystem "OpenChain Intelligence" "Indexes chains, detects signals, scores risk, evaluates rules and notifies." {
            spa = container "Web frontend" "Wallet/tx analysis, graph explorer, rule editor, alert triage." "Angular, Cytoscape.js, nginx" "Web"
            api = container "API (api role)" "REST API v1, authentication, RBAC, tenant isolation, investigation queries." "Java 21, Spring Boot"
            worker = container "Workers (worker role)" "Indexers, detection, scoring, rule evaluation, delivery." "Java 21, Spring Boot" {
                indexer = component "Indexer" "Polls heads, fetches transfers, checkpoints, handles reorgs." "oci-blockchain"
                providerRegistry = component "Provider registry" "Failover, rate limiting, circuit breaking over BlockchainProvider SPI." "oci-blockchain"
                detection = component "Detection engine" "Produces explainable Signals." "oci-intelligence"
                scoring = component "Risk scoring" "Saturating aggregation with decay; emits RISK_SCORE_CHANGED." "oci-intelligence"
                eventBus = component "Event bus" "In-process dispatch keyed by address; outbox for durable events." "oci-app"

                ruleRegistry = component "Rule registry" "Loads enabled rule versions per tenant; hot reload on change." "oci-alerting"
                ruleCompiler = component "Rule compiler" "Validates schema v1, compiles to closed operator tree, computes hash, enforces limits." "oci-alerting"
                ruleEvaluator = component "Rule evaluator" "Matches events, maintains sliding windows with watermarks." "oci-alerting"
                windowStore = component "Window state store" "Per (rule, groupKey) window contributions; retractable." "oci-alerting"
                deduplicator = component "Deduplicator / cooldown" "Dedup key + cooldown suppression, alert grouping." "oci-alerting"
                alertService = component "Alert lifecycle" "OPEN, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, FALSE_POSITIVE, RETRACTED." "oci-alerting"
                connectorRegistry = component "Connector registry" "Webhook (HMAC), email, Slack, Telegram connectors." "oci-alerting"
                deliveryWorker = component "Delivery worker" "Reads deliveries outbox, retries with backoff, SSRF guard, DLQ." "oci-alerting"
            }
            db = container "PostgreSQL" "System of record: tenants, rules, alerts, transfers (partitioned), signals, scores, outbox, audit." "PostgreSQL 17" "Database"
        }

        analyst -> oci.spa "Investigates wallets and triages alerts" "HTTPS"
        ruleAuthor -> oci.spa "Writes and dry-runs rules" "HTTPS"
        admin -> oci.spa "Administers" "HTTPS"
        customerSystems -> oci.api "Calls API v1 with API keys" "HTTPS/JSON"
        oci.spa -> oci.api "Calls" "HTTPS/JSON"
        oci.spa -> idp "Authenticates users" "OIDC Authorization Code + PKCE"
        oci.api -> idp "Validates tokens (JWKS)" "HTTPS"
        oci.api -> oci.db "Reads/writes" "JDBC"
        oci.worker -> oci.db "Reads/writes, leases, outbox" "JDBC"
        oci.worker -> chainProviders "Fetches blocks, logs, transactions" "HTTPS JSON-RPC / REST"
        oci.worker -> labelSources "Imports labels" "HTTPS"
        oci.worker -> notificationTargets "Delivers notifications" "HTTPS (HMAC), SMTP"

        oci.worker.indexer -> oci.worker.providerRegistry "Uses"
        oci.worker.providerRegistry -> chainProviders "Calls"
        oci.worker.indexer -> oci.worker.eventBus "Publishes ChainEvent"
        oci.worker.eventBus -> oci.worker.detection "Dispatches"
        oci.worker.detection -> oci.worker.eventBus "Publishes DETECTION_SIGNAL"
        oci.worker.eventBus -> oci.worker.scoring "Dispatches"
        oci.worker.scoring -> oci.worker.eventBus "Publishes RISK_SCORE_CHANGED"
        oci.worker.eventBus -> oci.worker.ruleEvaluator "Dispatches ChainEvent"
        oci.worker.ruleRegistry -> oci.worker.ruleCompiler "Compiles rule versions"
        oci.worker.ruleEvaluator -> oci.worker.ruleRegistry "Gets compiled rules"
        oci.worker.ruleEvaluator -> oci.worker.windowStore "Adds/retracts contributions"
        oci.worker.ruleEvaluator -> oci.worker.deduplicator "Emits matches"
        oci.worker.deduplicator -> oci.worker.alertService "Opens/updates alerts"
        oci.worker.alertService -> oci.db "Persists alerts + deliveries outbox (same tx)"
        oci.worker.deliveryWorker -> oci.db "Leases pending deliveries"
        oci.worker.deliveryWorker -> oci.worker.connectorRegistry "Resolves connector"
        oci.worker.deliveryWorker -> notificationTargets "Sends"
        oci.worker.windowStore -> oci.db "Checkpoints state"
    }

    views {
        systemContext oci "Context" {
            include *
            autoLayout lr
        }
        container oci "Containers" {
            include *
            autoLayout lr
        }
        component oci.worker "AlertingComponents" "Components of the alerting pipeline inside the worker." {
            include oci.worker.eventBus oci.worker.ruleRegistry oci.worker.ruleCompiler oci.worker.ruleEvaluator oci.worker.windowStore oci.worker.deduplicator oci.worker.alertService oci.worker.connectorRegistry oci.worker.deliveryWorker oci.db notificationTargets
            autoLayout lr
        }
        component oci.worker "WorkerComponents" "All worker components." {
            include *
            autoLayout lr
        }

        styles {
            element "Person" {
                shape Person
                background #1f4e79
                color #ffffff
            }
            element "Software System" {
                background #2e75b6
                color #ffffff
            }
            element "External" {
                background #7f7f7f
                color #ffffff
            }
            element "Container" {
                background #4a90d9
                color #ffffff
            }
            element "Component" {
                background #85bbf0
                color #000000
            }
            element "Database" {
                shape Cylinder
            }
            element "Web" {
                shape WebBrowser
            }
        }
    }
}
