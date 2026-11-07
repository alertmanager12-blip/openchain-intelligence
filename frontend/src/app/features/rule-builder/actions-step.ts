import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { RuleValidationError } from '../../core/models';
import { ACTION_TYPES, ActionType, RuleDraft } from '../../core/rules/rule-model';
import { MAT_FORM } from '../../shared/material';
import { sentence } from '../../shared/ui';

/** Step 4: what happens on a match (alert, connectors), cooldown and the enabled flag. */
@Component({
  selector: 'oci-actions-step',
  imports: [FormsModule, MatButton, MatSlideToggle, ...MAT_FORM],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="acts">
      @for (a of draft().actions; track $index; let i = $index) {
        <li>
          <mat-form-field class="type">
            <mat-label>Action</mat-label>
            <mat-select [value]="a.type" (valueChange)="setAction(i, { type: $event })">@for (t of actionTypes; track t) { <mat-option [value]="t">{{ actionLabel(t) }}</mat-option> }</mat-select>
          </mat-form-field>
          @if (a.type !== 'alert') {
            <mat-form-field class="grow">
              <mat-label>Connector id</mat-label>
              <input matInput class="mono" [name]="'tg' + i" [ngModel]="a.target" (ngModelChange)="setAction(i, { target: $event })" placeholder="wh-ops" [ociInvalid]="!!err('actions[' + i + '].target')" />
              @if (err('actions[' + i + '].target'); as m) { <mat-error>{{ m }}</mat-error> }
            </mat-form-field>
          } @else { <p class="grow hint">Creates an alert in the Alerts list.</p> }
          <button matButton class="sm danger" type="button" (click)="remove(i)" [attr.aria-label]="'Remove action ' + (i + 1)">Remove</button>
        </li>
      }
    </ul>
    @if (err('actions'); as m) { <span class="field-error">{{ m }}</span> }
    <div><button matButton="outlined" class="sm" type="button" (click)="add()">Add action</button></div>
    <div class="grid two">
      <mat-form-field>
        <mat-label>Cooldown</mat-label>
        <input matInput name="cd" [ngModel]="draft().cooldown" (ngModelChange)="patch.emit({ cooldown: $event })" placeholder="15m" [ociInvalid]="!!err('cooldown')" />
        @if (err('cooldown'); as m) { <mat-error>{{ m }}</mat-error> } @else { <mat-hint>Suppresses repeated alerts for the same rule and subject during this period.</mat-hint> }
      </mat-form-field>
      <div class="en">
        <mat-slide-toggle hideIcon [checked]="draft().enabled" (change)="patch.emit({ enabled: !draft().enabled })">{{ draft().enabled ? 'Enabled when saved' : 'Saved disabled' }}</mat-slide-toggle>
      </div>
    </div>
  `,
  styles: `
    :host { display: grid; gap: 16px; }
    .acts { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .acts li { display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: flex-start; padding-bottom: 6px; border-bottom: 1px solid var(--line-soft); }
    .acts .type { flex: 0 1 220px; }
    .acts .grow { flex: 1 1 220px; }
    .acts p.grow { margin: 14px 0 0; }
    .acts button { margin-top: 6px; }
    .two { grid-template-columns: minmax(0, 260px) minmax(0, 1fr); gap: 24px; align-items: start; }
    .en { display: flex; gap: 10px; align-items: center; min-height: 44px; }
    @media (max-width: 640px) { .two { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class ActionsStep {
  readonly draft = input.required<RuleDraft>();
  readonly errors = input<RuleValidationError[]>([]);
  readonly patch = output<Partial<RuleDraft>>();
  readonly actionTypes = ACTION_TYPES;

  actionLabel(t: ActionType): string {
    return t === 'alert' ? 'Raise an alert' : `Send to ${sentence(t)}`;
  }
  err(path: string): string | null {
    return this.errors().find((e) => e.path === path)?.message ?? null;
  }
  setAction(i: number, p: { type?: ActionType; target?: string }): void {
    this.patch.emit({ actions: this.draft().actions.map((a, j) => (j === i ? { ...a, ...p } : a)) });
  }
  add(): void {
    this.patch.emit({ actions: [...this.draft().actions, { type: 'webhook', target: '' }] });
  }
  remove(i: number): void {
    this.patch.emit({ actions: this.draft().actions.filter((_, j) => j !== i) });
  }
}
