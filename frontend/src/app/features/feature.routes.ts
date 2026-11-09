import { Routes } from '@angular/router';
import { MATERIAL_DEFAULTS } from '../material-defaults';

const t = (s: string) => `${s} – ChainSentinel`;

/** Every page, under one parent route whose environment injector carries the Material defaults (loaded lazily). */
export const FEATURE_ROUTES: Routes = [
  { path: '', providers: MATERIAL_DEFAULTS, children: [
  { path: 'overview', title: t('Overview'), loadComponent: () => import('./overview/overview').then((m) => m.OverviewPage) },
  { path: 'explorer', title: t('Explorer'), loadComponent: () => import('./explorer/explorer').then((m) => m.ExplorerPage) },
  { path: 'wallets/:network/:address', title: t('Wallet'), loadComponent: () => import('./wallet/wallet').then((m) => m.WalletPage) },
  { path: 'risk', title: t('Wallet risk'), loadComponent: () => import('./risk/risk').then((m) => m.RiskPage) },
  { path: 'risk/:network/:address', title: t('Wallet risk'), loadComponent: () => import('./risk/risk').then((m) => m.RiskPage) },
  { path: 'investigation', title: t('Fund flow graph'), loadComponent: () => import('./investigation/investigation').then((m) => m.InvestigationPage) },
  { path: 'alerts', title: t('Alerts'), loadComponent: () => import('./alerts/alerts').then((m) => m.AlertsPage) },
  { path: 'rules/builder', title: t('Rule builder'), loadComponent: () => import('./rule-builder/rule-builder').then((m) => m.RuleBuilderPage) },
  { path: 'rules/code', title: t('Rules as code'), loadComponent: () => import('./rule-code/rule-code').then((m) => m.RuleCodePage) },
  { path: 'rules/simulator', title: t('Rule simulator'), loadComponent: () => import('./simulator/simulator').then((m) => m.SimulatorPage) },
  { path: 'watchlist', title: t('Watchlist'), loadComponent: () => import('./watchlist/watchlist').then((m) => m.WatchlistPage) },
  { path: 'integrations', title: t('Integrations'), loadComponent: () => import('./integrations/integrations').then((m) => m.IntegrationsPage) },
  { path: 'community', title: t('Community labels'), loadComponent: () => import('./community/community').then((m) => m.CommunityPage) },
  { path: 'settings', title: t('Settings'), loadComponent: () => import('./settings/settings').then((m) => m.SettingsPage) },
  { path: '**', redirectTo: '' },
  ] },
];
