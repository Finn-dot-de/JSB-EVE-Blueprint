import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Observable } from 'rxjs';
import { filter, map } from 'rxjs/operators';
import { toObservable } from '@angular/core/rxjs-interop';
import { AuthService } from '../services/auth.service';
import { ToastService } from '../services/toast.service';

/** Lässt jeden angemeldeten Nutzer durch. */
export const authGuard: CanActivateFn = (route, state) => grantAccess(state.url, null);

/**
 * Lässt nur durch, wer eine der genannten Rollen trägt.
 *
 * <p>Die Prüfung im Browser ersetzt keine im Server - dort hängt an jedem
 * Endpunkt ein eigenes `@PreAuthorize`. Sie erspart dem Nutzer nur eine Seite,
 * die sich anschliessend ohnehin nur mit Fehlern füllen würde.</p>
 */
export function roleGuard(allowedRoles: string[]): CanActivateFn {
  return (route, state) => grantAccess(state.url, allowedRoles);
}

/**
 * Die gemeinsame Entscheidung beider Wächter.
 *
 * Der Anmeldestatus steht beim ersten Aufruf noch nicht fest - die Anwendung
 * fragt ihn beim Start erst ab. Ohne das Abwarten schickt der Wächter jeden
 * Direkteinstieg auf eine geschützte Seite zur Startseite zurück.
 *
 * @param allowedRoles `null` bedeutet: angemeldet sein genügt
 */
function grantAccess(url: string, allowedRoles: string[] | null): Observable<boolean | UrlTree> {
  const authService = inject(AuthService);
  const toastService = inject(ToastService);
  const router = inject(Router);

  return toObservable(authService.loading).pipe(
    filter((isLoading) => !isLoading),
    map(() => {
      if (!authService.currentUser()) {
        localStorage.setItem('redirectAfterLogin', url);
        return router.parseUrl('/home');
      }
      if (allowedRoles && !authService.hasAnyRole(allowedRoles)) {
        toastService.error('Für diesen Bereich fehlen dir die Rechte.');
        return router.parseUrl('/dashboard');
      }
      return true;
    }),
  );
}
