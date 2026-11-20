import type { Key } from '../../core/i18n/i18n.service';

export type StepId = 'scope' | 'conditions' | 'window' | 'actions' | 'review';

export const STEPS: { id: StepId; label: Key; hint: Key }[] = [
  { id: 'scope', label: 'rules.step.scope', hint: 'rules.step.scope.hint' },
  { id: 'conditions', label: 'rules.step.conditions', hint: 'rules.step.conditions.hint' },
  { id: 'window', label: 'rules.step.window', hint: 'rules.step.window.hint' },
  { id: 'actions', label: 'rules.step.actions', hint: 'rules.step.actions.hint' },
  { id: 'review', label: 'rules.step.review', hint: 'rules.step.review.hint' },
];

/** Which step a validation path belongs to (paths come from validateDraft). */
export function stepOfPath(path: string): Exclude<StepId, 'review'> {
  if (path === 'when' || path.startsWith('when.') || path.startsWith('when[')) return 'conditions';
  if (path === 'window' || path.startsWith('window.')) return 'window';
  if (path === 'cooldown' || path === 'actions' || path.startsWith('actions[') || path.startsWith('actions.')) return 'actions';
  return 'scope';
}
