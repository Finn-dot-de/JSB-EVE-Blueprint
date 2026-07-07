import { Injectable, signal } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  isLoggedIn = signal<boolean>(false);

  setLoggedInState(status: boolean): void {
    this.isLoggedIn.set(status);
  }

  logout(): void {
    this.isLoggedIn.set(false);
  }
}
