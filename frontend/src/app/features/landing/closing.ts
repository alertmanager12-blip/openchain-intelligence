import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18nService, Key } from '../../core/i18n/i18n.service';
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
    .license b { font: 600 15px var(--cs-mono); color: var(--cs-accent); white-space: nowrap; }
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
    .soon ::ng-deep b { color: var(--cs-text); font-weight: 600; }

    .final { text-align: center; padding: 128px 0; border-top: 1px solid var(--cs-line); background: radial-gradient(ellipse 50% 60% at 50% 100%, rgba(70, 207, 219, 0.1), transparent 70%); }
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
          <h2 class="cs-h2" id="os-title">{{ i18n.t('landing.os.title') }}</h2>
          <p class="cs-lede">{{ i18n.t('landing.os.lede') }}</p>
          <div class="license"><b>{{ license }}</b><span>{{ i18n.t('landing.os.license') }}</span></div>
          @if (links.repository; as r) { <div><a class="cs-btn" [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.os.source') }}</a></div> }
        </div>
        <ul class="values">
          @for (v of values; track v.t) { <li><b>{{ i18n.t(v.t) }}</b><span>{{ i18n.t(v.d) }}</span></li> }
        </ul>
      </div>
    </section>

    <section class="cs-section" id="use-cases" aria-labelledby="uc-title">
      <div class="cs-wrap">
        <h2 class="cs-h2" id="uc-title">{{ i18n.t('landing.uc.title') }}</h2>
        <ul class="uses">
          @for (u of useCases; track u.who) { <li><b>{{ i18n.t(u.who) }}</b><span>{{ i18n.t(u.value) }}</span></li> }
        </ul>
        <p class="caveat">{{ i18n.t('landing.uc.caveat') }}</p>
      </div>
    </section>

    <section class="cs-section" id="community" aria-labelledby="comm-title">
      <div class="cs-wrap comm">
        <div>
          <h2 class="cs-h2" id="comm-title">{{ i18n.t('landing.comm.title') }}</h2>
          <p class="cs-lede">{{ i18n.t('landing.comm.lede') }}</p>
        </div>
        @if (links.repository; as r) {
          <nav class="comm-links" [attr.aria-label]="i18n.t('landing.comm.aria')">
            <a [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.github') }}<span>{{ i18n.t('landing.comm.source') }}</span></a>
            <a [href]="links.contributing" rel="noopener" target="_blank">{{ i18n.t('landing.foot.contributing') }}<span>{{ i18n.t('landing.comm.contributing') }}</span></a>
            <a [href]="links.docs" rel="noopener" target="_blank">{{ i18n.t('landing.nav.docs') }}<span>{{ i18n.t('landing.comm.docs') }}</span></a>
            <a [href]="links.issues" rel="noopener" target="_blank">{{ i18n.t('landing.foot.issues') }}<span>{{ i18n.t('landing.comm.issues') }}</span></a>
          </nav>
        } @else {
          <p class="soon" [innerHTML]="i18n.t('landing.comm.soon')"></p>
        }
      </div>
    </section>

    <section class="final" aria-labelledby="final-title">
      <div class="cs-wrap">
        <h2 id="final-title">{{ i18n.t('landing.final.title') }}</h2>
        <p>{{ i18n.t('landing.final.lede') }}</p>
        <div class="final-row">
          <a class="cs-btn cs-btn--primary" routerLink="/overview">{{ i18n.t('landing.final.start') }}</a>
          @if (links.repository; as r) { <a class="cs-btn" [href]="r" rel="noopener" target="_blank">{{ i18n.t('landing.final.github') }}</a> }
        </div>
      </div>
    </section>
  `,
})
export class ClosingSections {
  protected readonly i18n = inject(I18nService);
  protected readonly license = 'Apache-2.0';
  protected readonly values: readonly { t: Key; d: Key }[] = [
    { t: 'landing.value.open.t', d: 'landing.value.open.d' },
    { t: 'landing.value.self.t', d: 'landing.value.self.d' },
    { t: 'landing.value.ext.t', d: 'landing.value.ext.d' },
    { t: 'landing.value.explain.t', d: 'landing.value.explain.d' },
    { t: 'landing.value.community.t', d: 'landing.value.community.d' },
    { t: 'landing.value.sovereign.t', d: 'landing.value.sovereign.d' },
  ];
  protected readonly links = publicLinks();
  protected readonly useCases = LANDING_COPY.useCases;
}
