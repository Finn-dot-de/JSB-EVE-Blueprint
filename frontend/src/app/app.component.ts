import { Component, signal, inject, OnInit } from '@angular/core';
import { RouterOutlet, ActivatedRoute, Router, Params } from '@angular/router';
import { AuthService } from './core/auth/auth.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private authService = inject(AuthService);

  ngOnInit(): void {
    this.route.queryParams.subscribe((params: Params) => {
      const token = params['token'];

      if (token) {
        // 1. Token speichern
        this.authService.setToken(token);

        this.router.navigate([], {
          queryParams: { token: null },
          queryParamsHandling: 'merge',
          replaceUrl: true
        });
      }
    });
  }
}
