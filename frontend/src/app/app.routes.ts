import {Routes} from '@angular/router';
import {authGuard} from './core/auth/auth.guard';

export const routes: Routes = [
  {path: '', redirectTo: 'buybot', pathMatch: 'full'},
  {
    path: 'buybot',
    loadComponent: () => import('./core/components/buybot/buybot.component').then(m => m.BuybotComponent)
  },
];
