import { Routes } from '@angular/router';

/**
 * '/' is the public landing page (outside the console shell, no Material defaults). The console pages load lazily,
 * together with the Material defaults they share (see features/feature.routes.ts).
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', title: 'ChainSentinel – The open intelligence engine for blockchain',
    loadComponent: () => import('./features/landing/landing').then((m) => m.LandingPage) },
  { path: '', loadChildren: () => import('./features/feature.routes').then((m) => m.FEATURE_ROUTES) },
];
