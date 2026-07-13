import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { BuybackService } from '../../core/services/buyback.service';
import {AssetResult} from '../../core/models/buback.model';

@Component({
  selector: 'app-buy-bot',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  templateUrl: './buy-bot.component.html',
  styleUrl: './buy-bot.component.scss'
})
export class BuyBotComponent {
  // 1. Service über die moderne Injektion einbinden
  private buybackService = inject(BuybackService);

  // 2. UI-Status und Variablen
  inputText: string = '';
  selectedStation: string = 'Jita IV - Moon 4';
  stations = ['Jita IV - Moon 4', 'Amarr VIII (Oris)', 'Dodixie IX - Moon 20'];

  isLoading: boolean = false;
  results: AssetResult[] = [];
  totalPrice: number = 0;
  botReaction: string = 'Warte auf Eingabe...';

  // 3. Die Hauptfunktion, wenn der Button geklickt wird
  calculate() {
    if (!this.inputText.trim()) return;

    // UI auf "Laden" setzen
    this.isLoading = true;
    this.results = [];
    this.botReaction = 'Analysiere Marktdaten über ESI API... bitte warten_';

    // 4. Den Service aufrufen und auf die Antwort abonnieren (Subscribe)
    this.buybackService.calculateOffer(this.inputText, this.selectedStation)
      .subscribe({
        next: (response) => {
          // Werte aus der Service-Antwort in die UI übernehmen
          this.results = response.results;
          this.totalPrice = response.totalPrice;
          this.botReaction = response.botReaction;
          this.isLoading = false;
        },
        error: (err) => {
          // Falls das Backend (oder der Mock) einen Fehler wirft
          console.error('Fehler bei der Berechnung:', err);
          this.botReaction = 'KRITISCHER SYSTEMFEHLER. Berechnung abgebrochen.';
          this.isLoading = false;
        }
      });
  }

  // Hilfsfunktion für die Sternchen-Anzeige im HTML
  getStars(demand: number): string {
    return '★'.repeat(demand) + '☆'.repeat(3 - demand);
  }
}
