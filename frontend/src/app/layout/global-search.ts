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
      <svg class="ico" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5 14 14"/></svg>
      <input id="gsearch" type="search" autocomplete="off" spellcheck="false" placeholder="Search an address or transaction hash"
        [ngModel]="q()" (ngModelChange)="q.set($event)" name="q" aria-describedby="gsearch-hint" />
      <div id="gsearch-hint" class="hint" aria-live="polite">
        @if (q().trim()) {
          @if (det().kind === 'unknown') {
            <span class="bad">{{ det().reason }}</span>
          } @else {
            <span>{{ det().kind === 'address' ? 'Open address on' : 'Open transaction on' }}</span>
            @for (n of det().candidates; track n; let first = $first) {
              <button type="button" class="chip" [class.primary]="first" (click)="go(n)">{{ n }}</button>
            }
            @if (det().candidates.length > 1) { <span class="muted">This format is shared by several networks. Pick one.</span> }
          }
        }
      </div>
    </form>
  `,
  styles: `
    form { position: relative; }
    input { width: 100%; font-family: var(--mono); font-size: 13px; padding-left: 32px; background: var(--surface); }
    input::placeholder { font-family: var(--font); font-size: 14px; }
    .ico { position: absolute; left: 10px; top: 9px; width: 16px; height: 16px; color: var(--muted); pointer-events: none; }
    .hint { position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 30; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 13px; padding: 8px 10px;
      background: var(--surface); border: 1px solid var(--line); border-radius: 4px; box-shadow: var(--shadow-overlay); }
    .hint:empty { display: none; }
    .hint .chip { cursor: pointer; font: inherit; font-size: 12px; text-transform: capitalize; }
    .hint .chip.primary { border-color: var(--accent); color: var(--accent); }
    .bad { color: var(--danger); }
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
