import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

/** Spec 2026-11-18: light is the default for a new visitor (prospects); a stored choice wins. */
describe('ThemeService', () => {
  beforeEach(() => { try { localStorage.clear(); } catch { /* no storage */ } });

  it('is light for a new visitor', () => {
    expect(TestBed.inject(ThemeService).effective()).toBe('light');
  });

  it('keeps a stored dark choice', () => {
    localStorage.setItem('oci.theme', 'dark');
    expect(TestBed.inject(ThemeService).effective()).toBe('dark');
  });
});
