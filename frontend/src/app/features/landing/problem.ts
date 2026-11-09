import { ChangeDetectionStrategy, Component } from '@angular/core';
import { LANDING_COPY } from './landing-content';

@Component({
  selector: 'oci-cs-problem',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .pb-grid { display: grid; grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr); gap: 64px; align-items: start; }
    dl { margin: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border-top: 1px solid var(--cs-line); }
    .pain { padding: 22px 24px 24px 0; border-bottom: 1px solid var(--cs-line); }
    .pain:nth-child(odd) { border-right: 1px solid var(--cs-line); }
    .pain:nth-child(even) { padding-left: 24px; padding-right: 0; }
    dt { font-weight: 600; font-size: 16px; }
    dd { margin: 6px 0 0; color: var(--cs-muted); font-size: 15px; line-height: 1.55; }
    .answer { margin-top: 32px; padding-left: 16px; border-left: 2px solid var(--cs-accent); font-size: 17px; line-height: 1.6; max-width: 60ch; }
    @media (max-width: 900px) {
      .pb-grid { grid-template-columns: 1fr; gap: 40px; }
    }
    @media (max-width: 560px) {
      dl { grid-template-columns: 1fr; }
      .pain, .pain:nth-child(even) { padding: 18px 0; border-right: 0; }
    }
  `,
  template: `
    <section class="cs-section" aria-labelledby="problem-title">
      <div class="cs-wrap pb-grid">
        <div>
          <h2 class="cs-h2" id="problem-title">{{ copy.title }}</h2>
          <p class="cs-lede">{{ copy.lede }}</p>
          <p class="answer">{{ copy.answer }}</p>
        </div>
        <dl>
          @for (p of copy.pains; track p.title) {
            <div class="pain"><dt>{{ p.title }}</dt><dd>{{ p.text }}</dd></div>
          }
        </dl>
      </div>
    </section>
  `,
})
export class ProblemSection {
  protected readonly copy = LANDING_COPY.problem;
}
