import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';

export type StepId = 'scope' | 'conditions' | 'window' | 'actions' | 'review';

export const STEPS: { id: StepId; label: string; hint: string }[] = [
  { id: 'scope', label: 'Scope', hint: 'What the rule watches' },
  { id: 'conditions', label: 'Conditions', hint: 'When it matches' },
  { id: 'window', label: 'Window', hint: 'Aggregate over time' },
  { id: 'actions', label: 'Actions', hint: 'What happens' },
  { id: 'review', label: 'Review', hint: 'Check and save' },
];

/** Which step a validation path belongs to (paths come from validateDraft). */
export function stepOfPath(path: string): Exclude<StepId, 'review'> {
  if (path === 'when' || path.startsWith('when.') || path.startsWith('when[')) return 'conditions';
  if (path === 'window' || path.startsWith('window.')) return 'window';
  if (path === 'cooldown' || path === 'actions' || path.startsWith('actions[') || path.startsWith('actions.')) return 'actions';
  return 'scope';
}

/** Numbered step navigation: the rule really is built in this order, and every step stays reachable. */
@Component({
  selector: 'oci-rule-step-nav',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav aria-label="Rule builder steps">
      <ol>
        @for (s of steps; track s.id; let i = $index) {
          <li>
            <button type="button" [attr.aria-current]="current() === s.id ? 'step' : null" [class.done]="i < index()" (click)="current.set(s.id)"
              [attr.aria-label]="(i + 1) + '. ' + s.label + (issues()[s.id] ? ', ' + issues()[s.id] + (issues()[s.id] === 1 ? ' issue' : ' issues') : '')">
              <span class="n" aria-hidden="true">{{ i + 1 }}</span>
              <span class="t"><span class="l">{{ s.label }}</span><span class="h">{{ s.hint }}</span></span>
              @if (issues()[s.id]; as c) { <span class="iss" aria-hidden="true">{{ c }}</span> }
            </button>
          </li>
        }
      </ol>
    </nav>
  `,
  styles: `
    ol { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 6px; }
    button { all: unset; box-sizing: border-box; width: 100%; display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: var(--r-control); cursor: pointer; border: 1px solid transparent; min-width: 0; }
    button:hover { background: var(--surface-2); }
    button:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
    button[aria-current='step'] { background: var(--surface); border-color: var(--line); box-shadow: var(--shadow-control); }
    .n { flex: none; width: 26px; height: 26px; display: grid; place-items: center; border-radius: 50%; font: 600 14px var(--font-display); background: var(--well); box-shadow: var(--shadow-inset); color: var(--ink-2); }
    [aria-current='step'] .n { background: var(--accent); color: var(--accent-ink); box-shadow: none; }
    .done .n { color: var(--accent); }
    .t { display: grid; min-width: 0; }
    .l { font: 600 15px/1.2 var(--font-display); color: var(--ink); }
    .h { font-size: 13px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .iss { margin-left: auto; flex: none; font-size: 12px; font-weight: 600; color: var(--danger); background: color-mix(in srgb, var(--risk-critical) var(--tint), var(--surface)); padding: 0 7px; border-radius: 10px; line-height: 20px; }
    @media (max-width: 900px) { .h { display: none; } }
    @media (max-width: 640px) { ol { grid-template-columns: repeat(5, auto); overflow-x: auto; } .t { display: none; } button { padding: 6px; } }
  `,
})
export class RuleStepNav {
  readonly current = model.required<StepId>();
  /** Issue count per step. */
  readonly issues = input<Partial<Record<StepId, number>>>({});
  readonly steps = STEPS;
  index(): number {
    return STEPS.findIndex((s) => s.id === this.current());
  }
}
