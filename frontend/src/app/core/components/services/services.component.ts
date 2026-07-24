import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { DiscordService } from '../../services/discord.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service'; // <-- NEU: Für das Bestätigungs-Modal

@Component({
  selector: 'app-services',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './services.component.html',
  styleUrls: ['./services.component.scss']
})
export class ServicesComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private discordService = inject(DiscordService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService); // <-- NEU

  isDiscordConnected = signal(false);
  isLoading = signal(true);

  ngOnInit() {
    this.route.queryParams.subscribe(params => {
      if (params['discord'] === 'success') {
        this.router.navigate([], { queryParams: { discord: null }, queryParamsHandling: 'merge' });
      } else if (params['discord'] === 'error') {
        this.toastService.error("Fehler bei der Verbindung mit Discord.");
        this.router.navigate([], { queryParams: { discord: null }, queryParamsHandling: 'merge' });
      }
    });

    this.discordService.getStatus().subscribe({
      next: (res) => {
        this.isDiscordConnected.set(res.connected);
        this.isLoading.set(false);
      },
      error: (err) => {
        console.error('Konnte Discord Status nicht laden', err);
        this.isLoading.set(false);
      }
    });
  }

  connectDiscord() {
    window.location.href = `${environment.apiUrl}/discord/login`;
  }

  // NEU: Methode zum Trennen der Verbindung
  async disconnectDiscord() {
    const confirmed = await this.confirmService.ask(
      'Verbindung trennen?',
      'Möchtest du die Verknüpfung zu deinem Discord-Account wirklich aufheben? Deine Rollen auf dem Server werden dir dadurch sofort entzogen.',
      'Ja, Trennen',
      'Abbrechen'
    );

    if (confirmed) {
      this.isLoading.set(true);
      this.discordService.disconnect().subscribe({
        next: () => {
          this.isDiscordConnected.set(false);
          this.toastService.success('Discord-Verbindung erfolgreich getrennt.');
          this.isLoading.set(false);
        },
        error: () => {
          this.toastService.error('Fehler beim Trennen der Verbindung.');
          this.isLoading.set(false);
        }
      });
    }
  }
}
