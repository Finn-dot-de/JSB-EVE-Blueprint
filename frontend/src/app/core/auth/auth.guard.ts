import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { toObservable } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs/operators';

export const authGuard: CanActivateFn = (route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  return toObservable(authService.loading).pipe(
    filter(isLoading => !isLoading),
    map(() => {
      if (authService.currentUser()) {
        return true;
      }

      localStorage.setItem('redirectAfterLogin', state.url);

      return router.parseUrl('/home');
    })
  );
};
