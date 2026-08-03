import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AltDto {
  id: number;
  name: string;
  portraitUrl: string;
  isMain: boolean;
}

export interface AuthedAltDto {
  id: number;
  name: string;
  portraitUrl: string;
  isMain: boolean;
}

export interface AuthedMainDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  alts: AuthedAltDto[];
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

  getMyAlts(): Observable<AltDto[]> {
    return this.http.get<AltDto[]>(`${this.apiUrl}/alts`);
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
