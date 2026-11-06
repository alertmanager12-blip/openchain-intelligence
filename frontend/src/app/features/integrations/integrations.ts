import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ConnectorType, Webhook } from '../../core/models';
import { timeAgo } from '../../shared/format';
import { query } from '../../shared/query';
import { UI } from '../../shared/ui';

const TYPES: { type: ConnectorType; label: string; target: string; placeholder: string; help: string; available?: false }[] = [
  { type: 'WEBHOOK', label: 'Webhook', target: 'HTTPS endpoint', placeholder: 'https://soc.example.com/hooks/oci', help: 'Signed JSON POST (HMAC-SHA256 in X-OCI-Signature). The signing secret is shown once.' },
  { type: 'SLACK', label: 'Slack', target: 'Incoming webhook URL', placeholder: 'https://hooks.slack.com/services/…', help: 'Posts a compact alert card to the channel bound to the webhook.' },
  { type: 'DISCORD', label: 'Discord', target: 'Channel webhook URL', placeholder: 'https://discord.com/api/webhooks/…', help: 'Posts an embed per alert.' },
  { type: 'TELEGRAM', label: 'Telegram', target: 'Chat id', placeholder: '-1001234567890', help: 'Requires OCI_TELEGRAM_BOT_TOKEN on the server; enter the destination chat id.' },
  { type: 'EMAIL', label: 'Email', target: 'Recipient address', placeholder: 'soc@example.com', help: 'Not available in this version: the server has no mail transport yet.', available: false },
];

