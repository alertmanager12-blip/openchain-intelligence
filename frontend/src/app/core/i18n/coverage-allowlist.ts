/**
 * Console sources that still carry hard-coded English (spec 2026-11-19 §6). The list only shrinks: i18n-coverage.spec.ts
 * fails when a file listed here no longer has a finding, and when an unlisted file gains one.
 */
export const COVERAGE_ALLOWLIST: { files: string[]; tokens: RegExp[] } = {
  files: [
    'features/community/community.ts',
    'features/explorer/explorer.ts',
    'features/integrations/integrations.html',
    'features/investigation/graph-canvas.ts',
    'features/investigation/graph-inspector.ts',
    'features/investigation/investigation.ts',
    'features/investigations/investigations.ts',
    'features/landing/capabilities.ts',
    'features/landing/closing.ts',
    'features/landing/developers.ts',
    'features/landing/hero-trace.ts',
    'features/landing/hero.ts',
    'features/landing/how-it-works.ts',
    'features/landing/investigation-preview.ts',
    'features/landing/landing.ts',
    'features/predictions/predictions.ts',
    'features/rule-builder/actions-step.ts',
    'features/rule-builder/condition-group.ts',
    'features/rule-builder/rule-builder.ts',
    'features/rule-builder/scope-step.ts',
    'features/rule-builder/window-step.ts',
    'features/rule-code/rule-code.ts',
    'features/rule-code/stored-rules.ts',
    'features/settings/settings.html',
    'features/simulator/simulator.ts',
    'features/traces/traces.ts',
    'features/watchlist/watchlist.ts',
  ],
  tokens: [
    /^[A-Z0-9_.:\-/+]+$/,
    /^(ChainSentinel|OFAC|SDN|TRON|Ethereum|YAML|JSON|API|OIDC|USDT|USDC|ETH|TRX|BTC|CSV|URL|IDs?|Enter|Esc|Escape|Shift|Ctrl|Cmd|Alt|Tab|Bearer|Gate)$/,
  ],
};
