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
  }
];
