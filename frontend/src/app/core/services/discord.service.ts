import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface DiscordMapping {
  authRole: string;
  discordRoleId: string;
  description: string;
}

@Injectable({
  providedIn: 'root'
})
export class DiscordService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/discord`;

  getStatus(): Observable<{ connected: boolean }> {
    return this.http.get<{ connected: boolean }>(`${this.apiUrl}/status`);
  }

  getMappings(): Observable<DiscordMapping[]> {
    return this.http.get<DiscordMapping[]>(`${this.apiUrl}/mappings`);
  }

  saveMapping(mapping: DiscordMapping): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/mappings`, mapping);
  }
}
