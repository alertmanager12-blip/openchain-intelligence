import { NetworkId } from './models';

export type QueryKind = 'address' | 'transaction' | 'unknown';

export interface DetectionResult {
  kind: QueryKind;
  /** Candidate networks, most likely first. Several candidates means the format alone is ambiguous. */
  candidates: NetworkId[];
  /** Canonical form of the input (trimmed; EVM values lower-cased, like io.openchain.core.model.Address). */
  normalized: string;
  /** Short analyst-facing explanation of why this was detected. */
  reason: string;
}

const B58 = '1-9A-HJ-NP-Za-km-z';
const EVM_ADDRESS = /^0x[0-9a-fA-F]{40}$/;
const EVM_TX = /^0x[0-9a-fA-F]{64}$/;
const HEX64 = /^[0-9a-fA-F]{64}$/;
const TRON_ADDRESS = new RegExp(`^T[${B58}]{33}$`);
const BTC_BECH32 = /^(bc1|tb1)[02-9ac-hj-np-z]{11,71}$/;
const BTC_LEGACY = new RegExp(`^[13][${B58}]{25,34}$`);
const SOLANA_ADDRESS = new RegExp(`^[${B58}]{32,44}$`);
const SOLANA_SIGNATURE = new RegExp(`^[${B58}]{86,88}$`);

const EVM_NETWORKS: NetworkId[] = ['ethereum', 'bsc', 'polygon'];

/**
 * Detects whether a search query is an address or a transaction hash and which networks it can belong to,
 * from its format only. Format never proves the network: EVM chains share one address format.
 */
export function detectQuery(raw: string): DetectionResult {
  const q = (raw ?? '').trim();
  if (!q) return { kind: 'unknown', candidates: [], normalized: '', reason: 'Empty query' };

  if (EVM_ADDRESS.test(q))
    return { kind: 'address', candidates: EVM_NETWORKS, normalized: q.toLowerCase(), reason: 'EVM address (0x + 40 hex): shared by Ethereum, BSC and Polygon' };
  if (EVM_TX.test(q))
    return { kind: 'transaction', candidates: EVM_NETWORKS, normalized: q.toLowerCase(), reason: 'EVM transaction hash (0x + 64 hex)' };
  if (HEX64.test(q))
    return { kind: 'transaction', candidates: ['tron', 'bitcoin'], normalized: q.toLowerCase(), reason: '64 hex characters without 0x: TRON or Bitcoin transaction id' };
  if (TRON_ADDRESS.test(q))
    return { kind: 'address', candidates: ['tron'], normalized: q, reason: 'TRON base58 address (T + 33 characters)' };
  if (BTC_BECH32.test(q.toLowerCase()) && (q === q.toLowerCase() || q === q.toUpperCase()))
    return { kind: 'address', candidates: ['bitcoin'], normalized: q.toLowerCase(), reason: 'Bitcoin bech32 address' };
  if (BTC_LEGACY.test(q))
    return { kind: 'address', candidates: q.length >= 32 ? ['bitcoin', 'solana'] : ['bitcoin'], normalized: q, reason: 'Bitcoin legacy/P2SH base58 address' };
  if (SOLANA_SIGNATURE.test(q))
    return { kind: 'transaction', candidates: ['solana'], normalized: q, reason: 'Solana transaction signature (base58, 86-88 characters)' };
  if (SOLANA_ADDRESS.test(q))
    return { kind: 'address', candidates: ['solana'], normalized: q, reason: 'Solana base58 public key (32-44 characters)' };

  return { kind: 'unknown', candidates: [], normalized: q, reason: 'Unrecognised format: expected an address or a transaction hash' };
}
