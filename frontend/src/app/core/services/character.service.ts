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

export interface CorpStatsDto {
  totalEsiMembers: number;
  registeredMains: number;
  registeredAlts: number;
}

@Injectable({ providedIn: 'root' })
export class CharacterService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/characters`;

  getMyAlts(): Observable<AltDto[]> {
    return this.http.get<AltDto[]>(`${this.apiUrl}/alts`);
  }

  getCorpStats(): Observable<CorpStatsDto> {
    return this.http.get<CorpStatsDto>(`${this.apiUrl}/corp-stats`);
  }
}
