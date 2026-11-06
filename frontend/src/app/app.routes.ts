import { Routes } from '@angular/router';

const t = (s: string) => `${s} – OpenChain Intelligence`;

export const routes: Routes = [
  { path: '', title: t('Overview'), loadComponent: () => import('./features/overview/overview').then((m) => m.OverviewPage) },
  { path: 'explorer', title: t('Explorer'), loadComponent: () => import('./features/explorer/explorer').then((m) => m.ExplorerPage) },
  { path: 'wallets/:network/:address', title: t('Wallet'), loadComponent: () => import('./features/wallet/wallet').then((m) => m.WalletPage) },
  { path: 'risk', title: t('Wallet risk'), loadComponent: () => import('./features/risk/risk').then((m) => m.RiskPage) },
  { path: 'risk/:network/:address', title: t('Wallet risk'), loadComponent: () => import('./features/risk/risk').then((m) => m.RiskPage) },
  { path: 'investigation', title: t('Fund flow graph'), loadComponent: () => import('./features/investigation/investigation').then((m) => m.InvestigationPage) },
  { path: 'alerts', title: t('Alerts'), loadComponent: () => import('./features/alerts/alerts').then((m) => m.AlertsPage) },
  { path: 'rules/builder', title: t('Rule builder'), loadComponent: () => import('./features/rule-builder/rule-builder').then((m) => m.RuleBuilderPage) },
  { path: 'rules/code', title: t('Rules as code'), loadComponent: () => import('./features/rule-code/rule-code').then((m) => m.RuleCodePage) },
  { path: 'rules/simulator', title: t('Rule simulator'), loadComponent: () => import('./features/simulator/simulator').then((m) => m.SimulatorPage) },
  { path: 'integrations', title: t('Integrations'), loadComponent: () => import('./features/integrations/integrations').then((m) => m.IntegrationsPage) },
  { path: 'community', title: t('Community labels'), loadComponent: () => import('./features/community/community').then((m) => m.CommunityPage) },
  { path: 'settings', title: t('Settings'), loadComponent: () => import('./features/settings/settings').then((m) => m.SettingsPage) },
  { path: '**', redirectTo: '' },
];
