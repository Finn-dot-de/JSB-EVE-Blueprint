import { Injectable } from '@angular/core';
import { Observable, delay, of } from 'rxjs';
import {AssetResult, BuybackResponse} from '../models/model.model';

@Injectable({
  providedIn: 'root'
})
export class ServiceService {

  // Diese Methode wird später durch this.http.post('/api/buyback/calculate', { text, station }) ersetzt
  calculateOffer(eveText: string, station: string): Observable<BuybackResponse> {

    // --- MOCK LOGIK (wird später im Java Backend gemacht) ---
    const results: AssetResult[] = [
      { quantity: 50000, name: 'Tritanium', volume: 500, demand: 3, price: 215000, isBlacklisted: false },
      { quantity: 10, name: 'PLEX', volume: 0.1, demand: 3, price: 48000000, isBlacklisted: false },
      { quantity: 1, name: 'Corpse (Unknown)', volume: 2, demand: 1, price: 0, isBlacklisted: true },
      { quantity: 5, name: 'Vexor', volume: 50000, demand: 2, price: 45000000, isBlacklisted: false }
    ];

    const totalPrice = results.reduce((sum, item) => sum + item.price, 0);

    let botReaction = 'Akzeptabel. Bitte erstelle den Vertrag.';
    if (totalPrice > 50000000) {
      botReaction = 'Ein exzellentes Geschäft. ISK stehen zur Überweisung bereit.';
    }

    const response: BuybackResponse = { results, totalPrice, botReaction };

    // of() erstellt ein Observable, delay() simuliert die Netzwerk-Ladezeit
    return of(response).pipe(delay(1500));
  }
}
