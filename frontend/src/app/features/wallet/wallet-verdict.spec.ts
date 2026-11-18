import { WalletAnalysis } from '../../core/models';
import { walletVerdict } from './wallet-verdict';

const base = { address: 'TW', network: 'tron', labels: [], signals: [], risk: null,
  history: { message: 'Address history loaded from block 100 to 200.' } } as unknown as WalletAnalysis;

describe('walletVerdict', () => {
  it('shows a confirmed listing as confirmed and the exposure it causes as inferred', () => {
    const w = { ...base,
      labels: [{ category: 'SANCTIONED', source: 'ofac-sdn', evidenceLevel: 'CONFIRMED_BY_SOURCE', name: 'OFAC' }],
      signals: [{ type: 'SANCTIONED_EXPOSURE', severity: 'HIGH', evidenceLevel: 'CONFIRMED_BY_SOURCE', summary: 'Received 1,000 USDT from a sanctioned address.' }],
      risk: { level: 'HIGH' } } as unknown as WalletAnalysis;
    const v = walletVerdict(w);
    expect(v.sentence).toBe('This wallet is listed as sanctioned by ofac-sdn.');
    expect(v.risk).toBe('HIGH');
    expect(v.lines).toEqual([
      { level: 'CONFIRMED', text: 'Listed as sanctioned by ofac-sdn', detail: 'confirmed by source' },
      { level: 'INFERRED', text: 'Received 1,000 USDT from a sanctioned address.', detail: 'confirmed by source' },
    ]);
    expect(v.coverage).toBe('Address history loaded from block 100 to 200.');
  });

  it('says there is no indicator in the data covered, not that the wallet is clean', () => {
    const v = walletVerdict(base);
    expect(v.sentence).toBe('No risk indicator in the data covered.');
    expect(v.lines).toEqual([]);
  });

  it('names the strongest signal when there is no listing', () => {
    const w = { ...base, signals: [
      { type: 'FAST_FORWARDING', severity: 'LOW', evidenceLevel: 'ANOMALY', summary: 'a', title: 'Fast forwarding' },
      { type: 'RAPID_DRAIN', severity: 'HIGH', evidenceLevel: 'RISK_SIGNAL', summary: 'b', title: 'Rapid drain' }] } as unknown as WalletAnalysis;
    expect(walletVerdict(w).sentence).toBe('2 risk signals on this wallet; the strongest: rapid drain.');
  });

  it('names an unconfirmed label instead of saying there is no indicator', () => {
    const w = { ...base, labels: [{ category: 'SCAM', source: 'community', evidenceLevel: 'RISK_SIGNAL', name: 'x' }] } as unknown as WalletAnalysis;
    expect(walletVerdict(w).sentence).toBe('This wallet is labelled scam by community (not confirmed by the source).');
  });
});
