import { Routes } from '@angular/router';

/** Der Buybot ist eine Ein-Seiten-Anwendung; alles andere landet auf dem Rechner. */
export const routes: Routes = [
  {path: '', redirectTo: 'buybot', pathMatch: 'full'},
  {
    path: 'buybot',
    loadComponent: () => import('./core/components/buybot/buybot.component').then(m => m.BuybotComponent)
  },
];
