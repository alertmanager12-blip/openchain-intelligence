import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { I18nService } from '../../core/i18n/i18n.service';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { mockBackendInterceptor } from '../../core/mock/mock-backend.interceptor';
import { mockDb } from '../../core/mock/mock-routes';
import { SettingsService } from '../../core/settings.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { AlertsPage } from './alerts';

/** The alert list names a rule from the translations the alert carries (ruleTranslations), in the console language. */
describe('Alerts list rule names', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), MATERIAL_DEFAULTS, provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
    const a = mockDb().alerts[0];
    a.ruleName = 'Wallet drained (>80% of balance out in 2 minutes)';
    a.ruleTranslations = { fr: { name: 'Portefeuille vidé (plus de 80 % du solde sorti en 2 minutes)' } };
  });
  afterEach(() => localStorage.removeItem('oci.lang'));

  const settle = async (f: ComponentFixture<unknown>) => { await f.whenStable(); await new Promise((r) => setTimeout(r, 450)); await f.whenStable(); };
  const titles = (f: ComponentFixture<AlertsPage>) => [...(f.nativeElement as HTMLElement).querySelectorAll('.it-title')].map((e) => e.textContent ?? '');

  it('shows the French name in French and the English name after switching, without navigating', async () => {
    const f = TestBed.createComponent(AlertsPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    expect(titles(f).some((t) => t.includes('Portefeuille vidé'))).toBe(true);
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(titles(f).some((t) => t.includes('Wallet drained'))).toBe(true);
    expect(titles(f).some((t) => t.includes('Portefeuille vidé'))).toBe(false);
    f.nativeElement.remove();
  });
});
