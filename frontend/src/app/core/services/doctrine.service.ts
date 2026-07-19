import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface FleetDoctrine {
  id: number;
  doctrineName: string;
  shipType: string;
  shipTypeId: number | null;
  name: string;
  eftString: string;
  createdBy: string;
  createdAt: string;
}

export interface CreateDoctrineDto {
  doctrineName: string;
  shipType: string;
  name: string;
  eftString: string;
}

@Injectable({
  providedIn: 'root'
})
export class DoctrineService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/doctrines`;

  getDoctrines(): Observable<FleetDoctrine[]> {
    return this.http.get<FleetDoctrine[]>(this.apiUrl);
  }

  createDoctrine(dto: CreateDoctrineDto): Observable<FleetDoctrine> {
    return this.http.post<FleetDoctrine>(this.apiUrl, dto);
  }

  deleteDoctrine(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