@Component({
  selector: 'oci-integrations',
  imports: [FormsModule, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head"><div>
        <h1>Integrations</h1>
        <p>Where alerts are delivered. Rules reference a connector by its id in their actions.</p>
      </div></div>

      <div class="grid layout">
        <section class="card card--flush">
          <h2>Connectors</h2>
          <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.data()" [empty]="(list.data() ?? []).length === 0" emptyText="No connector yet. Add one with the form to start delivering alerts." (retry)="list.retry()">
            <div class="table-wrap"><table>
              <thead><tr><th scope="col">Name</th><th scope="col">Type</th><th scope="col">Target</th><th scope="col">Last delivery</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
              <tbody>@for (w of list.data() ?? []; track w.id) {
                <tr>
                  <td><strong>{{ w.name }}</strong><div class="mono small muted">{{ w.id }}</div></td>
                  <td>{{ w.type }}</td>
                  <td class="mono small">{{ w.target }}</td>
                  <td class="small">@if (w.lastDeliveryStatus) { <span [class.ok]="w.lastDeliveryStatus === 'OK'" [class.bad]="w.lastDeliveryStatus === 'FAILED'">{{ w.lastDeliveryStatus === 'OK' ? 'Delivered' : 'Failed' }}</span><span class="muted">&#32;{{ ago(w.lastDeliveryAt) }}</span> } @else { <span class="muted">Never</span> }</td>
                  <td class="nowrap">
                    <button class="btn btn--sm" type="button" (click)="test(w)" [disabled]="testing() === w.id">{{ testing() === w.id ? 'Sending…' : 'Send test' }}</button>
                    <button class="btn btn--sm btn--ghost btn--danger" type="button" (click)="remove(w)">Delete</button>
                  </td>
                </tr>
                @if (testResult()?.id === w.id) { <tr><td colspan="5" class="small" [class.ok]="testResult()!.ok" [class.bad]="!testResult()!.ok" role="status">{{ testResult()!.message }}</td></tr> }
              }</tbody>
            </table></div>
          </oci-state>
        </section>

        <section class="card">
          <h2>Add a connector</h2>
          <form class="stack" (submit)="$event.preventDefault(); create()">
            <div class="seg" role="radiogroup" aria-label="Connector type">
              @for (t of types; track t.type) { <button type="button" role="radio" [attr.aria-checked]="type() === t.type" [attr.aria-pressed]="type() === t.type" [disabled]="t.available === false" [attr.title]="t.available === false ? t.help : null" (click)="type.set(t.type)">{{ t.label }}</button> }
            </div>
            <p class="small muted">{{ meta().help }}</p>
            <label>Name<input name="name" [ngModel]="name()" (ngModelChange)="name.set($event)" placeholder="SOC on-call" required /></label>
            <label>{{ meta().target }}<input name="target" class="mono" [ngModel]="target()" (ngModelChange)="target.set($event)" [placeholder]="meta().placeholder" required /></label>
            @if (createError(); as e) { <p class="field-error" role="alert">{{ e.title }}: {{ e.detail }}</p> }
            <div><button class="btn btn--primary" type="submit" [disabled]="busy() || meta().available === false || !name().trim() || !target().trim()">Create connector</button></div>
          </form>
          @if (secret(); as s) {
            <div class="note note--warn secret" role="alert">
              <strong>Signing secret: copy it now, it will not be shown again.</strong>
              <code class="mono">{{ s }}</code>
              <div class="row"><button class="btn btn--sm" type="button" (click)="copy(s)">Copy</button><button class="btn btn--sm btn--ghost" type="button" (click)="secret.set(null)">I stored it</button></div>
            </div>
          }
        </section>
      </div>
    </div>
  `,
  styles: `
    .layout { grid-template-columns: minmax(0, 1.6fr) minmax(300px, 1fr); align-items: start; }
    @media (max-width: 1000px) { .layout { grid-template-columns: minmax(0, 1fr); } }
    .seg { flex-wrap: wrap; }
    .ok { color: var(--ok); } .bad { color: var(--danger); }
    .secret { display: grid; gap: 8px; margin-top: 12px; } .secret code { word-break: break-all; }
  `,
})
export class IntegrationsPage {
  private readonly api = inject(ApiService);
  readonly types = TYPES;
  readonly list = query<Webhook[]>();
  readonly type = signal<ConnectorType>('WEBHOOK');
  readonly meta = computed(() => TYPES.find((t) => t.type === this.type())!);
  readonly name = signal('');
  readonly target = signal('');
  readonly busy = signal(false);
  readonly createError = signal<ApiError | null>(null);
  readonly secret = signal<string | null>(null);
  readonly testing = signal<string | null>(null);
  readonly testResult = signal<{ id: string; ok: boolean; message: string } | null>(null);
  readonly ago = timeAgo;

  constructor() {
    this.list.run(() => this.api.webhooks());
  }

  create(): void {
    this.busy.set(true);
    this.createError.set(null);
    this.api.createWebhook({ type: this.type(), name: this.name().trim(), target: this.target().trim() }).subscribe({
      next: (w) => {
        this.busy.set(false);
        this.secret.set(w.secret ?? null);
        this.name.set('');
        this.target.set('');
        const { secret: _omit, ...stored } = w;
        this.list.data.update((l) => [...(l ?? []), stored]);
      },
      error: (e: ApiError) => {
        this.busy.set(false);
        this.createError.set(e);
      },
    });
  }

  test(w: Webhook): void {
    this.testing.set(w.id);
    this.testResult.set(null);
    this.api.testWebhook(w.id).subscribe({
      next: (r) => {
        this.testing.set(null);
        this.testResult.set({ id: w.id, ok: r.delivered, message: r.message + (r.statusCode ? ` (HTTP ${r.statusCode})` : '') });
        this.list.retry();
      },
      error: (e: ApiError) => {
        this.testing.set(null);
        this.testResult.set({ id: w.id, ok: false, message: `${e.title}: ${e.detail ?? ''}` });
      },
    });
  }

  remove(w: Webhook): void {
    if (!confirm(`Delete connector "${w.name}"? Rules referencing ${w.id} will stop delivering to it.`)) return;
    this.api.deleteWebhook(w.id).subscribe({ next: () => this.list.data.update((l) => (l ?? []).filter((x) => x.id !== w.id)), error: (e: ApiError) => this.createError.set(e) });
  }

  copy(s: string): void {
    void navigator.clipboard?.writeText(s).catch(() => undefined);
  }
}
