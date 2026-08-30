import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Ein Charakter eines Accounts.
 *
 * <p>Ersetzt die frueheren, feldgleichen Typen `AltDto` und `AuthedAltDto` -
 * zwei Namen fuer dieselbe Antwort. Spiegelt `CharacterDtos.CharacterRefDto`
 * im Backend.</p>
 */
export interface CharacterRefDto {
  id: number;
  name: string;
  portraitUrl: string;
  isMain: boolean;
}

export interface AuthedMainDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  alts: CharacterRefDto[];
}

export interface UnauthedCharDto {
  id: number;
  name: string;
  portraitUrl: string;
}

export interface CorpStatsDto {
  corpId: number;
  corpName: string;
  totalEsiMembers: number;
  registeredMains: number;
  registeredAlts: number;
  totalRegisteredChars: number;
  authedMembers: AuthedMainDto[];       // <-- NEU
  unauthedMembers: UnauthedCharDto[];   // <-- NEU
}

export interface AdminAccountCharDto {
  id: number;
  name: string;
  portraitUrl: string;
  corporationName: string;
}

export interface AdminAccountDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  corporationName: string;
  alts: AdminAccountCharDto[];
}

/**
 * Ein einzelnes Signal eines Alt-Vorschlags. Spiegelt `CharacterDtos.AltSignalDto`.
 *
 * `available === false` heisst "nicht gemessen" und niemals "gemessen und
 * nichts gefunden" - deshalb ist `score` dort `null` und nicht 0. Die
 * Oberflaeche muss beide Faelle verschieden anzeigen: eine 0 sieht aus wie ein
 * Freispruch, obwohl gar nichts geprueft wurde.
 */
export interface AltSignalDto {
  signal: string;
  label: string;
  available: boolean;
  score: number | null;
  weightPercent: number;
  detail: string;
}

/**
 * Ein Verdacht: dieser nicht registrierte Charakter koennte zu diesem Konto
 * gehoeren. Spiegelt `CharacterDtos.AltSuggestionDto`.
 *
 * `probability` ist eine gewichtete Summe von Heuristiken und keine geeichte
 * Wahrscheinlichkeit. Die Aufschluesselung wandert deshalb mit: eine 94 aus
 * drei Signalen und eine 94 aus einem einzigen sind voellig verschiedene
 * Aussagen.
 */
export interface AltSuggestionDto {
  unauthedCharId: number;
  unauthedCharName: string;
  mainId: number;
  mainName: string;
  probability: number;
  signalsUsed: number;
  signalsTotal: number;
  signals: AltSignalDto[];
  corpId: number;
}

/**
 * Das Ergebnis einer bestaetigten Zuordnung. Spiegelt `CharacterDtos.AltLinkResultDto`.
 *
 * `linked` ist beim heutigen Server immer `false`: bestaetigt wird eine
 * Vormerkung, nicht die Zuordnung. `message` traegt den Klartext samt dem
 * naechsten Schritt - er wird angezeigt und nicht durch einen eigenen Satz
 * ersetzt, sonst verspraeche die Oberflaeche mehr, als geschehen ist.
 */
export interface AltLinkResultDto {
  unauthedCharId: number;
  unauthedCharName: string;
  mainId: number;
  mainName: string;
  probability: number;
  linked: boolean;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class CharacterService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/characters`;

  getMyAlts(): Observable<CharacterRefDto[]> {
    return this.http.get<CharacterRefDto[]>(`${this.apiUrl}/alts`);
  }

  getCorpStats(): Observable<CorpStatsDto[]> {
    return this.http.get<CorpStatsDto[]>(`${this.apiUrl}/corp-stats`);
  }

  setMainCharacter(characterId: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/set-main/${characterId}`, {});
  }

  getAllAccounts(): Observable<AdminAccountDto[]> {
    return this.http.get<AdminAccountDto[]>(`${this.apiUrl}/admin/accounts`);
  }

  /** Nicht registrierte Corp-Charaktere samt vermutetem Konto, ab 80 Punkten. */
  getAltSuggestions(): Observable<AltSuggestionDto[]> {
    return this.http.get<AltSuggestionDto[]>(`${this.apiUrl}/alt-suggestions`);
  }

  /**
   * Bestaetigt einen Vorschlag - als Vormerkung, nicht als Zuordnung.
   *
   * Der Server schreibt nichts nach `characters.main_character_id`; er haelt
   * fest, dass die Fuehrung den Verdacht fuer richtig haelt. Der Rueckgabewert
   * sagt in `linked` und `message`, was tatsaechlich geschehen ist.
   */
  confirmAltSuggestion(unauthedCharId: number, mainId: number): Observable<AltLinkResultDto> {
    return this.http.post<AltLinkResultDto>(`${this.apiUrl}/alt-suggestions/confirm`, {
      unauthedCharId,
      mainId,
    });
  }
}
