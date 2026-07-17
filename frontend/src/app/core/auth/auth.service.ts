import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of, tap } from 'rxjs';
import { Router } from '@angular/router';

export interface AuthUser {
  characterId: number;
  characterName: string;
  portraitUrl: string;
  roles: string[]; // NEU: Die Rollen vom Backend empfangen
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
    this.http.get<AuthUser>('http://localhost:8080/api/auth/me')
      .pipe(
        tap(user => this.currentUser.set(user)),
        catchError(() => {
          this.currentUser.set(null);
          return of(null);
        }),
        tap(() => this.loading.set(false))
      )
      .subscribe();
  }

  // NEU: Helfer-Methode für die Rechte-Prüfung im UI
  hasAnyRole(allowedRoles: string[]): boolean {
    const user = this.currentUser();
    if (!user || !user.roles) return false;
    return allowedRoles.some(role => user.roles.includes(role));
  }

  login() {
    window.location.href = 'http://localhost:8080/api/auth/login';
  }

  logout() {
    this.http.post('http://localhost:8080/api/auth/logout', {}).subscribe({
      next: () => {
        this.currentUser.set(null);
        this.router.navigate(['/home']);
      },
      error: (err) => console.error('Logout fehlgeschlagen', err)
    });
  }
}
