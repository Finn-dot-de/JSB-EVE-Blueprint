import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment'; // <-- WICHTIG: Environment importieren

export type ItemStatusCode = 'OK' | 'BLOCKED' | 'NOT_LISTED' | 'UNKNOWN';

export interface ParsedItemDto {
  rawName: string;
  quantity: number;
  typeId: number;
  volumeEach: number;
  categoryId: number;
  resolved: boolean;
  /** Deutscher Klartext aus dem Backend (Altbestand). */
  status: string;
  /** Maschinenlesbar - Grundlage für die Übersetzung im Frontend. */
  statusCode: ItemStatusCode;
  unitPrice: number;
  totalPrice: number;
  appliedModifier: number;
  /** MARKET = Jita-Preis des Items, REPROCESSED = Wert der Reprocessing-Ausbeute. */
  priceSource: 'MARKET' | 'REPROCESSED';
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

export interface BotTexts {
  idle: string;
  thinking: string;
  success: string;
  warnMissing: string;
  warnRejected: string;
  error: string;
  highVolume: string;
  highValue: string;
  expensiveItem: string;
}

export interface InjectorPrice {
  typeId: number;
  name: string;
  /** Jita-Sell eines Large Skill Injectors, 0 wenn der Markt nicht erreichbar war. */
  price: number;
}

/** Wie viele Skill Injectors der angemeldete Charakter besitzt. */
export interface MyInjectors {
  /** Anzahl im Besitz, null wenn sie nicht ermittelt werden konnte. */
  quantity: number | null;
  /** Grund, falls keine Zahl geliefert wurde. */
  hint?: string;
}

/** Öffentlicher Teil der Buybot-Konfiguration (ohne Margen/Preisbasis). */
export interface PublicConfig {
  botEnabled: boolean;
  maintenanceTitle?: string;
  maintenanceMessage?: string;
  volumeThreshold?: number;
  valueThreshold?: number;
  itemValueThreshold?: number;
  contractRecipient?: string;
  contractExpireDays?: number;
  contractDaysToComplete?: number;
  contractNote?: string;
  botTexts?: BotTexts;
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

  getPublicConfig(): Observable<PublicConfig> {
    return this.http.get<PublicConfig>(`${environment.apiUrl}/buybot/config`);
  }

  getInjectorPrice(): Observable<InjectorPrice> {
    return this.http.get<InjectorPrice>(`${environment.apiUrl}/buybot/injector-price`);
  }

  /**
   * Eigener Injector-Bestand. Antwortet mit HTTP 401, wenn niemand angemeldet ist -
   * das wertet der Aufrufer als "nicht angemeldet".
   */
  getMyInjectors(): Observable<MyInjectors> {
    return this.http.get<MyInjectors>(`${environment.apiUrl}/buybot/my-injectors`);
  }
}
