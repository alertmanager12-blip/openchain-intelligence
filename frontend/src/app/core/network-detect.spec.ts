import { detectQuery } from './network-detect';

describe('detectQuery', () => {
  it('detects EVM addresses, lower-cases them and lists every EVM chain', () => {
    const r = detectQuery('  0xAbCdEf0123456789abcdef0123456789ABCDEF01 ');
    expect(r.kind).toBe('address');
    expect(r.candidates).toEqual(['ethereum', 'bsc', 'polygon']);
    expect(r.normalized).toBe('0xabcdef0123456789abcdef0123456789abcdef01');
  });

  it('detects EVM transaction hashes', () => {
    const r = detectQuery('0x' + 'a'.repeat(64));
    expect(r.kind).toBe('transaction');
    expect(r.candidates[0]).toBe('ethereum');
  });

  it('rejects malformed hex (wrong length, non-hex chars)', () => {
    expect(detectQuery('0x' + 'a'.repeat(39)).kind).toBe('unknown');
    expect(detectQuery('0x' + 'g'.repeat(40)).kind).toBe('unknown');
  });

  it('flags bare 64-hex as an ambiguous TRON/Bitcoin transaction id', () => {
    const r = detectQuery('b'.repeat(64));
    expect(r.kind).toBe('transaction');
    expect(r.candidates).toEqual(['tron', 'bitcoin']);
  });

  it('detects TRON addresses and keeps their case', () => {
    const r = detectQuery('TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t');
    expect(r).toMatchObject({ kind: 'address', candidates: ['tron'], normalized: 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t' });
  });

  it('rejects TRON-like strings containing non-base58 characters', () => {
    expect(detectQuery('TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj0O').candidates).not.toContain('tron');
  });

  it('detects Bitcoin bech32 and legacy addresses', () => {
    expect(detectQuery('bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq').candidates).toEqual(['bitcoin']);
    expect(detectQuery('1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2').candidates[0]).toBe('bitcoin');
  });

  it('rejects mixed-case bech32', () => {
    expect(detectQuery('bc1qAr0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq').candidates).not.toContain('bitcoin');
  });

  it('detects Solana public keys and signatures', () => {
    expect(detectQuery('9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM')).toMatchObject({ kind: 'address', candidates: ['solana'] });
    const sig = '5'.repeat(30) + 'Kx'.repeat(29);
    expect(sig.length).toBe(88);
    expect(detectQuery(sig)).toMatchObject({ kind: 'transaction', candidates: ['solana'] });
  });

  it('returns unknown for empty or free text', () => {
    expect(detectQuery('').kind).toBe('unknown');
    expect(detectQuery('hello world').kind).toBe('unknown');
  });
});
