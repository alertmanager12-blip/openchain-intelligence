import { ChangeDetectionStrategy, Component, Injector, computed, inject, signal } from '@angular/core';
import { FormField, form, required, submit, validate } from '@angular/forms/signals';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ApiError, errorText, fieldErrorText, toApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { I18nService, hasKey, type Key } from '../../core/i18n/i18n.service';
import { ConnectorType, Webhook } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { confirmAction } from '../../shared/confirm-dialog';
import { MAT_BUTTONS, MAT_FORM, MAT_TABLE } from '../../shared/material';
import { PageHeader } from '../../shared/page-header';
import { UI } from '../../shared/ui';
import { maskTarget } from './connector-target';

/** Connector types: the product names and example values stay as they are; the console's words around them are in the dictionary. */
const TYPES: { type: ConnectorType; label: string; targetKey: Key; helpKey: Key; placeholder: string; available?: false }[] = [
  { type: 'WEBHOOK', label: 'Webhook', targetKey: 'integrations.type.WEBHOOK.target', helpKey: 'integrations.type.WEBHOOK.help', placeholder: 'https://soc.example.com/hooks/oci' },
  { type: 'SLACK', label: 'Slack', targetKey: 'integrations.type.SLACK.target', helpKey: 'integrations.type.SLACK.help', placeholder: 'https://hooks.slack.com/services/…' },
  { type: 'DISCORD', label: 'Discord', targetKey: 'integrations.type.DISCORD.target', helpKey: 'integrations.type.DISCORD.help', placeholder: 'https://discord.com/api/webhooks/…' },
  { type: 'TELEGRAM', label: 'Telegram', targetKey: 'integrations.type.TELEGRAM.target', helpKey: 'integrations.type.TELEGRAM.help', placeholder: '-1001234567890' },
  { type: 'EMAIL', label: 'Email', targetKey: 'integrations.type.EMAIL.target', helpKey: 'integrations.type.EMAIL.help', placeholder: 'soc@example.com', available: false },
];

@Component({
  selector: 'oci-integrations',
  imports: [FormField, PageHeader, MatButtonToggleModule, ...MAT_BUTTONS, ...MAT_FORM, ...MAT_TABLE, ...UI],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './integrations.html',
  styleUrl: './integrations.scss',
})
export class IntegrationsPage {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  private readonly dialog = inject(MatDialog);
  private readonly injector = inject(Injector);
  readonly types = TYPES;
  readonly cols = ['name', 'type', 'target', 'last', 'actions'];
  readonly byId = (_: number, w: Webhook) => w.id;
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.webhooks() });
  readonly type = signal<ConnectorType>('WEBHOOK');
  readonly meta = computed(() => TYPES.find((t) => t.type === this.type())!);
  readonly model = signal({ name: '', target: '' });
  readonly connector = form(this.model, (p) => {
    // validation messages are dictionary keys (shown through errText), so they follow a language switch
    required(p.name, { message: 'integrations.err.name' });
    required(p.target, { message: 'integrations.err.target' });
    validate(p.target, ({ value }) => {
      const v = value().trim();
      if (!v) return undefined;
      return this.type() === 'TELEGRAM'
        ? (/^-?\d+$/.test(v) ? undefined : { kind: 'chat-id', message: 'integrations.err.chatId' })
        : (/^https:\/\//i.test(v) ? undefined : { kind: 'https', message: 'integrations.err.https' });
    });
  });
  readonly busy = signal(false);
  readonly createError = signal<ApiError | null>(null);
  readonly secret = signal<string | null>(null);
  readonly testing = signal<string | null>(null);
  /** Result of the last test: the server's own wording (English) with its HTTP status, or the console's error text. */
  readonly lastTest = signal<{ id: string; ok: boolean; message: string; status: number | null; fromServer: boolean } | null>(null);
  protected ago(iso: string | null | undefined): string { return timeAgo(iso, Date.now(), this.i18n.lang()); }
  protected errText(m: { message?: string } | undefined): string { return m?.message && hasKey(m.message) ? this.i18n.t(m.message) : this.i18n.t('integrations.err.invalid'); }
  protected apiError(e: ApiError): string {
    const lang = this.i18n.lang();
    return [errorText(e, lang), ...e.fieldErrors.map((f) => `${f.field}: ${fieldErrorText(f, lang)}`)].join(' ');
  }
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
        this.toasts.success(this.i18n.t('integrations.toast.created'), `${w.name} (${w.id})`);
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
      const message = r.message + (r.statusCode ? ` ${this.i18n.t('integrations.http', { code: r.statusCode })}` : '');
      this.lastTest.set({ id: w.id, ok: r.delivered, message: r.message, status: r.statusCode ?? null, fromServer: true });
      if (r.delivered) this.toasts.success(this.i18n.t('integrations.toast.testSent'), `${w.name}: ${message}`);
      else this.toasts.error(this.i18n.t('integrations.toast.testFailed'), `${w.name}: ${message}`);
      this.list.reload();
    } catch (err) {
      const e = toApiError(err);
      this.lastTest.set({ id: w.id, ok: false, message: errorText(e, this.i18n.lang()), status: null, fromServer: false });
      this.toasts.error(this.i18n.t('integrations.toast.testFailed'), errorText(e, this.i18n.lang()));
    } finally {
      this.testing.set(null);
    }
  }

  async remove(w: Webhook): Promise<void> {
    const ok = await confirmAction(this.dialog, this.injector, {
      title: { key: 'integrations.confirm.title', params: { name: w.name } }, message: { key: 'integrations.confirm.message', params: { id: w.id } },
      confirm: { key: 'integrations.confirm.action' }, danger: true,
    });
    if (!ok) return;
    this.api.deleteWebhook(w.id).subscribe({
      next: () => {
        this.list.update((l) => (l ?? []).filter((x) => x.id !== w.id));
        this.toasts.success(this.i18n.t('integrations.toast.deleted'), w.name);
      },
      error: (e: ApiError) => this.toasts.error(this.i18n.t('integrations.toast.deleteFailed'), errorText(e, this.i18n.lang())),
    });
  }

  copy(s: string): void {
    void navigator.clipboard?.writeText(s).then(() => this.toasts.success(this.i18n.t('integrations.toast.copied'))).catch(() => undefined);
  }
}
