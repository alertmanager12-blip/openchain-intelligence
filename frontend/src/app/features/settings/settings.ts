import { ChangeDetectionStrategy, Component, Injector, inject, input, linkedSignal, signal } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { FormField, form, required, submit, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { ApiError, apiErrorText, errorText, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { I18nService, Key, Lang, hasKey } from '../../core/i18n/i18n.service';
import { ApiKey, Role, User } from '../../core/models';
import { AuthMode, SettingsService } from '../../core/settings.service';
import { ThemePref, ThemeService } from '../../core/theme.service';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { sanctionsSentence } from '../../shared/sanctions-text';
import { timeAgo } from '../../shared/format';
import { confirmAction } from '../../shared/confirm-dialog';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';
import { environment } from '../../../environments/environment';

type Section = 'connection' | 'appearance' | 'keys' | 'users';

// The backend's permissions (Scopes.java): an unknown one is rejected when the key is created.
const SCOPES = ['wallet:read', 'transaction:read', 'risk:read', 'investigation:read', 'investigation:write', 'investigation:review', 'alerts:read', 'alerts:write', 'rules:read',
  'rules:write', 'webhooks:read', 'webhooks:write', 'labels:report', 'watchlist:write', 'keys:admin'];

@Component({
  selector: 'oci-settings',
  imports: [FormField, PageHeader, MatSlideToggleModule, MatRadioModule, MatCheckboxModule, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
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
  protected readonly i18n = inject(I18nService);
  readonly langs: Lang[] = ['fr', 'en'];
  protected readonly langKey: Record<Lang, Key> = { fr: 'settings.language.fr', en: 'settings.language.en' };
  protected readonly themeKey: Record<ThemePref, Key> = { system: 'settings.theme.system', light: 'settings.theme.light', dark: 'settings.theme.dark' };
  protected readonly roleDesc: Record<Role, Key> = { ADMIN: 'settings.role.ADMIN.desc', ANALYST: 'settings.role.ANALYST.desc', VIEWER: 'settings.role.VIEWER.desc' };
  protected roleName(r: Role): string { return this.i18n.t(`settings.role.${r}` as Key); }
  protected apiError(e: ApiError): string { return apiErrorText(e, this.i18n.lang()); }
  /** Validation messages are dictionary keys, resolved here so they follow a language switch. */
  protected errText(m: { message?: string } | undefined): string { return m?.message && hasKey(m.message) ? this.i18n.t(m.message) : this.i18n.t('settings.keys.errInvalid'); }
  ago(iso: string | null): string { return timeAgo(iso, Date.now(), this.i18n.lang()); }

  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  /** Section from the URL (?section=keys, from the account menu); the sub-navigation changes it in place. */
  readonly sectionParam = input<string | undefined>(undefined, { alias: 'section' });
  readonly sections: { id: Section; label: Key }[] = [
    { id: 'connection', label: 'settings.tab.connection' }, { id: 'appearance', label: 'settings.tab.appearance' }, { id: 'keys', label: 'settings.tab.keys' }, { id: 'users', label: 'settings.tab.users' },
  ];
  readonly section = linkedSignal<Section>(() => (this.sections.some((x) => x.id === this.sectionParam()) ? (this.sectionParam() as Section) : 'connection'));
  readonly keyCols = ['name', 'key', 'scopes', 'used', 'actions'];
  readonly userCols = ['user', 'role', 'login'];
  readonly byId = (_: number, r: { id: string }) => r.id;

  /** Bumped when the data source changes (mock toggle) so both lists reload. */
  private readonly source = signal(0);
  /** Official sanctions lists in force (ADR-0027). */
  readonly sanctions = apiResource({ params: () => ({ s: this.source() }), stream: () => this.api.sanctionsLists() });
  readonly sanctionsSentence = sanctionsSentence;
  readonly users = apiResource({ params: () => ({ s: this.source() }), stream: () => this.api.users() });
  readonly keys = apiResource({ params: () => ({ s: this.source() }), stream: () => this.api.apiKeys() });

  readonly keyModel = signal({ name: '', scopes: ['alerts:read'] as string[] });
  readonly keyForm = form(this.keyModel, (p) => {
    required(p.name, { message: 'settings.keys.errName' });
    validate(p.scopes, ({ value }) => (value().length ? undefined : { kind: 'scopes', message: 'settings.keys.errScopes' }));
  });
  readonly newSecret = signal<string | null>(null);
  readonly keyError = signal<ApiError | null>(null);

  /** Vertical tab list: arrow keys move between sections (WAI-ARIA tabs pattern, automatic activation). */
  tabKey(e: KeyboardEvent): void {
    const i = this.sections.findIndex((x) => x.id === this.section());
    const n = this.sections.length;
    const next = e.key === 'ArrowDown' || e.key === 'ArrowRight' ? (i + 1) % n : e.key === 'ArrowUp' || e.key === 'ArrowLeft' ? (i - 1 + n) % n : e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : -1;
    if (next < 0) return;
    e.preventDefault();
    this.section.set(this.sections[next].id);
    (e.currentTarget as HTMLElement).querySelector<HTMLElement>(`#tab-${this.sections[next].id}`)?.focus();
  }

  toggleMock(): void {
    this.settings.update({ mock: !this.conn().mock });
    this.source.update((n) => n + 1);
    this.toasts.success(this.i18n.t(this.conn().mock ? 'settings.conn.mockOn' : 'settings.conn.mockOff'));
  }

  setMode(m: AuthMode): void {
    this.settings.update({ authMode: m });
  }

  setRole(u: User, role: Role): void {
    this.api.updateUserRole(u.id, role).subscribe({
      next: (x) => {
        this.users.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y)));
        this.toasts.success(this.i18n.t('settings.users.roleChanged'), this.i18n.t('settings.users.nowRole', { name: x.name, role: this.roleName(x.role).toLowerCase() }));
      },
      error: (e: ApiError) => this.toasts.error(this.i18n.t('settings.users.roleFailed'), errorText(e, this.i18n.lang())),
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
        this.toasts.success(this.i18n.t('settings.keys.created'), k.name);
      } catch (err) {
        this.keyError.set(toApiError(err));
      }
    });
  }

  async revoke(k: ApiKey): Promise<void> {
    const ok = await confirmAction(this.dialog, this.injector, { title: { key: 'settings.keys.confirmTitle', params: { name: k.name } }, message: { key: 'settings.keys.confirmMessage' }, confirm: { key: 'settings.keys.confirmButton' }, danger: true });
    if (!ok) return;
    this.api.revokeApiKey(k.id).subscribe({
      next: (x) => {
        this.keys.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y)));
        this.toasts.success(this.i18n.t('settings.keys.revokedToast'), k.name);
      },
      error: (e: ApiError) => this.toasts.error(this.i18n.t('settings.keys.revokeFailed'), errorText(e, this.i18n.lang())),
    });
  }
}
