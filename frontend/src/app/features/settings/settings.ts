import { ChangeDetectionStrategy, Component, Injector, inject, signal } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
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
import { confirmAction } from '../../shared/confirm-dialog';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI, sentence } from '../../shared/ui';
import { environment } from '../../../environments/environment';

const SCOPES = ['rules:read', 'rules:write', 'alerts:read', 'alerts:write', 'wallets:read', 'labels:write'];

@Component({
  selector: 'oci-settings',
  imports: [FormField, PageHeader, MatTabsModule, MatSlideToggleModule, MatRadioModule, MatCheckboxModule, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
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

  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  /** Selected tab: Connection, Appearance, API keys, Users and roles. */
  readonly section = signal(0);
  readonly keyCols = ['name', 'key', 'scopes', 'used', 'actions'];
  readonly userCols = ['user', 'role', 'login'];
  readonly byId = (_: number, r: { id: string }) => r.id;

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

  async revoke(k: ApiKey): Promise<void> {
    const ok = await confirmAction(this.dialog, this.injector, { title: `Revoke key "${k.name}"?`, message: 'Clients using it will be rejected immediately. This cannot be undone.', confirm: 'Revoke key', danger: true });
    if (!ok) return;
    this.api.revokeApiKey(k.id).subscribe({
      next: (x) => {
        this.keys.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y)));
        this.toasts.success('Key revoked', k.name);
      },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }
}
