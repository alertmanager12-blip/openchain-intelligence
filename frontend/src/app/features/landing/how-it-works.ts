import { ChangeDetectionStrategy, Component } from '@angular/core';
import { LANDING_COPY } from './landing-content';

/** The pipeline in four steps: a real sequence, hence the numbering and the connecting rail. */
@Component({
  selector: 'oci-cs-how',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    section { background: var(--cs-bg-2); }
    ol { list-style: none; margin: 56px 0 0; padding: 0; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0; counter-reset: step; position: relative; }
    ol::before { content: ''; position: absolute; left: 0; right: 0; top: 19px; height: 1px; background: linear-gradient(90deg, var(--cs-accent), var(--cs-flow) 60%, var(--cs-line-2)); opacity: .55; }
    li { position: relative; padding-right: 28px; }
    .step-num {
      position: relative; display: grid; place-items: center; width: 40px; height: 40px; border-radius: 50%;
      background: var(--cs-bg-2); border: 1px solid var(--cs-line-2); font: 600 14px var(--cs-mono); color: var(--cs-text);
    }
    li:first-child .step-num { border-color: var(--cs-accent); color: var(--cs-accent); }
    li h3 { margin-top: 22px; font-size: 22px; font-weight: 600; letter-spacing: -0.02em; }
    .sum { margin-top: 6px ; font-size: 16px; color: var(--cs-text); }
    .det { margin-top: 10px ; color: var(--cs-muted); font-size: 15px; line-height: 1.55; }
    .how-note { margin-top: 48px; color: var(--cs-muted); font-size: 15px; max-width: 70ch; }
    @media (max-width: 900px) {
      ol { grid-template-columns: 1fr; gap: 28px; }
      ol::before { left: 19px; right: auto; top: 0; bottom: 0; width: 1px; height: auto; background: linear-gradient(180deg, var(--cs-accent), var(--cs-line-2)); }
      li { display: grid; grid-template-columns: 40px 1fr; column-gap: 20px; padding: 0; }
      li > :not(.step-num) { grid-column: 2; }
      li h3 { margin-top: 6px; }
    }
  `,
  template: `
    <section class="cs-section" id="how-it-works" aria-labelledby="how-title">
      <div class="cs-wrap">
        <h2 class="cs-h2" id="how-title">From a new block to an alert, in four steps.</h2>
        <p class="cs-lede">You don’t need to know how a blockchain stores transfers. ChainSentinel reads them, explains what they mean and tells you when to look.</p>
        <ol>
          @for (s of steps; track s.name; let i = $index) {
            <li>
              <span class="step-num" aria-hidden="true">{{ i + 1 }}</span>
              <h3>{{ s.name }}</h3>
              <p class="sum">{{ s.summary }}</p>
              <p class="det">{{ s.detail }}</p>
            </li>
          }
        </ol>
        <p class="how-note">Everything runs on your infrastructure: the indexers, the detectors, the database and the console. Nothing leaves your servers except the requests to the blockchain nodes you configure and the notifications you set up.</p>
      </div>
    </section>
  `,
})
export class HowItWorksSection {
  protected readonly steps = LANDING_COPY.steps;
}
