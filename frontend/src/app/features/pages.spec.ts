import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Type } from '@angular/core';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogHarness } from '@angular/material/dialog/testing';
import { MatTabGroupHarness } from '@angular/material/tabs/testing';
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
    ['alerts', AlertsPage, {}, 'al_'],
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

  it('lists the API keys in the settings tab of the same name', async () => {
    const f = await mount(SettingsPage);
    const tabs = await TestbedHarnessEnvironment.loader(f).getHarness(MatTabGroupHarness);
    expect((await Promise.all((await tabs.getTabs()).map((t) => t.getLabel())))).toEqual(['Connection', 'Appearance', 'API keys', 'Users and roles']);
    await tabs.selectTab({ label: 'API keys' });
    await new Promise((r) => setTimeout(r, 300));
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).textContent).toContain('oci_demo_');
    expect((f.nativeElement as HTMLElement).querySelector('.state--error')).toBeNull();
  });

  it('opens an alert from a deep link and explains why it fired', async () => {
    const f = await mount(AlertsPage, { id: 'al_0001' });
    // the alert opens in a MatDialog (rendered in the overlay container), labelled by its title
    const dialog = await TestbedHarnessEnvironment.documentRootLoader(f).getHarness(MatDialogHarness);
    const labelledBy = await dialog.getAriaLabelledby();
    expect(document.getElementById(labelledBy!)?.textContent).toBeTruthy();
    const sheet = document.querySelector('mat-dialog-container[role="dialog"]');
    expect(sheet?.getAttribute('aria-modal')).toBe('true');
    expect(sheet?.textContent).toContain('Why this alert fired');
    expect(sheet?.textContent).toContain('Measured values');
    expect(sheet?.querySelectorAll('table.cond tbody tr').length).toBeGreaterThan(0);
    expect(sheet?.querySelector('oci-gauge[role="meter"]')).not.toBeNull();
    expect(sheet?.textContent).toContain('Contributing transactions');
  });

  it('shows a retryable error state when the provider is down', async () => {
    const el = await render(RiskPage, { network: 'solana', address: fakeAddress('solana', 1) });
    expect(el.querySelector('.state--error')?.textContent).toContain('Provider unavailable');
    expect(el.querySelector('.state--error button')?.textContent).toContain('Retry');
  });
});
