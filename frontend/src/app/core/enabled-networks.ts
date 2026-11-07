import { Injectable, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, shareReplay } from 'rxjs';
import { ApiService } from './api.service';
import { NetworkId } from './models';

/**
 * Networks this instance actually indexes (GET /networks lists enabled networks only). An address format alone does not
 * say which network it belongs to (EVM chains share one format), so UI choices must be limited to enabled networks:
 * opening a wallet on a disabled one only returns "network not enabled on this instance".
 */
@Injectable({ providedIn: 'root' })
export class EnabledNetworks {
  private readonly api = inject(ApiService);

  /** Loaded once and shared; null while loading or when the call failed (then nothing is filtered). */
  private readonly list = toSignal(
    this.api.networks().pipe(
      catchError(() => of(null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    ),
    { initialValue: null },
  );

  /** Enabled network ids, or null when unknown. */
  readonly enabled = computed<NetworkId[] | null>(() => {
    const l = this.list();
    return l ? l.filter((n) => n.enabled !== false).map((n) => n.network) : null;
  });

  /**
   * The detected candidates restricted to enabled networks, keeping their order (most likely first).
   * When the enabled list is unknown, candidates are returned unchanged.
   */
  enabledCandidates(candidates: readonly NetworkId[]): NetworkId[] {
    const e = this.enabled();
    return e ? candidates.filter((c) => e.includes(c)) : [...candidates];
  }

  /** Candidates that exist for this format but are not enabled here (to explain why they are not offered). */
  disabledCandidates(candidates: readonly NetworkId[]): NetworkId[] {
    const e = this.enabled();
    return e ? candidates.filter((c) => !e.includes(c)) : [];
  }
}
