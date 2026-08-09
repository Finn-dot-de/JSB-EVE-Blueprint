import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment'; // <-- WICHTIG: Environment importieren

export interface ParsedItemDto {
  rawName: string;
  quantity: number;
  typeId: number;
  volumeEach: number;
  categoryId: number;
  resolved: boolean;
  status: string;
  totalPrice: number;
  appliedModifier: number;
}

export interface CalculateRequest {
  rawInput: string;
  locationId: number;
}

export interface BuybackLocation {
  id: number;
  name: string;
  transportFee: number;
  securityFee: number;
  stationId: number;
}

@Injectable({
  providedIn: 'root'
})
export class BuybotService {
  private http = inject(HttpClient);

  calculateBuyback(request: CalculateRequest): Observable<ParsedItemDto[]> {
    // Die environment.apiUrl voranstellen
    return this.http.post<ParsedItemDto[]>(`${environment.apiUrl}/buybot/calculate`, request);
  }

  getLocations(): Observable<BuybackLocation[]> {
    // Die environment.apiUrl voranstellen
    return this.http.get<BuybackLocation[]>(`${environment.apiUrl}/buybot/locations`);
  }
}
