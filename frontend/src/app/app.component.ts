import {Component, inject} from '@angular/core';
import {Router, RouterOutlet} from '@angular/router';
import { AuthService } from './core/services/auth.service';
import {ConfirmComponent} from './core/components/confirm/confirm.component';
import {ToastComponent} from './core/components/toast/toast.component';

@Component({
  selector: 'app-root',
  standalone: true,

  imports: [RouterOutlet, ConfirmComponent, ToastComponent],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.scss']
})
export class AppComponent {
  authService = inject(AuthService);
  router = inject(Router);

  isRetroMode(): boolean {
    return this.router.url.includes('/buybot');
  }
}
