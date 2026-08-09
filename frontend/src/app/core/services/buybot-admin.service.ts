import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AdminConfig {
  id?: number;
  priceBasis: string;
  globalModifier: number;
  volumeThreshold: number;
  valueThreshold: number;
  itemValueThreshold: number;
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
}

export interface AdminType {
  typeId?: number;
  typeName?: string;
  modifier: number;
  isBlacklisted: boolean;
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
  addCategory(categoryName: string, modifier: number): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(`${this.baseUrl}/categories`, { categoryName, modifier });
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
  addType(typeName: string, modifier: number, isBlacklisted: boolean): Observable<AdminType> {
    return this.http.post<AdminType>(`${this.baseUrl}/types`, { typeName, modifier, isBlacklisted });
  }
  deleteType(typeId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/types/${typeId}`);
  }
}
