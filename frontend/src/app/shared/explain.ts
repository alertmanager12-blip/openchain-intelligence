import { RiskContribution, Signal } from '../core/models';
import { sentence } from './format';

/**
 * Plain-language names used when a payload carries no `title` / `summary` (servers older than the explainability
 * fields). Mirrors oci-core SignalText so a fallback reads the same as the server's wording.
 */
const SIGNAL_TITLES: Record<string, string> = {
  UNUSUAL_AMOUNT: 'Unusual amount',
  STRUCTURING: 'Possible structuring',
  FAST_FORWARDING: 'Fast forwarding',
  DORMANT_REACTIVATION: 'Dormant wallet reactivated',
  CIRCULAR_FLOW: 'Circular flow',
  FLOW_CONCENTRATION: 'Fan-out to many recipients',
  RAPID_DRAIN: 'Rapid drain',
  SANCTIONED_EXPOSURE: 'Direct exposure to a sanctioned address',
  FLAGGED_EXPOSURE: 'Direct exposure to a flagged address',
  INDIRECT_EXPOSURE: 'Indirect exposure to a listed address',
  MIXER_EXPOSURE: 'Direct exposure to a mixer',
  SCAM_EXPOSURE: 'Direct exposure to a scam-reported address',
  DUSTING: 'Dusting from a listed address',
  OWNERSHIP_CHANGE: 'Contract ownership change',
  UNUSUAL_APPROVAL: 'Unlimited token approval',
};

const LABEL_CATEGORIES: Record<string, string> = {
  SANCTIONED: 'sanctioned', MIXER: 'a mixer', SCAM: 'a scam', PHISHING: 'phishing', RANSOMWARE: 'ransomware',
  STOLEN_FUNDS: 'holding stolen funds', DARKNET_MARKET: 'a darknet market', FLAGGED_COMMUNITY: 'flagged by the community',
  ISSUER_FROZEN: 'frozen by the stablecoin issuer', EXCHANGE: 'an exchange', BRIDGE: 'a bridge', DEFI_PROTOCOL: 'a DeFi protocol',
};

/** Plain-language name of a signal type ("RAPID_DRAIN" -> "Rapid drain"). */
export function signalTypeTitle(type: string): string {
  return SIGNAL_TITLES[type] ?? sentence(type);
}

/** Plain-language name of a label category factor ("SANCTIONED" -> "Listed as sanctioned"). */
export function labelTitle(category: string): string {
  const key = category.toUpperCase();
  if (key === 'ISSUER_FROZEN') return 'Frozen by the stablecoin issuer';
  return 'Listed as ' + (LABEL_CATEGORIES[key] ?? key.toLowerCase().replace(/_/g, ' '));
}

/** Name of a risk factor key: "signal:RAPID_DRAIN", "label:SANCTIONED" or a bare key. */
export function factorTitle(factor: string): string {
  const i = factor.indexOf(':');
  const kind = i >= 0 ? factor.slice(0, i) : '';
  const key = i >= 0 ? factor.slice(i + 1) : factor;
  if (kind === 'label') return labelTitle(key);
  return signalTypeTitle(key);
}

export function contributionTitle(c: Pick<RiskContribution, 'factor' | 'title'>): string {
  return c.title?.trim() || factorTitle(c.factor);
}

/** The plain sentence of a contribution, or null when the server sent none (the explanation then stays technical). */
export function contributionSummary(c: Pick<RiskContribution, 'summary'>): string | null {
  return c.summary?.trim() || null;
}

export function signalTitle(s: Pick<Signal, 'type' | 'title'>): string {
  return s.title?.trim() || signalTypeTitle(s.type);
}

/** Server summary, else the first evidence description (older payloads). */
export function signalSummary(s: Pick<Signal, 'summary' | 'evidence'>): string | null {
  return s.summary?.trim() || s.evidence?.[0]?.description || null;
}
