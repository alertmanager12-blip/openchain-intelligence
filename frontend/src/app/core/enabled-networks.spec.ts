import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ApiService } from './api.service';
import { EnabledNetworks } from './enabled-networks';
import { NetworkStatus } from './models';

function status(network: NetworkStatus['network'], enabled = true): NetworkStatus {
  return { network, headBlock: 1, finalizedBlock: 1, healthy: true, providerId: 'p', latencyMillis: 1, checkedAt: '2026-11-07T00:00:00Z', detail: null, enabled };
}

function setup(networks: () => ReturnType<ApiService['networks']>): EnabledNetworks {
  TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: { networks } }] });
  return TestBed.inject(EnabledNetworks);
}

describe('EnabledNetworks', () => {
  it('offers only enabled networks for an ambiguous EVM address, most likely first', () => {
    const n = setup(() => of([status('tron'), status('ethereum'), status('bsc', false)]));
    expect(n.enabled()).toEqual(['tron', 'ethereum']);
    expect(n.enabledCandidates(['ethereum', 'bsc', 'polygon'])).toEqual(['ethereum']);
    expect(n.disabledCandidates(['ethereum', 'bsc', 'polygon'])).toEqual(['bsc', 'polygon']);
  });

  it('returns no candidate when the format only matches disabled networks', () => {
    const n = setup(() => of([status('tron')]));
    expect(n.enabledCandidates(['ethereum', 'bsc', 'polygon'])).toEqual([]);
  });

  it('does not filter while the list is unknown (the call failed)', () => {
    const n = setup(() => throwError(() => new Error('offline')));
    expect(n.enabled()).toBeNull();
    expect(n.enabledCandidates(['ethereum', 'bsc'])).toEqual(['ethereum', 'bsc']);
  });
});
