import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../services/auth.service';
import { ThemeChoice, ThemeService } from '../../services/theme.service';
import { OWN_CORPORATION_LOGO } from '../../shared/eve-image.util';

/** Ein Eintrag des Zahnrad-Menüs. */
interface ThemeOption {
  readonly choice: ThemeChoice;
  readonly label: string;
  readonly icon: string;
}

@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.scss']
})
export class NavbarComponent {

  protected readonly ownCorporationLogo = OWN_CORPORATION_LOGO;

  public authService = inject(AuthService);
  public themeService = inject(ThemeService);

  isMenuOpen = signal(false);
  isSettingsOpen = signal(false);

  readonly themeOptions: readonly ThemeOption[] = [
    { choice: 'system', label: 'Wie das System', icon: 'fa-solid fa-circle-half-stroke' },
    { choice: 'dim', label: 'Gedämpft', icon: 'fa-solid fa-cloud-moon' },
    { choice: 'dark', label: 'Dunkel', icon: 'fa-solid fa-moon' },
    { choice: 'ma', label: 'MA – Schwarz & Blut', icon: 'fa-solid fa-skull' },
  ];

  toggleMenu() {
    this.isMenuOpen.set(!this.isMenuOpen());
    // Zwei offene Menüs nebeneinander überlagern sich.
    if (this.isMenuOpen()) this.isSettingsOpen.set(false);
  }

  toggleSettings() {
    this.isSettingsOpen.set(!this.isSettingsOpen());
    if (this.isSettingsOpen()) this.isMenuOpen.set(false);
  }

  chooseTheme(choice: ThemeChoice) {
    this.themeService.set(choice);
    this.isSettingsOpen.set(false);
  }

  addAltCharacter() {
    this.authService.login();
  }
}
