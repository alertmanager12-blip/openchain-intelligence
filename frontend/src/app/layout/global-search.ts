import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NetworkId } from '../core/models';
import { detectQuery } from '../core/network-detect';

/** Global search: address or transaction hash; the network is inferred from the format. */
@Component({
  selector: 'oci-global-search',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form role="search" (submit)="$event.preventDefault(); go()">
      <label class="sr-only" for="gsearch">Search an address or transaction hash</label>
      <input id="gsearch" type="search" autocomplete="off" spellcheck="false" placeholder="Search address or tx hash (0x…, T…, bc1…)"
        [ngModel]="q()" (ngModelChange)="q.set($event)" name="q" aria-describedby="gsearch-hint" />
      <div id="gsearch-hint" class="hint" aria-live="polite">
        @if (q().trim()) {
          @if (det().kind === 'unknown') {
            <span class="bad">{{ det().reason }}</span>
          } @else {
            <span>{{ det().kind === 'address' ? 'Address' : 'Transaction' }} ·</span>
            @for (n of det().candidates; track n; let first = $first) {
              <button type="button" class="chip" [class.primary]="first" (click)="go(n)">{{ n }}</button>
            }
            @if (det().candidates.length > 1) { <span class="muted">format shared by several networks: pick one</span> }
          }
        }
      </div>
    </form>
  `,
  styles: `
    form { position: relative; }
    input { width: 100%; font-family: var(--mono); font-size: 13px; }
    .hint { position: absolute; top: 100%; left: 0; right: 0; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 12px; padding-top: 4px; }
    .hint:empty { display: none; }
    .hint .chip { cursor: pointer; font: inherit; font-size: 11.5px; }
    .hint .chip.primary { border-color: var(--accent); color: var(--accent); }
    .bad { color: var(--danger); background: var(--surface); padding: 0 4px; }
    .hint > span:not(.bad) { background: var(--surface); padding: 0 4px; border-radius: 3px; }
  `,
})
export class GlobalSearch {
  private readonly router = inject(Router);
  readonly q = signal('');
  readonly det = computed(() => detectQuery(this.q()));

  go(network?: NetworkId): void {
    const d = this.det();
    const n = network ?? d.candidates[0];
    if (!n || d.kind === 'unknown') return;
    if (d.kind === 'address') this.router.navigate(['/wallets', n, d.normalized]);
    else this.router.navigate(['/explorer'], { queryParams: { q: d.normalized, network: n } });
    this.q.set('');
  }
}
