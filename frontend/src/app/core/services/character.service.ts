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
}
