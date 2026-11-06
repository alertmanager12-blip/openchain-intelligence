import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError } from '../../core/api-error';
import { ApiService } from '../../core/api.service';
import { Rule } from '../../core/models';
import { Toasts } from '../../core/toasts';
import { apiResource } from '../../shared/api-resource';
import { timeAgo } from '../../shared/format';
import { SeverityBadge, StateComponent } from '../../shared/ui';

/** Rules stored on the server: enable or disable them, open them in the builder or the simulator, or load their YAML. */
@Component({
  selector: 'oci-stored-rules',
  imports: [RouterLink, SeverityBadge, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card card--flush" aria-labelledby="h-stored">
      <div class="card-head"><h2 id="h-stored">Stored rules</h2>
        @if (list.value(); as p) { <span class="small muted">{{ enabledCount() }} of {{ p.items.length }} enabled</span> }</div>
      <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value()?.items ?? []).length === 0"
        emptyText="No rule is stored yet. Save the YAML above, or create one in the rule builder." skeleton="table" [skeletonRows]="4" (retry)="list.reload()">
        <div class="table-wrap"><table>
          <thead><tr><th scope="col">Rule</th><th scope="col">Severity</th><th scope="col">Enabled</th><th scope="col" class="right">Version</th><th scope="col" class="right">Updated</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
          <tbody>@for (r of list.value()?.items ?? []; track r.id) {
            <tr>
              <td><span class="strong">{{ r.name }}</span><div class="sub"><span class="mono">{{ r.id }}</span></div></td>
              <td><oci-severity [value]="r.severity" /></td>
              <td><button type="button" class="switch" role="switch" [attr.aria-checked]="r.enabled" [disabled]="busy() === r.id" (click)="toggle(r)" [attr.aria-label]="'Enabled: ' + r.name"></button></td>
              <td class="right">{{ r.version }}</td>
              <td class="right small nowrap">{{ ago(r.updatedAt) }}</td>
              <td class="right"><div class="row-actions">
                <a class="btn btn--sm btn--ghost" [routerLink]="['/rules/builder']" [queryParams]="{ rule: r.id }">Open in builder</a>
                <a class="btn btn--sm btn--ghost" [routerLink]="['/rules/simulator']" [queryParams]="{ rule: r.id }">Simulate</a>
                <button class="btn btn--sm btn--ghost" type="button" (click)="loadYaml.emit(r.yaml)">Load YAML</button>
              </div></td>
            </tr>
          }</tbody>
        </table></div>
      </oci-state>
    </section>
  `,
})
export class StoredRules {
  private readonly api = inject(ApiService);
  private readonly toasts = inject(Toasts);
  readonly loadYaml = output<string>();
  readonly list = apiResource({ params: () => ({}), stream: () => this.api.rules() });
  readonly busy = signal<string | null>(null);
  readonly ago = timeAgo;

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
        this.toasts.success(on ? 'Rule enabled' : 'Rule disabled', r.name);
      },
      error: (e: ApiError) => {
        this.busy.set(null);
        this.toasts.error(on ? 'Could not enable the rule' : 'Could not disable the rule', e.detail ?? e.title);
      },
    });
  }
}
