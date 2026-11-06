import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ApiKey, Role, User } from '../../core/models';
import { AuthMode, SettingsService } from '../../core/settings.service';
import { ThemeService, ThemePref } from '../../core/theme.service';
import { timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { UI, sentence } from '../../shared/ui';
import { environment } from '../../../environments/environment';

const SCOPES = ['rules:read', 'rules:write', 'alerts:read', 'alerts:write', 'wallets:read', 'labels:write'];

@Component({
  selector: 'oci-settings',
  imports: [FormsModule, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head"><div><h1>Settings</h1><p>Connection, appearance, users and API keys.</p></div></div>

      <div class="grid grid-2">
        <section class="card stack">
          <h2>Connection</h2>
          @if (allowMock) {
            <label class="check"><input type="checkbox" [checked]="conn().mock" (change)="settings.update({ mock: !conn().mock }); reloadAll()" />Use mock data (fictitious, in memory, no backend; development builds only)</label>
          }
          <label>Authentication<select [ngModel]="conn().authMode" (ngModelChange)="setMode($event)">
            <option value="none">None (development)</option><option value="bearer">Bearer token (OIDC)</option><option value="apiKey">API key</option>
          </select></label>
          @if (conn().authMode !== 'none') {
            <label>{{ conn().authMode === 'bearer' ? 'Access token' : 'API key' }}
              <input type="password" autocomplete="off" [ngModel]="conn().token" (ngModelChange)="settings.update({ token: $event })" /></label>
            <p class="small muted">Stored in this browser only and sent to /api requests only.</p>
          }
        </section>
        <section class="card stack">
          <h2>Appearance</h2>
          <div class="seg" role="radiogroup" aria-label="Theme">
            @for (p of themes; track p) { <button type="button" role="radio" [attr.aria-checked]="theme.pref() === p" [attr.aria-pressed]="theme.pref() === p" [attr.tabindex]="theme.pref() === p ? 0 : -1" (click)="theme.pref.set(p)" (keydown)="themeKey($event)">{{ themeLabel(p) }}</button> }
          </div>
          <p class="small muted">"System" follows your operating system preference.</p>
        </section>
      </div>

      <section class="card card--flush">
        <h2>Users &amp; roles</h2>
        <dl class="roles small pad"><div><dt>Admin</dt><dd>manages users, keys and connectors.</dd></div><div><dt>Analyst</dt><dd>triages alerts and edits rules.</dd></div><div><dt>Viewer</dt><dd>reads only.</dd></div></dl>
        <oci-state [loading]="users.loading()" [error]="users.error()" [hasData]="!!users.data()" (retry)="users.retry()">
          <div class="table-wrap"><table>
            <thead><tr><th scope="col">User</th><th scope="col">Role</th><th scope="col">Last login</th></tr></thead>
            <tbody>@for (u of users.data() ?? []; track u.id) {
              <tr><td><strong>{{ u.name }}</strong><div class="small muted">{{ u.email }}</div></td>
                <td><label><span class="sr-only">Role of {{ u.email }}</span><select [ngModel]="u.role" (ngModelChange)="setRole(u, $event)">@for (r of roles; track r) { <option [value]="r">{{ sentence(r) }}</option> }</select></label></td>
                <td class="small">{{ ago(u.lastLoginAt) }}</td></tr>
            }</tbody>
          </table></div>
        </oci-state>
      </section>

      <section class="card card--flush">
        <h2>API keys</h2>
        <form class="toolbar" (submit)="$event.preventDefault(); createKey()">
          <label>Name<input name="kn" [ngModel]="keyName()" (ngModelChange)="keyName.set($event)" placeholder="CI pipeline" /></label>
          <fieldset><legend>Scopes</legend><div class="row">
            @for (s of scopes; track s) { <label class="check"><input type="checkbox" [checked]="keyScopes().includes(s)" (change)="toggleScope(s)" />{{ s }}</label> }
          </div></fieldset>
          <button class="btn btn--primary" type="submit" [disabled]="!keyName().trim() || keyScopes().length === 0">Create key</button>
        </form>
        @if (newSecret(); as s) {
          <div class="note note--warn secret" role="alert"><strong>Copy this key now: it will not be shown again.</strong><code class="mono">{{ s }}</code>
            <div><button class="btn btn--sm" type="button" (click)="newSecret.set(null)">Done</button></div></div>
        }
        @if (keyError(); as e) { <p class="field-error pad" role="alert">{{ e.title }}: {{ e.detail }}</p> }
        <oci-state [loading]="keys.loading()" [error]="keys.error()" [hasData]="!!keys.data()" [empty]="(keys.data() ?? []).length === 0" emptyText="No API key yet. Create one above for scripts and CI pipelines." (retry)="keys.retry()">
          <div class="table-wrap"><table>
            <thead><tr><th scope="col">Name</th><th scope="col">Key</th><th scope="col">Scopes</th><th scope="col">Last used</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
            <tbody>@for (k of keys.data() ?? []; track k.id) {
              <tr [class.revoked]="k.revoked"><td>{{ k.name }}</td><td class="mono small">{{ k.prefix }}…</td><td class="small">{{ k.scopes.join(', ') }}</td><td class="small">{{ ago(k.lastUsedAt) }}</td>
                <td>@if (k.revoked) { <span class="small muted">Revoked</span> } @else { <button class="btn btn--sm btn--danger" type="button" (click)="revoke(k)">Revoke</button> }</td></tr>
            }</tbody>
          </table></div>
        </oci-state>
      </section>
    </div>
  `,
  styles: `
    .pad { padding: 0 16px; } .secret { display: grid; gap: 8px; margin: 12px 16px; } .revoked td { opacity: 0.55; }
    .roles { display: flex; flex-wrap: wrap; gap: 4px 24px; margin: 4px 0 8px; color: var(--ink-2); }
    .roles div { display: flex; gap: 4px; } .roles dt { font-weight: 600; color: var(--ink); } .roles dd { margin: 0; }
  `,
})
export class SettingsPage {
  private readonly api = inject(ApiService);
  readonly settings = inject(SettingsService);
  readonly theme = inject(ThemeService);
  readonly conn = this.settings.connection;
  /** Mock mode exists only in development builds. */
  readonly allowMock = environment.allowMock;
  readonly themes: ThemePref[] = ['system', 'light', 'dark'];
  readonly sentence = sentence;
  readonly themeLabel = (p: ThemePref) => sentence(p);

  /** Radio group keyboard pattern: arrows move and select. */
  themeKey(e: KeyboardEvent): void {
    const i = this.themes.indexOf(this.theme.pref());
    const d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    this.theme.pref.set(this.themes[(i + d + this.themes.length) % this.themes.length]);
    const btns = (e.currentTarget as HTMLElement).parentElement?.querySelectorAll<HTMLButtonElement>('button');
    btns?.[(i + d + this.themes.length) % this.themes.length]?.focus();
  }
  readonly roles: Role[] = ['ADMIN', 'ANALYST', 'VIEWER'];
  readonly scopes = SCOPES;
  readonly users = query<User[]>();
  readonly keys = query<ApiKey[]>();
  readonly keyName = signal('');
  readonly keyScopes = signal<string[]>(['alerts:read']);
  readonly newSecret = signal<string | null>(null);
  readonly keyError = signal<ApiError | null>(null);
  readonly ago = timeAgo;

  constructor() {
    this.reloadAll();
  }

  reloadAll(): void {
    this.users.run(() => this.api.users(), false);
    this.keys.run(() => this.api.apiKeys(), false);
  }

  setMode(m: AuthMode): void {
    this.settings.update({ authMode: m });
  }

  setRole(u: User, role: Role): void {
    this.api.updateUserRole(u.id, role).subscribe({ next: (x) => this.users.data.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y))), error: (e: ApiError) => this.keyError.set(e) });
  }

  toggleScope(s: string): void {
    this.keyScopes.update((l) => (l.includes(s) ? l.filter((x) => x !== s) : [...l, s]));
  }

  createKey(): void {
    this.keyError.set(null);
    this.api.createApiKey(this.keyName().trim(), this.keyScopes()).subscribe({
      next: (k) => {
        this.newSecret.set(k.secret ?? null);
        this.keyName.set('');
        const { secret: _omit, ...stored } = k;
        this.keys.data.update((l) => [stored, ...(l ?? [])]);
      },
      error: (e: ApiError) => this.keyError.set(e),
    });
  }

  revoke(k: ApiKey): void {
    if (!confirm(`Revoke key "${k.name}"? Clients using it will be rejected immediately.`)) return;
    this.api.revokeApiKey(k.id).subscribe({ next: (x) => this.keys.data.update((l) => (l ?? []).map((y) => (y.id === x.id ? x : y))), error: (e: ApiError) => this.keyError.set(e) });
  }
}
