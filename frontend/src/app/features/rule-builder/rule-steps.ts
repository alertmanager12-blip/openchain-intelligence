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
