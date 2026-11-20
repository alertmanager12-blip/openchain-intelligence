import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { MatIconButton } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { ApiError, codedText, errorText } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { fmtPercent } from '../../core/i18n/format';
import { I18nService } from '../../core/i18n/i18n.service';
import { Rule, RuleNoise } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { ruleText } from '../../shared/rule-text';
import { SeverityBadge, StateComponent } from '../../shared/ui';

/** Rules stored on the server: enable or disable them, open them in the builder or the simulator, or load their YAML. */
@Component({
  selector: 'oci-stored-rules',
  imports: [RouterLink, MatTableModule, MatSlideToggle, MatMenuModule, MatIconButton, SeverityBadge, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card card--flush" aria-labelledby="h-stored">
      <div class="card-head"><h2 id="h-stored">{{ i18n.t('rules.stored.title') }}</h2>
        @if (list.value(); as p) { <span class="small muted">{{ i18n.t('rules.stored.enabledOf', { enabled: enabledCount(), total: p.items.length }) }}</span> }</div>
      <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value()?.items ?? []).length === 0"
        [emptyText]="i18n.t('rules.stored.empty')" skeleton="table" [skeletonRows]="4" (retry)="list.reload()">
        <div class="table-wrap"><table mat-table [dataSource]="list.value()?.items ?? []" [trackBy]="byId">
          <ng-container matColumnDef="rule">
            <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('rules.stored.rule') }}</th>
            <td mat-cell *matCellDef="let r"><span class="strong">{{ text(r).name }}</span>
              @if (text(r).description; as d) { <div class="sub desc">{{ d }}</div> }
              <div class="sub"><span class="mono">{{ r.id }}</span></div>
              @if (r.unreachableReasons?.length) {
                <div class="sub warn" data-testid="unreachable">{{ i18n.t('rules.stored.unreachable', { reasons: reasons(r) }) }}</div>
              }</td>
          </ng-container>
          <ng-container matColumnDef="severity">
            <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('rules.stored.severity') }}</th>
            <td mat-cell *matCellDef="let r"><oci-severity [value]="r.severity" /></td>
          </ng-container>
          <ng-container matColumnDef="enabled">
            <th mat-header-cell *matHeaderCellDef scope="col">{{ i18n.t('rules.stored.enabled') }}</th>
            <td mat-cell *matCellDef="let r"><mat-slide-toggle [checked]="r.enabled" [disabled]="busy() === r.id || (!r.enabled && !!r.unreachableReasons?.length)" (change)="toggle(r)" [attr.aria-label]="i18n.t('rules.stored.enabledAria', { name: text(r).name })" hideIcon /></td>
          </ng-container>
          <ng-container matColumnDef="noise">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right">{{ i18n.t('rules.stored.alerts24h') }}</th>
            <td mat-cell *matCellDef="let r" class="right nowrap" data-testid="noise">
              @if (noiseOf(r.id); as n) {
                {{ n.recent }}@if (n.falsePositiveRate !== null) { <div class="sub">{{ i18n.t('rules.stored.falsePositives', { pct: pct(n.falsePositiveRate) }) }}</div> }
              } @else { <span class="muted">0</span> }
            </td>
          </ng-container>
          <ng-container matColumnDef="version">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right">{{ i18n.t('rules.stored.version') }}</th>
            <td mat-cell *matCellDef="let r" class="right">{{ r.version }}</td>
          </ng-container>
          <ng-container matColumnDef="updated">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right">{{ i18n.t('rules.stored.updated') }}</th>
            <td mat-cell *matCellDef="let r" class="right small nowrap">{{ ago(r.updatedAt) }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right"><span class="sr-only">{{ i18n.t('rules.stored.actions') }}</span></th>
            <td mat-cell *matCellDef="let r" class="right">
              <button matIconButton type="button" [matMenuTriggerFor]="menu" [matMenuTriggerData]="{ r }" [attr.aria-label]="i18n.t('rules.stored.actionsFor', { name: text(r).name })">
                <svg viewBox="0 0 16 16" aria-hidden="true" fill="currentColor"><circle cx="8" cy="3.5" r="1.3"/><circle cx="8" cy="8" r="1.3"/><circle cx="8" cy="12.5" r="1.3"/></svg></button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="cols"></tr>
          <tr mat-row *matRowDef="let r; columns: cols"></tr>
        </table></div>
        <mat-menu #menu="matMenu" xPosition="before">
          <ng-template matMenuContent let-r="r">
            <a mat-menu-item [routerLink]="['/rules/builder']" [queryParams]="{ rule: r.id }">{{ i18n.t('rules.stored.openBuilder') }}</a>
            <a mat-menu-item [routerLink]="['/rules/simulator']" [queryParams]="{ rule: r.id }">{{ i18n.t('rules.stored.simulate') }}</a>
            <button mat-menu-item type="button" (click)="loadYaml.emit(r.yaml)">{{ i18n.t('rules.stored.loadYaml') }}</button>
          </ng-template>
        </mat-menu>
      </oci-state>
    </section>
  `,
})
export class StoredRules {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18nService);
  readonly loadYaml = output<string>();
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.rules() });
  readonly busy = signal<string | null>(null);
  protected ago(iso: string): string { return timeAgo(iso, Date.now(), this.i18n.lang()); }
  protected text(r: Rule): { name: string; description: string | null } { return ruleText(r, this.i18n.lang()); }
  /** Why a rule can never fire: by the stable codes in the console's language, else the server's English reasons. */
  protected reasons(r: Rule): string {
    const lang = this.i18n.lang();
    const en = r.unreachableReasons ?? [];
    return (r.unreachableCodes?.length === en.length ? r.unreachableCodes.map((c, i) => codedText(c, lang, en[i])) : en).join(lang === 'fr' ? '\u202f; ' : '; ');
  }
  readonly cols = ['rule', 'severity', 'enabled', 'noise', 'version', 'updated', 'actions'];
  /** Alert volume per rule over the last 24 hours (GET /rules/noise): the measurement behind rule tuning. */
  readonly noise = apiResource({ params: () => ({}), stream: () => this.api.ruleNoise(24) });
  private readonly noiseById = computed(() => new Map((this.noise.value() ?? []).map((n) => [n.ruleId, n])));
  noiseOf(id: string): RuleNoise | undefined { return this.noiseById().get(id); }
  protected pct(rate: number): string { return fmtPercent(rate, this.i18n.lang()); }
  readonly byId = (_: number, r: Rule) => r.id;

  enabledCount(): number {
    return (this.list.value()?.items ?? []).filter((r) => r.enabled).length;
  }

  reload(): void {
    this.list.reload();
  }

  toggle(r: Rule): void {
    const on = !r.enabled;
    this.busy.set(r.id);
    this.api.enableRule(r.id, on).subscribe({
      next: (u) => {
        this.busy.set(null);
        this.list.update((p) => ({ items: (p?.items ?? []).map((x) => (x.id === u.id ? u : x)), nextCursor: p?.nextCursor ?? null }));
        this.toasts.success(this.i18n.t(on ? 'rules.stored.toast.enabled' : 'rules.stored.toast.disabled'), ruleText(r, this.i18n.lang()).name);
      },
      error: (e: ApiError) => {
        this.busy.set(null);
        this.toasts.error(this.i18n.t(on ? 'rules.stored.toast.enableFailed' : 'rules.stored.toast.disableFailed'), errorText(e, this.i18n.lang()));
      },
    });
  }
}
