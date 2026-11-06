import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormField, form, required, submit, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { ApiError, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { ConnectorType, Webhook } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';
import { maskTarget } from './connector-target';

const TYPES: { type: ConnectorType; label: string; target: string; placeholder: string; help: string; available?: false }[] = [
  { type: 'WEBHOOK', label: 'Webhook', target: 'HTTPS endpoint', placeholder: 'https://soc.example.com/hooks/oci', help: 'Signed JSON POST (HMAC-SHA256 in X-OCI-Signature). The signing secret is shown once.' },
  { type: 'SLACK', label: 'Slack', target: 'Incoming webhook URL', placeholder: 'https://hooks.slack.com/services/…', help: 'Posts a compact alert card to the channel bound to the webhook.' },
  { type: 'DISCORD', label: 'Discord', target: 'Channel webhook URL', placeholder: 'https://discord.com/api/webhooks/…', help: 'Posts an embed per alert.' },
  { type: 'TELEGRAM', label: 'Telegram', target: 'Chat id', placeholder: '-1001234567890', help: 'Requires OCI_TELEGRAM_BOT_TOKEN on the server; enter the destination chat id.' },
  { type: 'EMAIL', label: 'Email', target: 'Recipient address', placeholder: 'soc@example.com', help: 'Not available in this version: the server has no mail transport yet.', available: false },
];

@Component({
  selector: 'oci-integrations',
  imports: [FormField, PageHeader, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './integrations.html',
  styleUrl: './integrations.scss',
})
export class IntegrationsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  readonly types = TYPES;
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.webhooks() });
  readonly type = signal<ConnectorType>('WEBHOOK');
  readonly meta = computed(() => TYPES.find((t) => t.type === this.type())!);
  readonly model = signal({ name: '', target: '' });
  readonly connector = form(this.model, (p) => {
    required(p.name, { message: 'Give the connector a name.' });
    required(p.target, { message: 'Enter where alerts are delivered.' });
    validate(p.target, ({ value }) => {
      const v = value().trim();
      if (!v) return undefined;
      return this.type() === 'TELEGRAM'
        ? (/^-?\d+$/.test(v) ? undefined : { kind: 'chat-id', message: 'A Telegram chat id is a number, for example -1001234567890.' })
        : (/^https:\/\//i.test(v) ? undefined : { kind: 'https', message: 'Use an https:// URL.' });
    });
  });
  readonly busy = signal(false);
  readonly createError = signal<ApiError | null>(null);
  readonly secret = signal<string | null>(null);
  readonly testing = signal<string | null>(null);
  readonly lastTest = signal<{ id: string; ok: boolean; message: string } | null>(null);
  readonly ago = timeAgo;
  readonly mask = maskTarget;

  readonly stats = computed(() => {
    const l = this.list.value() ?? [];
    const last = l.map((w) => w.lastDeliveryAt).filter((x): x is string => !!x).sort().at(-1) ?? null;
    return { total: l.length, failed: l.filter((w) => w.lastDeliveryStatus === 'FAILED').length, never: l.filter((w) => !w.lastDeliveryStatus).length, last };
  });

  typeLabel(t: ConnectorType): string {
    return TYPES.find((x) => x.type === t)?.label ?? t;
  }

  create(e: Event): void {
    e.preventDefault();
    void submit(this.connector, async () => {
      this.busy.set(true);
      this.createError.set(null);
      try {
        const w = await firstValueFrom(this.api.createWebhook({ type: this.type(), name: this.model().name.trim(), target: this.model().target.trim() }));
        this.secret.set(w.secret ?? null);
        this.model.set({ name: '', target: '' });
        this.connector().reset();
        const { secret: _omit, ...stored } = w;
        this.list.update((l) => [...(l ?? []), stored]);
        this.toasts.success('Connector created', `${w.name} (${w.id})`);
      } catch (err) {
        this.createError.set(toApiError(err));
      } finally {
        this.busy.set(false);
      }
    });
  }

  async test(w: Webhook): Promise<void> {
    this.testing.set(w.id);
    this.lastTest.set(null);
    try {
      const r = await firstValueFrom(this.api.testWebhook(w.id));
      const message = r.message + (r.statusCode ? ` (HTTP ${r.statusCode})` : '');
      this.lastTest.set({ id: w.id, ok: r.delivered, message });
      if (r.delivered) this.toasts.success('Test sent', `${w.name}: ${message}`);
      else this.toasts.error('Test not delivered', `${w.name}: ${message}`);
      this.list.reload();
    } catch (err) {
      const e = toApiError(err);
      this.lastTest.set({ id: w.id, ok: false, message: `${e.title}: ${e.detail ?? ''}` });
      this.toasts.error('Test not delivered', e.detail ?? e.title);
    } finally {
      this.testing.set(null);
    }
  }

  remove(w: Webhook): void {
    if (!confirm(`Delete connector "${w.name}"? Rules referencing ${w.id} will stop delivering to it.`)) return;
    this.api.deleteWebhook(w.id).subscribe({
      next: () => {
        this.list.update((l) => (l ?? []).filter((x) => x.id !== w.id));
        this.toasts.success('Connector deleted', w.name);
      },
      error: (e: ApiError) => this.toasts.error(e.title, e.detail ?? undefined),
    });
  }

  copy(s: string): void {
    void navigator.clipboard?.writeText(s).then(() => this.toasts.success('Secret copied')).catch(() => undefined);
  }
}
