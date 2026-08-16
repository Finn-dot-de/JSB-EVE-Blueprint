import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type NotifyTarget = 'NONE' | 'DISCORD' | 'EVEMAIL' | 'BOTH';

export interface AdminConfig {
  id?: number;
  priceBasis: string;
  globalModifier: number;
  volumeThreshold: number;
  valueThreshold: number;
  itemValueThreshold: number;
  /** Reprocessing-Ausbeute in Prozent (NPC-Station = 50). */
  reprocessingRate: number;

  // Wartungsmodus
  botEnabled: boolean;
  maintenanceTitle?: string;
  maintenanceMessage?: string;

  // Vertragserstellung (Anleitung im Frontend)
  contractRecipient?: string;
  contractExpireDays?: number;
  contractDaysToComplete?: number;
  contractNote?: string;

  // Vertragsprüfung - 0 bedeutet "nicht gesetzt" (das Backend macht daraus NULL).
  contractCheckEnabled: boolean;
  contractCheckCharacterId: number;
  priceTolerancePercent: number;
  checkIntervalMinutes: number;
  notifyTarget: NotifyTarget;
  discordWebhookUrl?: string;
  notifyMailRecipientId: number;
  notifyOnOk: boolean;

  botTexts?: {
    idle: string;
    thinking: string;
    success: string;
    warnMissing: string;
    warnRejected: string;
    error: string;
    highVolume: string;
    highValue: string;
    expensiveItem: string;
  };
}

export interface AdminLocation {
  id?: number;
  name: string;
  transportFee: number;
  securityFee: number;
  stationId?: number;
}

export interface AdminCategory {
  categoryId?: number;
  categoryName?: string;
  modifier: number;
  /** true = Reprocessing-Wert der Ausbeute statt Marktpreis des Items. */
  useReprocessedValue: boolean;
}

export interface AdminType {
  typeId?: number;
  typeName?: string;
  modifier: number;
  isBlacklisted: boolean;
  /** Überschreibt die Kategorie-Einstellung. */
  useReprocessedValue: boolean;
  /**
   * false = das Item hat gar keine Reprocessing-Ausbeute, das Häkchen bleibt also
   * wirkungslos und es gilt weiter der Marktpreis.
   */
  reprocessable?: boolean;
}

export interface LinkedCharacter {
  id: number;
  name: string;
}

export interface ContractCheckResult {
  contractId: number;
  issuerId?: number;
  issuerName?: string;
  title?: string;
  contractType?: string;
  issuedAt?: string;
  expiresAt?: string;
  checkedAt?: string;
  startLocationId?: number;
  locationName?: string;
  contractPrice?: number;
  expectedPrice?: number;
  deviationPercent?: number;
  totalVolume?: number;
  verdict: 'OK' | 'WARN' | 'REJECT';
  findingCodes?: string;
  findings?: string;
  itemSummary?: string;
  notified?: boolean;
  /** Grund, warum die Meldung nicht rausging. */
  notifyError?: string;
  notifyAttempts?: number;
}

export type AuditCategory = 'REQUEST' | 'QUOTE' | 'ADMIN' | 'SECURITY' | 'CONTRACT_CHECK' | 'NOTIFICATION' | 'ERROR';
export type AuditSeverity = 'INFO' | 'WARN' | 'ERROR';

/** Ein Eintrag aus dem Protokoll. */
export interface AuditEntry {
  id: number;
  occurredAt: string;
  category: AuditCategory;
  severity: AuditSeverity;
  message: string;
  /** Fehler-ID, die der Spieler bei einer Meldung nennt. */
  requestId?: string;
  actorCharacterId?: number;
  actorName?: string;
  clientIp?: string;
  userAgent?: string;
  httpMethod?: string;
  path?: string;
  statusCode?: number;
  durationMs?: number;
  details?: string;
}

export interface AuditPage {
  entries: AuditEntry[];
  total: number;
}

