import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of, tap } from 'rxjs';
import { Router } from '@angular/router';
import {environment} from '../../../environments/environment';

/**
 * Rollen, die den Admin-Bereich freischalten.
 *
 * Muss mit der Regel im Backend uebereinstimmen (@PreAuthorize an
 * BuybotAdminController und AuditController) - sonst sieht jemand den Knopf,
 * bekommt aber beim Klick eine Fehlermeldung, oder umgekehrt.
 */
export const ADMIN_ROLES = ['ROLE_IT_ADMIN', 'ROLE_CEO', 'ROLE_DIRECTOR'];

export interface AuthUser {
  characterId: number;
  characterName: string;
  portraitUrl: string;
  roles: string[];
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  currentUser = signal<AuthUser | null>(null);
  loading = signal<boolean>(true);

  portraitUrl = computed(() => {
    const user = this.currentUser();
    return user ? `https://images.evetech.net/characters/${user.characterId}/portrait?size=128` : null;
  });

  constructor() {
    this.checkAuthStatus();
  }

  checkAuthStatus() {
    this.http.get<AuthUser>(`${environment.apiUrl}/auth/me`)
      .pipe(
        tap(user => {
          this.currentUser.set(user);

          if (user) {
            const redirectUrl = localStorage.getItem('redirectAfterLogin');
            if (redirectUrl) {

              localStorage.removeItem('redirectAfterLogin');

              this.router.navigateByUrl(redirectUrl);
            }
          }
        }),
        catchError(() => {
          this.currentUser.set(null);
          return of(null);
        }),
        tap(() => this.loading.set(false))
      )
      .subscribe();
  }
  
  hasAnyRole(allowedRoles: string[]): boolean {
    const user = this.currentUser();
    if (!user || !user.roles) return false;
    return allowedRoles.some(role => user.roles.includes(role));
  }

  login() {
    window.location.href = `${environment.apiUrl}/auth/login`;
  }

  logout() {
    this.http.post(`${environment.apiUrl}/auth/logout`, {}).subscribe({
      next: () => {
        this.currentUser.set(null);
        this.router.navigate(['/home']);
      },
      error: (err) => console.error('Logout fehlgeschlagen', err)
    });
  }
}
