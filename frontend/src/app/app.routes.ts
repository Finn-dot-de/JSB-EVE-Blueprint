import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth/auth.guard';

/** Wer die Rechteverwaltung öffnen darf - dieselbe Grenze wie im Server. */
const LEADERSHIP_OR_IT = ['ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN'];

export const routes: Routes = [
  { path: '', redirectTo: 'home', pathMatch: 'full' },
  {
    path: 'home',
    loadComponent: () => import('./core/components/home/home.component').then(m => m.HomeComponent)
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'groups/rights',
    canActivate: [roleGuard(LEADERSHIP_OR_IT)],
    loadComponent: () => import('./core/components/roles/roles.component').then(m => m.RolesComponent)
  },
  {
    path: 'fleet/tracking',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/fleet-manager/fleet-manager.component').then(m => m.FleetManagerComponent)
  },
  {
    path: 'fleet/join/:code',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/fleet-join/fleet-join.component').then(m => m.FleetJoinComponent)
  },
  {
    path: 'charlink',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/charlink/charlink.component').then(m => m.CharlinkComponent)
  },
  {
    // Dieselbe Grenze wie am Server (AccessRules.LEADERSHIP_OR_IT). Der Guard
    // ist Bequemlichkeit, keine Sicherung - durchgesetzt wird sie im Dienst.
    path: 'charlink/alt-suggestions',
    canActivate: [roleGuard(LEADERSHIP_OR_IT)],
    loadComponent: () => import('./core/components/alt-suggestions/alt-suggestions.component').then(m => m.AltSuggestionsComponent)
  },
  {
    path: 'charlink/stats',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/corp-stats/corp-stats.component').then(m => m.CorpStatsComponent)
  },
  {
    path: 'services',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/services/services.component').then(m => m.ServicesComponent)
  },
  {
    path: 'admin/discord',
    canActivate: [roleGuard(LEADERSHIP_OR_IT)],
    loadComponent: () => import('./core/components/discord-admin/discord-admin.component').then(m => m.DiscordAdminComponent)
  },
  {
    path: 'admin/navigation',
    canActivate: [roleGuard(LEADERSHIP_OR_IT)],
    loadComponent: () => import('./core/components/navigation-admin/navigation-admin.component').then(m => m.NavigationAdminComponent)
  },
  {
    path: 'fleet/doctrines',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/doctrines/doctrines.component').then(m => m.DoctrinesComponent)
  },
  {
    path: 'corp/mining',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/mining-tax/mining-tax.component').then(m => m.MiningTaxComponent)
  },
  {
    path: 'corp/assets',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/asset-audit/asset-audit.component').then(m => m.AssetAuditComponent)
  },
  {
    path: 'my/assets',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/my-assets/my-assets.component').then(m => m.MyAssetsComponent)
  },
  {
    path: 'industry',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/industry/industry.component').then(m => m.IndustryComponent)
  },
  {
    path: 'groups/manage',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/groups-board/groups-board.component').then(m => m.GroupsBoardComponent)
  },
  {
    path: 'academy',
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/academy/academy.component').then(m => m.AcademyComponent)
  },
];
