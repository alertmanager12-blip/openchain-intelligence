import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RuleValidationError } from '../../core/models';
import { ACTION_TYPES, ActionType, RuleDraft } from '../../core/rules/rule-model';
import { sentence } from '../../shared/ui';

/** Step 4: what happens on a match (alert, connectors), cooldown and the enabled flag. */
@Component({
  selector: 'oci-actions-step',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ul class="acts">
      @for (a of draft().actions; track $index; let i = $index) {
        <li>
          <label>Action<select [ngModel]="a.type" (ngModelChange)="setAction(i, { type: $event })" [name]="'at' + i">@for (t of actionTypes; track t) { <option [value]="t">{{ actionLabel(t) }}</option> }</select></label>
          @if (a.type !== 'alert') {
            <label class="grow">Connector id<input class="mono" [name]="'tg' + i" [ngModel]="a.target" (ngModelChange)="setAction(i, { target: $event })" placeholder="wh-ops" [attr.aria-invalid]="err('actions[' + i + '].target') ? true : null" /></label>
          } @else { <p class="grow hint">Creates an alert in the Alerts list.</p> }
          <button class="btn btn--sm btn--ghost" type="button" (click)="remove(i)" [attr.aria-label]="'Remove action ' + (i + 1)">Remove</button>
          @if (err('actions[' + i + '].target'); as m) { <span class="field-error full">{{ m }}</span> }
        </li>
      }
    </ul>
    @if (err('actions'); as m) { <span class="field-error">{{ m }}</span> }
    <div><button class="btn btn--sm" type="button" (click)="add()">Add action</button></div>
    <div class="grid two">
      <label>Cooldown<input name="cd" [ngModel]="draft().cooldown" (ngModelChange)="patch.emit({ cooldown: $event })" placeholder="15m" [attr.aria-invalid]="err('cooldown') ? true : null" />
        @if (err('cooldown'); as m) { <span class="field-error">{{ m }}</span> } @else { <span class="hint">Suppresses repeated alerts for the same rule and subject during this period.</span> }</label>
      <div class="en">
        <button type="button" class="switch" role="switch" [attr.aria-checked]="draft().enabled" (click)="patch.emit({ enabled: !draft().enabled })" aria-labelledby="en-l"></button>
        <span id="en-l">{{ draft().enabled ? 'Enabled when saved' : 'Saved disabled' }}</span>
      </div>
    </div>
  `,
  styles: `
    :host { display: grid; gap: 16px; }
    .acts { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .acts li { display: flex; flex-wrap: wrap; gap: 10px 12px; align-items: flex-end; padding-bottom: 10px; border-bottom: 1px solid var(--line-soft); }
    .acts .hint { margin: 0 0 8px; }
    .full { flex-basis: 100%; }
    .two { grid-template-columns: minmax(0, 220px) minmax(0, 1fr); gap: 24px; align-items: end; }
    .en { display: flex; gap: 10px; align-items: center; min-height: 36px; }
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
