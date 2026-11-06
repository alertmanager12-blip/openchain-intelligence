import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { SettingsService } from '../settings.service';

/**
 * Mock mode (development builds only): answers /api/v1 from in-memory fake data. Production builds replace this
 * file with mock-backend.interceptor.prod.ts (angular.json fileReplacements), so they contain no fake data at all.
 */
export const mockBackendInterceptor: HttpInterceptorFn = (req, next) => {
  if (!environment.allowMock || !inject(SettingsService).connection().mock || !req.url.startsWith(environment.apiBaseUrl)) return next(req);
  return from(import('./mock-routes')).pipe(switchMap((m) => m.handleMock(req)));
};
