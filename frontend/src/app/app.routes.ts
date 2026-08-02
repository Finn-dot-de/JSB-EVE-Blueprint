import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guard';

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
    canActivate: [authGuard],
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
    canActivate: [authGuard],
    loadComponent: () => import('./core/components/discord-admin/discord-admin.component').then(m => m.DiscordAdminComponent)
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
];
