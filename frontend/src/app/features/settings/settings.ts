import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FormField, form, required, submit, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { ApiError, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ApiKey, Role, User } from '../../core/models';
import { AuthMode, SettingsService } from '../../core/settings.service';
import { ThemePref, ThemeService } from '../../core/theme.service';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { PageHeader } from '../../shared/page-header';
import { TabDef, TabList } from '../../shared/tab-list';
import { UI, sentence } from '../../shared/ui';
import { environment } from '../../../environments/environment';

const SCOPES = ['rules:read', 'rules:write', 'alerts:read', 'alerts:write', 'wallets:read', 'labels:write'];
type Section = 'connection' | 'appearance' | 'keys' | 'users';

@Component({
  selector: 'oci-settings',
  imports: [FormsModule, FormField, PageHeader, TabList, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.html',
  styleUrl: './settings.scss',
})
export class SettingsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  readonly settings = inject(SettingsService);
  readonly theme = inject(ThemeService);
  readonly conn = this.settings.connection;
  /** Mock mode exists only in development builds. */
  readonly allowMock = environment.allowMock;
  readonly themes: ThemePref[] = ['system', 'light', 'dark'];
  readonly roles: Role[] = ['ADMIN', 'ANALYST', 'VIEWER'];
  readonly scopes = SCOPES;
  readonly sentence = sentence;
  readonly ago = timeAgo;

  readonly section = signal<Section>('connection');
  readonly sections: TabDef<Section>[] = [
    { id: 'connection', label: 'Connection' }, { id: 'appearance', label: 'Appearance' }, { id: 'keys', label: 'API keys' }, { id: 'users', label: 'Users and roles' },
  ];

  /** Bumped when the data source changes (mock toggle) so both lists reload. */
  private readonly source = signal(0);
  readonly users = apiResource({ params: () => ({ s: this.source() }), stream: () => this.api.users() });
  readonly keys = apiResource({ params: () => ({ s: this.source() }), stream: () => this.api.apiKeys() });

  readonly keyModel = signal({ name: '', scopes: ['alerts:read'] as string[] });
  readonly keyForm = form(this.keyModel, (p) => {
    required(p.name, { message: 'Name the key after what uses it, for example CI pipeline.' });
    validate(p.scopes, ({ value }) => (value().length ? undefined : { kind: 'scopes', message: 'Pick at least one scope.' }));
  });
  readonly newSecret = signal<string | null>(null);
  readonly keyError = signal<ApiError | null>(null);

  themeKey(e: KeyboardEvent): void {
    const i = this.themes.indexOf(this.theme.pref());
    const d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    const next = (i + d + this.themes.length) % this.themes.length;
    this.theme.pref.set(this.themes[next]);
    (e.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button')[next]?.focus();
  }

  toggleMock(): void {
    this.settings.update({ mock: !this.conn().mock });
    this.source.update((n) => n + 1);
    this.toasts.success(this.conn().mock ? 'Mock data turned on' : 'Mock data turned off');
  }

  setMode(m: AuthMode): void {
    this.settings.update({ authMode: m });
  }

  setRole(u: User, role: Role): void {
    this.api.updateUserRole(u.id, role).subscribe({
      next: (x) => {
        this.users.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y)));
        this.toasts.success('Role changed', `${x.name} is now ${sentence(x.role).toLowerCase()}`);
      },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }

  toggleScope(s: string): void {
    this.keyModel.update((m) => ({ ...m, scopes: m.scopes.includes(s) ? m.scopes.filter((x) => x !== s) : [...m.scopes, s] }));
  }

  createKey(e: Event): void {
    e.preventDefault();
    void submit(this.keyForm, async () => {
      this.keyError.set(null);
      try {
        const k = await firstValueFrom(this.api.createApiKey(this.keyModel().name.trim(), this.keyModel().scopes));
        this.newSecret.set(k.secret ?? null);
        this.keyModel.set({ name: '', scopes: ['alerts:read'] });
        this.keyForm().reset();
        const { secret: _omit, ...stored } = k;
        this.keys.update((l) => [stored, ...(l ?? [])]);
        this.toasts.success('Key created', k.name);
      } catch (err) {
        this.keyError.set(toApiError(err));
      }
    });
  }

  revoke(k: ApiKey): void {
    if (!confirm(`Revoke key "${k.name}"? Clients using it will be rejected immediately.`)) return;
    this.api.revokeApiKey(k.id).subscribe({
      next: (x) => {
        this.keys.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y)));
        this.toasts.success('Key revoked', k.name);
      },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }
}
