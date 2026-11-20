/**
 * Console sources that still carry hard-coded English (spec 2026-11-19 §6). The list only shrinks: i18n-coverage.spec.ts
 * fails when a file listed here no longer has a finding, and when an unlisted file gains one.
 */
export const COVERAGE_ALLOWLIST: { files: string[]; tokens: RegExp[] } = {
  files: [
    'features/landing/capabilities.ts',
    'features/landing/closing.ts',
    'features/landing/developers.ts',
    'features/landing/hero-trace.ts',
    'features/landing/hero.ts',
    'features/landing/how-it-works.ts',
    'features/landing/investigation-preview.ts',
    'features/landing/landing.ts',
    'features/settings/settings.html',
  ],
  tokens: [
    /^[A-Z0-9_.:\-/+]+$/,
    /^(ChainSentinel|OFAC|SDN|TRON|Ethereum|YAML|JSON|API|OIDC|USDT|USDC|ETH|TRX|BTC|CSV|URL|IDs?|Enter|Esc|Escape|Shift|Ctrl|Cmd|Alt|Tab|Bearer|Gate)$/,
  ],
};
