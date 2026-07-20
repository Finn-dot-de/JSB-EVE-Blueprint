import {Component, inject} from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { NavbarComponent } from './core/components/navbar/navbar.component';
import { SidebarComponent } from './core/components/sidebar/sidebar.component';
import { ToastComponent } from './core/components/toast/toast.component';
import { AuthService } from './core/services/auth.service';
import {ConfirmComponent} from './core/components/confirm/confirm.component';

@Component({
  selector: 'app-root',
  standalone: true,

  imports: [RouterOutlet, NavbarComponent, SidebarComponent, ToastComponent, ConfirmComponent],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent {
  public authService = inject(AuthService);
}
