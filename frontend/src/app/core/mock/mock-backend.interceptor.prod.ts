import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Production replacement of mock-backend.interceptor.ts (angular.json "production" fileReplacements):
 * a pass-through, so production bundles contain no fake data and can never serve it.
 */
export const mockBackendInterceptor: HttpInterceptorFn = (req, next) => next(req);
