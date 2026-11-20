import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor, errorInterceptor } from '../../core/interceptors';
import { I18nService, translate } from '../../core/i18n/i18n.service';
import { Alert } from '../../core/models';
import { mockBackendInterceptor } from '../../core/mock/mock-backend.interceptor';
import { SettingsService } from '../../core/settings.service';
import { MATERIAL_DEFAULTS } from '../../material-defaults';
import { AlertsPage } from './alerts';
import { alertLines } from './alert-lines';
import { isoDuration, opSymbol } from './evidence';

/** Spec 2026-11-19 task 11: the alerts inbox and its pane in French, engine sentences kept in English, live language switch. */
describe('Alerts screen in French', () => {
  beforeEach(() => {
    localStorage.setItem('oci.lang', 'fr');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), MATERIAL_DEFAULTS, provideHttpClient(withInterceptors([authInterceptor, errorInterceptor, mockBackendInterceptor]))],
    });
    TestBed.inject(SettingsService).update({ mock: true });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });
  afterEach(() => localStorage.removeItem('oci.lang'));

  const settle = async (f: ComponentFixture<unknown>) => {
    await f.whenStable();
    await new Promise((r) => setTimeout(r, 450));
    await f.whenStable();
    f.detectChanges();
  };

  async function mount() {
    const f = TestBed.createComponent(AlertsPage);
    document.body.appendChild(f.nativeElement);
    await settle(f);
    (f.nativeElement as HTMLElement).querySelector<HTMLElement>('[role="option"]')!.click();
    await settle(f);
    return f;
  }

  it('shows the heading, the filters, the status names and the lifecycle actions in French', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('h1')?.textContent).toContain('Alertes');
    expect(el.querySelector('[role="toolbar"]')?.getAttribute('aria-label')).toBe('Filtrer les alertes');
    expect(el.querySelector('.seg')?.textContent).toContain('Ouverte');
    expect(el.querySelector('.seg')?.textContent).toContain('Faux positif');
    const acts = el.querySelector('oci-alert-detail .acts')?.textContent ?? '';
    expect(acts).toContain('Prendre en charge');
    expect(acts).toContain('Faux positif');
    f.destroy();
  });

  it('marks the engine sentences lang="en" and re-renders in English without navigation', async () => {
    const f = await mount();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('oci-alert-detail [lang="en"]')).not.toBeNull();
    TestBed.inject(I18nService).set('en');
    await settle(f);
    expect(el.querySelector('[role="toolbar"]')?.getAttribute('aria-label')).toBe('Filter alerts');
    expect(el.querySelector('oci-alert-detail .acts')?.textContent).toContain('Acknowledge');
    expect(el.querySelector('oci-alert-detail .acts')?.textContent).not.toContain('Prendre en charge');
    f.destroy();
  });
});

describe('Alert helpers in French', () => {
  it('writes durations and operators in the console language', () => {
    expect(isoDuration('PT1H30M', 'fr')).toBe('1 h 30 min');
    expect(isoDuration('P1D', 'fr')).toBe('1 j');
    expect(isoDuration('PT45S', 'fr')).toBe('45 s');
    expect(opSymbol('not_in', 'fr')).toBe('hors de');
    expect(opSymbol('gte', 'fr')).toBe('≥');
  });

  it('keeps the engine wording of an alert one click away, or marks it English when no builder exists', () => {
    const base = { evidenceLevel: 'RISK_SIGNAL', summary: 'Fund splitting', ruleId: 'x' } as unknown as Alert;
    const plain = alertLines({ ...base, evidenceSummary: '24 recipients in 3 minutes' } as Alert, 'fr')[0];
    expect(plain).toMatchObject({ level: 'INFERRED', text: '24 recipients in 3 minutes', detail: 'signal de risque', textLang: 'en' });
    const built = alertLines({ ...base, ruleId: 'large-usdt-transfer', evidenceSummary: 'Large transfer, engine words',
      matchedEvent: { 'transaction.amount': 5, 'transaction.token.symbol': 'USDT', 'transaction.from': '0x1234567890abcdef', 'transaction.to': '0xfedcba0987654321' } } as unknown as Alert, 'fr')[0];
    expect(built.text).toContain('Gros transfert');
    expect(built.original).toBe('Large transfer, engine words');
    expect(built.textLang).toBeUndefined();
  });

  it('writes plurals and the partial-withdrawal sentence in French', () => {
    expect(translate('fr', 'alerts.count', { count: 0 })).toBe('0 alerte');
    expect(translate('fr', 'alerts.count', { count: 2 })).toBe('2 alertes');
    expect(translate('fr', 'alerts.partial.body', { withdrawn: 1, matched: 3, count: 3 })).toContain('1 sur 3 événements correspondants ont quitté');
  });
});
