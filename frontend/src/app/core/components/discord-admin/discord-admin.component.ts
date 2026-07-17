import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DiscordService, DiscordMapping } from '../../services/discord.service';

@Component({
  selector: 'app-discord-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './discord-admin.component.html'
})
export class DiscordAdminComponent implements OnInit {
  private discordService = inject(DiscordService);

  mappings = signal<DiscordMapping[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.discordService.getMappings().subscribe({
      next: (data) => {
        this.mappings.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Fehler beim Laden der Mappings:', err);
        alert('Zugriff verweigert oder Fehler beim Laden der Rollen!');
        this.loading.set(false);
      }
    });
  }

  saveMapping(mapping: DiscordMapping) {
    this.discordService.saveMapping(mapping).subscribe({
      next: () => alert(`Mapping für ${mapping.authRole} gespeichert!`),
      error: () => alert(`Fehler beim Speichern von ${mapping.authRole}.`)
    });
  }
}
