import { Component, inject } from '@angular/core';
import { AuthService } from '../../services/auth.service';
import { OWN_CORPORATION_LOGO } from '../../shared/eve-image.util';

@Component({
  selector: 'app-home',
  standalone: true,
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss']
})
export class HomeComponent {

  protected readonly ownCorporationLogo = OWN_CORPORATION_LOGO;

  public authService = inject(AuthService);
}
