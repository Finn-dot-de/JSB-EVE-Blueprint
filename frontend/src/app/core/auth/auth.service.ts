import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, of, tap } from 'rxjs';

export interface AuthUser {
  characterId: number;
  characterName: string;
  portraitUrl: string;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private http = inject(HttpClient);

  // Hält nur die nackten Daten vom Backend
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

  login() {
    window.location.href = 'http://localhost:8080/api/auth/login';
  }

  logout() {
    this.http.post('http://localhost:8080/api/auth/logout', {}).subscribe({
      next: () => this.currentUser.set(null),
      error: (err) => console.error('Logout fehlgeschlagen', err)
    });
  }
}
