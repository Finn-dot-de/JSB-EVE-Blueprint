import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface CreateFleetDto {
  fleetName: string;
  doctrine: string;
  linkExpiryMinutes: number;
}

export interface FleetEvent {
  id: number;
  fcCharacterId: number;
  fcCharacterName: string;
  fleetName: string;
  doctrine: string;
  trackingCode: string;
  startTime: string;
  endTime: string | null;
  linkExpiryTime: string;
}

export interface FleetAttendance {
  id: number;
  characterId: number;
  characterName: string;
  shipTypeId: number;
  shipName: string;
  joinTime: string;
}

@Injectable({
  providedIn: 'root'
})
export class FleetService {
  private http = inject(HttpClient);
  private apiUrl = 'http://localhost:8080/api/fleets';

  createFleet(dto: CreateFleetDto): Observable<FleetEvent> {
    return this.http.post<FleetEvent>(`${this.apiUrl}/create`, dto);
  }

  joinFleet(trackingCode: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/join/${trackingCode}`, {});
  }

  syncFleetViaEsi(eventId: number): Observable<number> {
    return this.http.post<number>(`${this.apiUrl}/${eventId}/sync-esi`, {});
  }

  closeFleet(eventId: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/${eventId}/close`, {});
  }

  getFleetAttendance(eventId: number): Observable<FleetAttendance[]> {
    return this.http.get<FleetAttendance[]>(`${this.apiUrl}/${eventId}/attendance`);
  }

  getRecentFleets(): Observable<FleetEvent[]> {
    return this.http.get<FleetEvent[]>(`${this.apiUrl}/recent`);
  }

}
