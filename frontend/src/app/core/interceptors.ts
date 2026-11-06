import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { toApiError } from './api-error';
import { SettingsService } from './settings.service';

/** Adds the configured credential to /api requests only (never to third-party URLs). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(environment.apiBaseUrl)) return next(req);
  const { authMode, token } = inject(SettingsService).connection();
  if (!token) return next(req);
  if (authMode === 'bearer') return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
  if (authMode === 'apiKey') return next(req.clone({ setHeaders: { 'X-API-Key': token } }));
  return next(req);
};

/** Converts every HTTP failure into an ApiError (RFC 9457 aware), so components handle a single error type. */
export const errorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(catchError((e: unknown) => throwError(() => toApiError(e))));
