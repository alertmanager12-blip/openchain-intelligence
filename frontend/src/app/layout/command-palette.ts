import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, model, signal, untracked, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { ApiService } from '../core/api.service';
import { NetworkId, Severity } from '../core/models';
import { detectQuery } from '../core/network-detect';
import { apiResource } from '../shared/api-resource';
import { shortHash } from '../shared/format';
import { NAV_ITEMS } from './navigation';

export interface PaletteOption {
  id: string;
  group: 'Go to address or transaction' | 'Pages' | 'Recent alerts';
  label: string;
  hint?: string;
  severity?: Severity;
  run: () => void;
}

const NET_LABEL: Record<NetworkId, string> = { ethereum: 'Ethereum', tron: 'TRON', bsc: 'BSC', polygon: 'Polygon', bitcoin: 'Bitcoin', solana: 'Solana' };

/**
 * Command palette (Ctrl+K / ⌘K): search an address or transaction hash (the network is detected from the format),
 * jump to a page or to a recent alert. ARIA combobox with a listbox popup (aria-activedescendant).
 */
@Component({
  selector: 'oci-command-palette',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (open()) {
      <div class="scrim" (click)="close()" aria-hidden="true"></div>
      <div class="palette" role="dialog" aria-modal="true" aria-label="Command palette" (keydown)="key($event)">
        <div class="field">
          <svg class="ico" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5 14 14"/></svg>
          <input #input type="text" role="combobox" aria-autocomplete="list" aria-expanded="true" aria-controls="palette-list" spellcheck="false" autocomplete="off"
            [attr.aria-activedescendant]="activeId()" aria-label="Search an address, a transaction hash, a page or an alert"
            placeholder="Address, transaction hash, page or alert" [value]="q()" (input)="setQuery($any($event.target).value)" />
          <kbd>Esc</kbd>
        </div>
        @if (detection(); as d) { <p class="det" [class.bad]="d.kind === 'unknown'">{{ d.reason }}</p> }
        <div class="list" id="palette-list" role="listbox" aria-label="Results">
          @for (g of groups(); track g.name) {
            <div role="group" [attr.aria-labelledby]="'pg-' + $index">
              <div class="g" [id]="'pg-' + $index" role="presentation">{{ g.name }}</div>
              @for (o of g.options; track o.id) {
                <div class="opt" role="option" [id]="o.id" [attr.aria-selected]="o.id === activeId()" (click)="choose(o)" (mousemove)="hover(o.id)">
                  @if (o.severity) { <span class="badge sev-{{ o.severity.toLowerCase() }}">{{ o.severity.charAt(0) + o.severity.slice(1).toLowerCase() }}</span> }
                  <span class="l">{{ o.label }}</span>
                  @if (o.hint) { <span class="h" [class.mono]="o.group !== 'Pages'">{{ o.hint }}</span> }
                </div>
              }
            </div>
          } @empty {
            <p class="none" role="status">Nothing matches. Paste a full address or transaction hash, or type a page name.</p>
          }
        </div>
        <div class="foot" aria-hidden="true"><span><kbd>↑</kbd><kbd>↓</kbd> move</span><span><kbd>Enter</kbd> open</span></div>
      </div>
    }
  `,
  styles: `
    .scrim { position: fixed; inset: 0; z-index: 80; background: var(--scrim); }
    .palette { position: fixed; z-index: 81; top: min(14vh, 120px); left: 50%; transform: translateX(-50%); width: min(640px, calc(100vw - 24px));
      background: var(--surface); border: 1px solid var(--line); border-radius: var(--r-panel); box-shadow: var(--shadow-overlay); overflow: hidden; animation: pop 0.14s var(--ease); }
    @keyframes pop { from { opacity: 0; transform: translateX(-50%) scale(0.98); } }
    .field { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--line-soft); }
    .ico { width: 18px; height: 18px; color: var(--muted); flex: none; }
    input { flex: 1; border: 0; box-shadow: none; background: transparent; font-size: 17px; padding: 4px 0; min-width: 0; }
    input:focus { box-shadow: none; outline: none; }
    .det { margin: 0; padding: 8px 16px; font-size: 13px; color: var(--ink-2); background: var(--surface-2); border-bottom: 1px solid var(--line-soft); }
    .det.bad { color: var(--warn); }
    .list { max-height: min(56vh, 440px); overflow-y: auto; padding: 6px; }
    .g { font: 600 12px var(--font-display); color: var(--muted); padding: 10px 10px 4px; letter-spacing: 0.02em; }
    .opt { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: var(--r-control); cursor: pointer; min-width: 0; }
    .opt[aria-selected='true'] { background: var(--accent-tint); }
    .l { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .h { color: var(--muted); font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 45%; }
    .none { padding: 16px 10px; color: var(--ink-2); margin: 0; }
    .foot { display: flex; gap: 16px; padding: 8px 16px; border-top: 1px solid var(--line-soft); font-size: 12px; color: var(--muted); }
    .foot kbd { margin-right: 3px; }
  `,
})
export class CommandPalette {
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  readonly open = model(false);
  private readonly input = viewChild<ElementRef<HTMLInputElement>>('input');
  readonly q = signal('');
  readonly active = signal(0);
  private returnFocus: HTMLElement | null = null;

  /** Recent alerts, fetched only while the palette is open. */
  private readonly alerts = apiResource({ params: () => (this.open() ? { limit: 6 } : undefined), stream: (p) => this.api.alerts(p) });

  readonly detection = computed(() => {
    const t = this.q().trim();
    if (t.length < 20) return null;
    return detectQuery(t);
  });

  readonly options = computed<PaletteOption[]>(() => {
    const t = this.q().trim().toLowerCase();
    const out: PaletteOption[] = [];
    const d = this.detection();
    if (d && d.kind !== 'unknown') {
      for (const n of d.candidates) {
        out.push({
          id: `po-go-${n}`, group: 'Go to address or transaction',
          label: `${d.kind === 'address' ? 'Open address' : 'Open transaction'} on ${NET_LABEL[n]}`, hint: shortHash(d.normalized, 10, 8),
          run: () => (d.kind === 'address'
            ? this.router.navigate(['/wallets', n, d.normalized])
            : this.router.navigate(['/explorer'], { queryParams: { q: d.normalized, network: n } })),
        });
      }
    }
    for (const p of NAV_ITEMS) {
      if (!t || `${p.label} ${p.keywords ?? ''}`.toLowerCase().includes(t)) {
        out.push({ id: `po-page-${p.path.replace(/\W/g, '') || 'home'}`, group: 'Pages', label: p.label, run: () => this.router.navigateByUrl(p.path) });
      }
    }
    for (const a of this.alerts.value()?.items ?? []) {
      if (!t || `${a.ruleName} ${a.id} ${a.address ?? ''}`.toLowerCase().includes(t)) {
        out.push({ id: `po-al-${a.id}`, group: 'Recent alerts', label: a.ruleName, hint: a.id, severity: a.severity, run: () => this.router.navigate(['/alerts'], { queryParams: { id: a.id } }) });
      }
    }
    return out;
  });

  readonly groups = computed(() => {
    const m = new Map<string, PaletteOption[]>();
    for (const o of this.options()) m.set(o.group, [...(m.get(o.group) ?? []), o]);
    return [...m.entries()].map(([name, options]) => ({ name, options }));
  });
  readonly activeId = computed(() => this.options()[Math.min(this.active(), this.options().length - 1)]?.id ?? null);

  constructor() {
    effect(() => {
      if (!this.open()) return;
      untracked(() => {
        this.returnFocus = document.activeElement as HTMLElement | null;
        this.q.set('');
        this.active.set(0);
      });
    });
    effect(() => this.input()?.nativeElement.focus());
  }

  setQuery(v: string): void {
    this.q.set(v);
    this.active.set(0);
  }

  hover(id: string): void {
    const i = this.options().findIndex((o) => o.id === id);
    if (i >= 0) this.active.set(i);
  }

  choose(o: PaletteOption): void {
    this.close(false);
    o.run();
  }

  close(restoreFocus = true): void {
    this.open.set(false);
    if (restoreFocus) this.returnFocus?.focus?.();
  }

  key(e: KeyboardEvent): void {
    const n = this.options().length;
    if (e.key === 'Escape') { e.preventDefault(); this.close(); }
    else if (e.key === 'ArrowDown' && n) { e.preventDefault(); this.active.set((this.active() + 1) % n); this.scroll(); }
    else if (e.key === 'ArrowUp' && n) { e.preventDefault(); this.active.set((this.active() - 1 + n) % n); this.scroll(); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      const o = this.options()[this.active()];
      if (o) this.choose(o);
    } else if (e.key === 'Tab') e.preventDefault(); // focus stays in the combobox (modal dialog)
  }

  private scroll(): void {
    const id = this.activeId();
    if (id) queueMicrotask(() => document.getElementById(id)?.scrollIntoView?.({ block: 'nearest' }));
  }
}
