import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { CharacterService, AltDto } from '../../services/character.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-charlink',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './charlink.component.html',
  styleUrls: ['./charlink.component.scss']
})
export class CharlinkComponent implements OnInit {
  public authService = inject(AuthService);
  private charService = inject(CharacterService);

  characters = signal<AltDto[]>([]);
  loading = signal(true);

  get isLeadership(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN']);
  }

  ngOnInit() {
    this.charService.getMyAlts().subscribe({
      next: (data) => {
        // Sortiere: Main Charakter immer ganz oben!
        const sorted = data.sort((a, b) => (a.isMain === b.isMain ? 0 : a.isMain ? -1 : 1));
        this.characters.set(sorted);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  addAlt() {
    this.authService.login(); // EVE SSO anstoßen
  }
}
