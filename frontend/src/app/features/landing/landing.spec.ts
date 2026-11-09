import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LANDING_COPY } from './landing-content';
import { LandingPage } from './landing';
import { PUBLIC_REPOSITORY_URL, publicLinks } from './public-links';

describe('Landing page', () => {
  let f: ComponentFixture<LandingPage>;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    f = TestBed.createComponent(LandingPage);
    el = f.nativeElement;
    document.body.appendChild(el);
    await f.whenStable();
  });

  afterEach(() => f.destroy());

  it('answers what it is, with the brief headline and a way into the console', () => {
    expect(el.querySelector('h1')?.textContent).toContain('See Beyond Blockchain Transactions.');
    const starts = [...el.querySelectorAll<HTMLAnchorElement>('a')].filter((a) => a.getAttribute('href') === '/overview');
    expect(starts.length).toBeGreaterThanOrEqual(3); // header, hero, final call to action
    const signIn = [...el.querySelectorAll<HTMLAnchorElement>('a')].find((a) => a.textContent?.trim() === 'Sign in');
    expect(signIn?.getAttribute('href')).toBe('/settings?section=connection');
  });

  it('has no dead in-page anchor and no empty link', () => {
    for (const a of el.querySelectorAll<HTMLAnchorElement>('a')) {
      const href = a.getAttribute('href');
      expect(href, a.outerHTML).toBeTruthy();
      expect(href).not.toBe('#');
      if (href!.startsWith('#')) expect(el.querySelector(href!), `target of ${href}`).not.toBeNull();
    }
  });

  it('points every external link at the public repository, in a new tab', () => {
    const repo = PUBLIC_REPOSITORY_URL!;
    expect(publicLinks().repository).toBe(repo);
    const external = [...el.querySelectorAll<HTMLAnchorElement>('a')].filter((a) => /^https?:/.test(a.getAttribute('href') ?? ''));
    expect(external.length).toBeGreaterThan(0);
    for (const a of external) {
      expect(a.getAttribute('href')!.startsWith(repo), a.outerHTML).toBe(true);
      expect(a.getAttribute('rel')).toContain('noopener');
    }
    expect(el.textContent).toContain('View on GitHub');
    expect(el.querySelector('#community')?.textContent).not.toContain('being prepared');
  });

  it('derives the repository links from one URL', () => {
    expect(publicLinks('https://github.com/acme/chainsentinel/')).toEqual({
      repository: 'https://github.com/acme/chainsentinel',
      issues: 'https://github.com/acme/chainsentinel/issues',
      contributing: 'https://github.com/acme/chainsentinel/blob/main/CONTRIBUTING.md',
      docs: 'https://github.com/acme/chainsentinel/tree/main/docs',
      license: 'https://github.com/acme/chainsentinel/blob/main/LICENSE',
    });
  });

  it('labels illustrative data and states the availability of every capability', () => {
    expect(el.querySelector('oci-cs-hero-trace')?.textContent).toContain('Illustrative data');
    expect(el.querySelector('#investigation')?.textContent).toContain('Illustrative data');
    const items = LANDING_COPY.pillars.flatMap((p) => p.items);
    expect(el.querySelectorAll('#capabilities [role=tabpanel] li .cs-status').length).toBe(items.length);
    expect(el.querySelector('#developers')?.textContent).toMatch(/SDKs and MCP server\s*Planned/);
    expect(el.textContent).toContain('Apache-2.0');
  });

  it('switches capabilities with the arrow keys (tabs pattern)', async () => {
    const tabs = [...el.querySelectorAll<HTMLButtonElement>('[role=tab]')];
    expect(tabs.map((t) => t.querySelector('b')?.textContent)).toEqual(['Understand', 'Predict', 'Investigate', 'Act']);
    tabs[0].focus();
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await f.whenStable();
    expect(tabs[3].getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(tabs[3]);
    expect(el.querySelector<HTMLElement>('#panel-act')?.hidden).toBe(false);
    expect(el.querySelector<HTMLElement>('#panel-understand')?.hidden).toBe(true);
  });

  it('opens and closes the mobile menu', async () => {
    const burger = el.querySelector<HTMLButtonElement>('.cs-burger')!;
    const sheet = el.querySelector<HTMLElement>('#cs-sheet')!;
    expect(sheet.hidden).toBe(true);
    burger.click();
    await f.whenStable();
    expect(burger.getAttribute('aria-expanded')).toBe('true');
    expect(sheet.hidden).toBe(false);
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await f.whenStable();
    expect(sheet.hidden).toBe(true);
  });
});
