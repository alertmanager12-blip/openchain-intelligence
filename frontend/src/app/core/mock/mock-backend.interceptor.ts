import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SettingsService } from '../settings.service';

/**
 * Mock mode: answers /api/v1 from in-memory fake data. The mock store is code-split and only downloaded
 * when mock mode is on, so production bundles do not carry it.
 */
export const mockBackendInterceptor: HttpInterceptorFn = (req, next) => {
  if (!inject(SettingsService).connection().mock || !req.url.startsWith(environment.apiBaseUrl)) return next(req);
  return from(import('./mock-routes')).pipe(switchMap((m) => m.handleMock(req)));
};
