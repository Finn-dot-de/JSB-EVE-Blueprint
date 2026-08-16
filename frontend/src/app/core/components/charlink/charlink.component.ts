import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { CharacterService, CharacterRefDto } from '../../services/character.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service'; // <-- NEU
import { ToastService } from '../../services/toast.service';     // <-- NEU

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
  private confirmService = inject(ConfirmService); // <-- NEU
  private toastService = inject(ToastService);     // <-- NEU

  characters = signal<CharacterRefDto[]>([]);
  loading = signal(true);

  get isLeadership(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_IT_ADMIN']);
  }

  ngOnInit() {
    this.charService.getMyAlts().subscribe({
      next: (data) => {
        const sorted = data.sort((a, b) => (a.isMain === b.isMain ? 0 : a.isMain ? -1 : 1));
        this.characters.set(sorted);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  addAlt() {
    this.authService.login();
  }

  // NEU: Methode zum Wechseln des Mains
  async makeMain(char: CharacterRefDto) {
    const confirmed = await this.confirmService.ask(
      'Main Charakter ändern?',
      `Möchtest du wirklich ${char.name} zu deinem neuen Main Charakter machen?`,
      'Ja, ändern',
      'Abbrechen'
    );

    if (confirmed) {
      this.loading.set(true); // Spinner anzeigen
      this.charService.setMainCharacter(char.id).subscribe({
        next: () => {
          this.toastService.success(`${char.name} ist jetzt dein Main!`);
          this.ngOnInit(); // Lädt die Liste neu, um den MAIN-Badge zu verschieben
        },
        error: (err) => {
          this.toastService.error(err.error?.message || 'Fehler beim Ändern des Mains.');
          this.loading.set(false);
        }
      });
    }
  }
}
