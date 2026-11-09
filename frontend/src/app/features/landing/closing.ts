import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LANDING_COPY } from './landing-content';
import { publicLinks } from './public-links';

/** Open source principles, use cases, community and the final call to action. License checked in LICENSE (Apache-2.0). */
@Component({
  selector: 'oci-cs-closing',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .os { display: grid; grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr); gap: 64px; align-items: start; }
    .license { display: inline-flex; align-items: baseline; gap: 10px; margin-top: 28px; padding: 12px 16px; border: 1px solid var(--cs-line-2); border-radius: 8px; }
    .license b { font: 600 15px var(--cs-mono); color: var(--cs-accent); }
    .license span { color: var(--cs-muted); font-size: 14px; }
    .os .cs-btn { margin-top: 24px; }
    .values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; margin: 0; padding: 0; list-style: none; border-top: 1px solid var(--cs-line); }
    .values li { padding: 20px 20px 20px 0; border-bottom: 1px solid var(--cs-line); }
    .values li:nth-child(even) { padding: 20px 0 20px 20px; border-left: 1px solid var(--cs-line); }
    .values b { display: block; font-size: 16px; font-weight: 600; }
    .values span { display: block; margin-top: 4px; color: var(--cs-muted); font-size: 14px; line-height: 1.55; }

    .uses { margin: 48px 0 0; padding: 0; list-style: none; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; background: var(--cs-line); border: 1px solid var(--cs-line); border-radius: var(--cs-r); overflow: hidden; }
    .uses li { padding: 24px; background: var(--cs-bg); }
    .uses b { display: block; font-size: 17px; font-weight: 600; letter-spacing: -0.01em; }
    .uses span { display: block; margin-top: 8px; color: var(--cs-muted); font-size: 15px; line-height: 1.55; }
    .caveat { margin-top: 20px; color: var(--cs-faint); font-size: 14px; max-width: 80ch; }

    .comm { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr); gap: 56px; align-items: center; }
    .comm-links { display: grid; gap: 10px; }
    .comm-links a { display: flex; justify-content: space-between; padding: 16px 18px; border: 1px solid var(--cs-line); border-radius: 8px; text-decoration: none; }
    .comm-links a:hover { border-color: var(--cs-line-2); background: var(--cs-surface); }
    .comm-links a span { color: var(--cs-muted); }
    .soon { padding: 20px; border: 1px dashed var(--cs-line-2); border-radius: 10px; color: var(--cs-muted); font-size: 15px; line-height: 1.6; }
    .soon b { color: var(--cs-text); font-weight: 600; }

    .final { text-align: center; padding: 128px 0; border-top: 1px solid var(--cs-line); background: radial-gradient(ellipse 50% 60% at 50% 100%, rgba(61, 220, 151, 0.09), transparent 70%); }
    .final h2 { font-size: clamp(32px, 4.6vw, 56px); line-height: 1.05; font-weight: 650; letter-spacing: -0.035em; max-width: 18ch; margin: 0 auto; text-wrap: balance; }
    .final p { margin: 20px auto 0; max-width: 56ch; color: var(--cs-muted); font-size: 18px; line-height: 1.6; }
    .final .final-row { display: flex; flex-wrap: wrap; justify-content: center; gap: 12px; margin-top: 36px; }

    @media (max-width: 960px) {
      .os, .comm { grid-template-columns: 1fr; gap: 40px; }
      .uses { grid-template-columns: 1fr 1fr; }
    }
    @media (max-width: 600px) {
      .uses, .values { grid-template-columns: 1fr; }
      .values li, .values li:nth-child(even) { padding: 18px 0; border-left: 0; }
      .final { padding: 88px 0; }
    }
  `,
  template: `
    <section class="cs-section" id="open-source" aria-labelledby="os-title">
      <div class="cs-wrap os">
        <div>
          <h2 class="cs-h2" id="os-title">Open Intelligence. Transparent by Design.</h2>
          <p class="cs-lede">A risk score you cannot inspect is a risk in itself. Every detector, weight and rule in ChainSentinel can be read, tested and changed.</p>
          <div class="license"><b>Apache-2.0</b><span>Use it, modify it, run it commercially.</span></div>
          @if (links.repository; as r) { <div><a class="cs-btn" [href]="r" rel="noopener" target="_blank">Explore the source code</a></div> }
        </div>
        <ul class="values">
          <li><b>Open source</b><span>The whole platform, from indexers to console, under one permissive license.</span></li>
          <li><b>Self-hosted</b><span>Runs with Docker Compose on your own server. No account, no usage quota.</span></li>
          <li><b>Extensible architecture</b><span>Network providers, detectors and notification channels sit behind small interfaces: adding one is a contained change.</span></li>
          <li><b>Explainable detection</b><span>Each signal carries its evidence and level; each score lists what raised it and by how much.</span></li>
          <li><b>Community intelligence</b><span>Analysts can report labels for review; nothing is applied without moderation.</span></li>
          <li><b>Data sovereignty</b><span>Wallets you watch and alerts you raise stay in your PostgreSQL database.</span></li>
        </ul>
      </div>
    </section>

    <section class="cs-section" id="use-cases" aria-labelledby="uc-title">
      <div class="cs-wrap">
        <h2 class="cs-h2" id="uc-title">Built for the teams that answer for the money.</h2>
        <ul class="uses">
          @for (u of useCases; track u.who) { <li><b>{{ u.who }}</b><span>{{ u.value }}</span></li> }
        </ul>
        <p class="caveat">ChainSentinel produces signals and evidence for review. It does not replace a regulated compliance program or the judgment of your analysts.</p>
      </div>
    </section>

    <section class="cs-section" id="community" aria-labelledby="comm-title">
      <div class="cs-wrap comm">
        <div>
          <h2 class="cs-h2" id="comm-title">Build the future of blockchain intelligence with us.</h2>
          <p class="cs-lede">Detectors, network providers, rules and labels all get better with more eyes. Developers, investigators and researchers are welcome.</p>
        </div>
        @if (links.repository; as r) {
          <nav class="comm-links" aria-label="Contribute">
            <a [href]="r" rel="noopener" target="_blank">GitHub<span>Source code</span></a>
            <a [href]="links.contributing" rel="noopener" target="_blank">Contribution guide<span>How to propose a change</span></a>
            <a [href]="links.docs" rel="noopener" target="_blank">Documentation<span>Guides, ADRs, API</span></a>
            <a [href]="links.issues" rel="noopener" target="_blank">Issues<span>Bugs and ideas</span></a>
          </nav>
        } @else {
          <p class="soon"><b>The public repository is being prepared.</b> Links to the source code, the contribution guide, the documentation and the issue tracker will appear here when it opens.</p>
        }
      </div>
    </section>

    <section class="final" aria-labelledby="final-title">
      <div class="cs-wrap">
        <h2 id="final-title">Make blockchain intelligence open to everyone.</h2>
        <p>Explore ChainSentinel, discover its capabilities and help build the next generation of open blockchain intelligence.</p>
        <div class="final-row">
          <a class="cs-btn cs-btn--primary" routerLink="/overview">Get Started</a>
          @if (links.repository; as r) { <a class="cs-btn" [href]="r" rel="noopener" target="_blank">Explore GitHub</a> }
        </div>
      </div>
    </section>
  `,
})
export class ClosingSections {
  protected readonly links = publicLinks();
  protected readonly useCases = LANDING_COPY.useCases;
}
