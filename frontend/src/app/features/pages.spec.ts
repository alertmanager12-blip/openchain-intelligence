import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../core/interceptors';
import { fakeAddress } from '../core/mock/mock-db';
import { mockBackendInterceptor } from '../core/mock/mock-backend.interceptor';
import { SettingsService } from '../core/settings.service';
import { AlertsPage } from './alerts/alerts';
import { CommunityPage } from './community/community';
import { ExplorerPage } from './explorer/explorer';
import { IntegrationsPage } from './integrations/integrations';
import { OverviewPage } from './overview/overview';
import { RiskPage } from './risk/risk';
import { RuleBuilderPage } from './rule-builder/rule-builder';
import { RuleCodePage } from './rule-code/rule-code';
import { SettingsPage } from './settings/settings';
import { SimulatorPage } from './simulator/simulator';
import { WalletPage } from './wallet/wallet';

/** Smoke test: every page renders against the mock backend without runtime errors. */
describe('pages (mock mode smoke test)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
  });

  async function mount<T>(cmp: Type<T>, inputs: Record<string, unknown> = {}): Promise<ComponentFixture<T>> {
    const f = TestBed.createComponent(cmp);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    await f.whenStable();
    await new Promise((r) => setTimeout(r, 450));
    await f.whenStable();
    return f;
  }

  async function render<T>(cmp: Type<T>, inputs: Record<string, unknown> = {}): Promise<HTMLElement> {
    return (await mount(cmp, inputs)).nativeElement as HTMLElement;
  }

  const addr = fakeAddress('ethereum', 3);
  const cases: [string, Type<unknown>, Record<string, unknown>, string][] = [
    ['overview', OverviewPage, {}, 'Recent signals'],
    ['explorer', ExplorerPage, { q: '0x' + 'fa4ede30'.repeat(8), network: 'ethereum' }, 'Token transfers'],
    ['wallet', WalletPage, { network: 'ethereum', address: addr }, 'Counterparties'],
    ['risk', RiskPage, { network: 'ethereum', address: addr }, 'Contributions'],
    ['alerts', AlertsPage, {}, 'All alerts'],
    ['builder', RuleBuilderPage, {}, 'apiVersion: openchain.io/rule/v1'],
    ['code', RuleCodePage, {}, 'Validate on server'],
    ['simulator', SimulatorPage, {}, 'Run dry run'],
    ['integrations', IntegrationsPage, {}, 'SOC webhook'],
    ['community', CommunityPage, {}, 'Demo'],
    ['settings', SettingsPage, {}, 'Users and roles'],
  ];

  for (const [name, cmp, inputs, expected] of cases) {
    it(`renders ${name}`, async () => {
      const el = await render(cmp, inputs);
      expect(el.textContent).toContain(expected);
      expect(el.querySelector('.state--error')).toBeNull();
    });
  }

  it('renders the alert rows of the inbox, one option per alert id', async () => {
    const el = await render(AlertsPage);
    const rows = el.querySelectorAll('[role="listbox"] [role="option"][id^="opt-al_"]');
    expect(rows.length).toBeGreaterThan(0);
    expect(rows[0].textContent?.trim()).toBeTruthy();
  });

  it('lists the API keys in the settings section of the same name', async () => {
    const f = await mount(SettingsPage);
    const el = f.nativeElement as HTMLElement;
    const tabs = [...el.querySelectorAll<HTMLButtonElement>('[role="tablist"] [role="tab"]')];
    expect(tabs.map((t) => t.textContent?.trim())).toEqual(['Connection', 'Appearance', 'API keys', 'Users and roles']);
    tabs.find((t) => t.textContent?.trim() === 'API keys')!.click();
    await new Promise((r) => setTimeout(r, 300));
    await f.whenStable();
    expect(el.querySelector('[role="tab"][aria-selected="true"]')?.textContent?.trim()).toBe('API keys');
    expect(el.querySelector('[role="tabpanel"]')?.getAttribute('aria-labelledby')).toBe('tab-keys');
    expect(el.textContent).toContain('oci_demo_');
    expect(el.querySelector('.state--error')).toBeNull();
  });

  it('opens the settings section named in the URL', async () => {
    const el = await render(SettingsPage, { section: 'users' });
    expect(el.querySelector('[role="tab"][aria-selected="true"]')?.textContent?.trim()).toBe('Users and roles');
  });

  it('opens an alert from a deep link and explains why it fired', async () => {
    const f = await mount(AlertsPage, { id: 'al_0001' });
    const el = f.nativeElement as HTMLElement;
    // the alert opens in the inbox pane: a region labelled by its title, next to the list (not a modal)
    const pane = el.querySelector('oci-alert-detail[role="region"]');
    expect(pane).not.toBeNull();
    const labelledBy = pane!.getAttribute('aria-labelledby');
    expect(document.getElementById(labelledBy!)?.textContent).toBeTruthy();
    expect(el.querySelector('[role="option"][aria-selected="true"]')?.id).toBe('opt-al_0001');
    expect(pane?.textContent).toContain('Why this alert fired');
    expect(pane?.textContent).toContain('Measured values');
    expect(pane?.querySelectorAll('table.cond tbody tr').length).toBeGreaterThan(0);
    expect(pane?.querySelector('oci-gauge[role="meter"]')).not.toBeNull();
    expect(pane?.textContent).toContain('Contributing transactions');
  });

  it('shows a retryable error state when the provider is down', async () => {
    const el = await render(RiskPage, { network: 'solana', address: fakeAddress('solana', 1) });
    expect(el.querySelector('.state--error')?.textContent).toContain('Provider unavailable');
    expect(el.querySelector('.state--error button')?.textContent).toContain('Retry');
  });
});
