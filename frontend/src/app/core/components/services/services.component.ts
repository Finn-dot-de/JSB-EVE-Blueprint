import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { DiscordService } from '../../services/discord.service';

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

  isDiscordConnected = signal(false);
  isLoading = signal(true);

  ngOnInit() {
    // 1. URL Parameter checken (für Erfolgs-/Fehlermeldungen nach dem Redirect)
    this.route.queryParams.subscribe(params => {
      if (params['discord'] === 'success') {
        // URL sauber machen, damit das nicht beim nächsten Neuladen nochmal aufploppt
        this.router.navigate([], { queryParams: { discord: null }, queryParamsHandling: 'merge' });
      } else if (params['discord'] === 'error') {
        alert('Fehler bei der Verbindung mit Discord.');
        this.router.navigate([], { queryParams: { discord: null }, queryParamsHandling: 'merge' });
      }
    });

    // 2. Tatsächlichen Status aus der Datenbank laden
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
}
