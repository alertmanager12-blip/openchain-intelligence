import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { ApiService } from '../core/api.service';
import { Dashboard } from '../core/models';
import { AuthMode, SettingsService, readStorage, writeStorage } from '../core/settings.service';

/** True when a key press is meant for a text field, a select or an editable element (single-key shortcuts must not fire). */
export function isTypingTarget(t: EventTarget | null): boolean {
  const el = t as HTMLElement | null;
  if (!el || !el.tagName) return false;
  return /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable || !!el.closest?.('[contenteditable="true"], mat-select, .mat-mdc-select-panel, [role="combobox"]');
}

const COLLAPSED_KEY = 'oci.sidebar.collapsed';
const REFRESH_MS = 60_000;

/** Where the console gets its data from, as shown in the sidebar's environment element. Never a workspace: there is none. */
export interface EnvironmentInfo {
  kind: 'mock' | 'demo' | 'api';
  label: string;
  detail: string;
}

/**
 * State of the application shell shared by the sidebar, the per-page top bar (shared/page-header.ts) and the pages:
 * sidebar collapse (persisted) and mobile drawer, command palette and shortcut help, plus the counts and environment
 * read from GET /dashboard (refreshed every minute and after alert changes).
 */
@Injectable({ providedIn: 'root' })
export class Shell {
  private readonly api = inject(ApiService);
  private readonly settings = inject(SettingsService);
  private readonly router = inject(Router);

  readonly collapsed = signal<boolean>(readStorage<{ v: boolean }>(COLLAPSED_KEY, { v: false }).v);
  readonly drawerOpen = signal(false);
  readonly palette = signal(false);
  readonly help = signal(false);
  readonly mac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

  private readonly dash = signal<Dashboard | null>(null);
  private timer: ReturnType<typeof setInterval> | null = null;

  readonly openAlerts = computed(() => this.dash()?.totals.openAlerts ?? null);
  readonly rulesEnabled = computed(() => this.dash()?.totals.rulesEnabled ?? null);
  readonly simulatedNetworks = computed(() => (this.dash()?.networks ?? []).filter((n) => n.simulated).length);
  readonly networkCount = computed(() => this.dash()?.networks.length ?? 0);

  readonly env = computed<EnvironmentInfo>(() => {
    if (environment.allowMock && this.settings.connection().mock) {
      return { kind: 'mock', label: 'Mock data', detail: 'Fictitious data generated in this browser. Development build only.' };
    }
    const sim = this.simulatedNetworks();
    if (sim > 0) {
      return { kind: 'demo', label: 'Demo', detail: `${sim} of ${this.networkCount()} networks read the simulated demo ledger, not a real chain.` };
    }
    return { kind: 'api', label: this.apiHost(), detail: `Backend at ${this.apiHost()}${environment.apiBaseUrl}` };
  });

  /** The stored credential, described without its secret: mode and the first characters of the key only. */
  readonly credential = computed(() => {
    const { authMode, token } = this.settings.connection();
    const mode: Record<AuthMode, string> = { none: 'No credential', apiKey: 'API key', bearer: 'Bearer token (OIDC)' };
    // at most 8 characters, and never more than a quarter of the value
    const prefix = token && authMode !== 'none' ? token.slice(0, Math.min(8, Math.floor(token.length / 4))) + '…' : null;
    return { mode: authMode, label: mode[authMode], prefix, signedIn: authMode !== 'none' && !!token };
  });

  constructor() {
    this.refresh();
    this.timer = setInterval(() => this.refresh(), REFRESH_MS);
    inject(DestroyRef).onDestroy(() => this.timer && clearInterval(this.timer));
  }

  /** Re-reads the counts (called after an alert changes status). Failures keep the last value: counts are optional. */
  refresh(): void {
    this.api.dashboard().subscribe({ next: (d) => this.dash.set(d), error: () => undefined });
  }

  toggleCollapsed(): void {
    this.collapsed.update((c) => !c);
    writeStorage(COLLAPSED_KEY, { v: this.collapsed() });
  }

  /** Clears the stored credential (never sent anywhere) and opens Settings, Connection. */
  signOut(): void {
    this.settings.update({ token: '' });
    void this.router.navigate(['/settings'], { queryParams: { section: 'connection' } });
  }

  private apiHost(): string {
    return typeof location !== 'undefined' ? location.host : 'this server';
  }
}
