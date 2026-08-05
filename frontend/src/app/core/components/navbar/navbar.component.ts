import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '../../services/auth.service';
import { OWN_CORPORATION_LOGO } from '../../shared/eve-image.util';

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

  isMenuOpen = signal(false);

  toggleMenu() {
    this.isMenuOpen.set(!this.isMenuOpen());
  }

  addAltCharacter() {
    this.authService.login();
  }
}