export interface ContractCheckStatus {
  enabled: boolean;
  intervalMinutes: number;
  notifyTarget: NotifyTarget;
  lastRunAt?: string;
  nextRunAt?: string;
  trigger?: string;
  lastRunSuccess: boolean;
  lastRunMessage?: string;
  scanned: number;
  checked: number;
  notified: number;
  /** Verträge, deren Meldung noch aussteht. */
  pendingNotifications: number;
}

export interface ContractCheckRun {
  success: boolean;
  message: string;
  scanned: number;
  checked: number;
  notified: number;
}

@Injectable({
  providedIn: 'root'
})
export class BuybotAdminService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/admin/buybot`;

  // --- CONFIG ---
  getConfig(): Observable<AdminConfig> {
    return this.http.get<AdminConfig>(`${this.baseUrl}/config`);
  }
  updateConfig(config: AdminConfig): Observable<AdminConfig> {
    return this.http.put<AdminConfig>(`${this.baseUrl}/config`, config);
  }

  // --- LOCATIONS ---
  getLocations(): Observable<AdminLocation[]> {
    return this.http.get<AdminLocation[]>(`${this.baseUrl}/locations`);
  }
  addLocation(location: AdminLocation): Observable<AdminLocation> {
    return this.http.post<AdminLocation>(`${this.baseUrl}/locations`, location);
  }
  deleteLocation(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/locations/${id}`);
  }

  // --- CATEGORIES ---
  getCategories(): Observable<AdminCategory[]> {
    return this.http.get<AdminCategory[]>(`${this.baseUrl}/categories`);
  }
  addCategory(categoryName: string, modifier: number, useReprocessedValue: boolean): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(`${this.baseUrl}/categories`, { categoryName, modifier, useReprocessedValue });
  }
  deleteCategory(categoryId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/categories/${categoryId}`);
  }

  searchStationId(name: string): Observable<number> {
    return this.http.get<number>(`${this.baseUrl}/search-station?name=${encodeURIComponent(name)}`);
  }

  // --- TYPES (ITEMS) ---
  getTypes(): Observable<AdminType[]> {
    return this.http.get<AdminType[]>(`${this.baseUrl}/types`);
  }
  addType(typeName: string, modifier: number, isBlacklisted: boolean, useReprocessedValue: boolean): Observable<AdminType> {
    return this.http.post<AdminType>(`${this.baseUrl}/types`, { typeName, modifier, isBlacklisted, useReprocessedValue });
  }
  deleteType(typeId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/types/${typeId}`);
  }

  // --- VERTRAGSPRÜFUNG ---
  getLinkedCharacters(): Observable<LinkedCharacter[]> {
    return this.http.get<LinkedCharacter[]>(`${this.baseUrl}/characters`);
  }
  runContractCheck(): Observable<ContractCheckRun> {
    return this.http.post<ContractCheckRun>(`${this.baseUrl}/contract-check/run`, {});
  }
  testNotification(): Observable<ContractCheckRun> {
    return this.http.post<ContractCheckRun>(`${this.baseUrl}/contract-check/test`, {});
  }
  getContractCheckStatus(): Observable<ContractCheckStatus> {
    return this.http.get<ContractCheckStatus>(`${this.baseUrl}/contract-check/status`);
  }

  // --- PROTOKOLL ---
  getAuditEntries(category: AuditCategory | '', minSeverity: AuditSeverity | '', limit = 50): Observable<AuditPage> {
    let params = `?limit=${limit}`;
    if (category) {
      params += `&category=${category}`;
    }
    if (minSeverity) {
      params += `&minSeverity=${minSeverity}`;
    }
    return this.http.get<AuditPage>(`${environment.apiUrl}/admin/audit${params}`);
  }
  getContractCheckResults(limit = 25): Observable<ContractCheckResult[]> {
    return this.http.get<ContractCheckResult[]>(`${this.baseUrl}/contract-check/results?limit=${limit}`);
  }
  forgetContractCheck(contractId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/contract-check/results/${contractId}`);
  }
}
