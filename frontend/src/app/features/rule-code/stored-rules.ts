import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { MatIconButton } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
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
  imports: [RouterLink, MatTableModule, MatSlideToggle, MatMenuModule, MatIconButton, SeverityBadge, StateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card card--flush" aria-labelledby="h-stored">
      <div class="card-head"><h2 id="h-stored">Stored rules</h2>
        @if (list.value(); as p) { <span class="small muted">{{ enabledCount() }} of {{ p.items.length }} enabled</span> }</div>
      <oci-state [loading]="list.loading()" [error]="list.error()" [hasData]="!!list.value()" [empty]="(list.value()?.items ?? []).length === 0"
        emptyText="No rule is stored yet. Save the YAML above, or create one in the rule builder." skeleton="table" [skeletonRows]="4" (retry)="list.reload()">
        <div class="table-wrap"><table mat-table [dataSource]="list.value()?.items ?? []" [trackBy]="byId">
          <ng-container matColumnDef="rule">
            <th mat-header-cell *matHeaderCellDef scope="col">Rule</th>
            <td mat-cell *matCellDef="let r"><span class="strong">{{ r.name }}</span><div class="sub"><span class="mono">{{ r.id }}</span></div></td>
          </ng-container>
          <ng-container matColumnDef="severity">
            <th mat-header-cell *matHeaderCellDef scope="col">Severity</th>
            <td mat-cell *matCellDef="let r"><oci-severity [value]="r.severity" /></td>
          </ng-container>
          <ng-container matColumnDef="enabled">
            <th mat-header-cell *matHeaderCellDef scope="col">Enabled</th>
            <td mat-cell *matCellDef="let r"><mat-slide-toggle [checked]="r.enabled" [disabled]="busy() === r.id" (change)="toggle(r)" [aria-label]="'Enabled: ' + r.name" hideIcon /></td>
          </ng-container>
          <ng-container matColumnDef="version">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right">Version</th>
            <td mat-cell *matCellDef="let r" class="right">{{ r.version }}</td>
          </ng-container>
          <ng-container matColumnDef="updated">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right">Updated</th>
            <td mat-cell *matCellDef="let r" class="right small nowrap">{{ ago(r.updatedAt) }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef scope="col" class="right"><span class="sr-only">Actions</span></th>
            <td mat-cell *matCellDef="let r" class="right">
              <button matIconButton type="button" [matMenuTriggerFor]="menu" [matMenuTriggerData]="{ r }" [attr.aria-label]="'Actions for ' + r.name">
                <svg viewBox="0 0 16 16" aria-hidden="true" fill="currentColor"><circle cx="8" cy="3.5" r="1.3"/><circle cx="8" cy="8" r="1.3"/><circle cx="8" cy="12.5" r="1.3"/></svg></button>
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="cols"></tr>
          <tr mat-row *matRowDef="let r; columns: cols"></tr>
        </table></div>
        <mat-menu #menu="matMenu" xPosition="before">
          <ng-template matMenuContent let-r="r">
            <a mat-menu-item [routerLink]="['/rules/builder']" [queryParams]="{ rule: r.id }">Open in builder</a>
            <a mat-menu-item [routerLink]="['/rules/simulator']" [queryParams]="{ rule: r.id }">Simulate</a>
            <button mat-menu-item type="button" (click)="loadYaml.emit(r.yaml)">Load YAML into the editor</button>
          </ng-template>
        </mat-menu>
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
  readonly cols = ['rule', 'severity', 'enabled', 'version', 'updated', 'actions'];
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
        this.toasts.success(on ? 'Rule enabled' : 'Rule disabled', r.name);
      },
      error: (e: ApiError) => {
        this.busy.set(null);
        this.toasts.error(on ? 'Could not enable the rule' : 'Could not disable the rule', e.detail ?? e.title);
      },
    });
  }
}
