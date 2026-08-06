import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DiscordService, DiscordMapping } from '../../services/discord.service';
import { ToastService } from '../../services/toast.service'; // <-- NEU: Importieren

@Component({
  selector: 'app-discord-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './discord-admin.component.html'
})
export class DiscordAdminComponent implements OnInit {
  private discordService = inject(DiscordService);
  private toastService = inject(ToastService);

  mappings = signal<DiscordMapping[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.discordService.getMappings().subscribe({
      next: (data) => {
        this.mappings.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.toastService.error('Zugriff verweigert oder Fehler beim Laden der Rollen!');
        this.loading.set(false);
      }
    });
  }

  saveMapping(mapping: DiscordMapping) {
    this.discordService.saveMapping(mapping).subscribe({
      next: () => this.toastService.success(`Mapping für ${mapping.authRole} gespeichert!`),
      error: () => this.toastService.error(`Fehler beim Speichern von ${mapping.authRole}.`)
    });
  }
}
