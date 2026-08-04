import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AssetRowDto, AssetStackDto, PageDto,
  MemberAssetDetailDto, TypeSuggestionDto, IdNameDto
} from './asset.service';

/**
 * Filter-Optionen der eigenen Sicht.
 * Enthaelt bewusst keine Corporation- oder Main-Listen wie das Director-Pendant,
 * dafuer die eigenen Charaktere.
 */
export interface MyFilterOptionsDto {
  categories: IdNameDto[];
  groups: IdNameDto[];
  locations: IdNameDto[];
  regions: string[];
  locationFlags: string[];
  characters: IdNameDto[];
}

/**
 * Suchparameter der Mitglieder-Sicht.
 * mainId und corporationId fehlen absichtlich: den Scope setzt das Backend
 * selbst aus dem Token - er laesst sich von hier aus nicht beeinflussen.
 */
export interface MyAssetSearchParams {
  q?: string | null;
  typeId?: number | null;
  groupId?: number | null;
  categoryId?: number | null;
  characterId?: number | null;
  locationId?: number | null;
  regionName?: string | null;
  locationFlag?: string | null;
  minQuantity?: number | null;
  minValue?: number | null;
  shipsOnly?: boolean | null;
  sort?: string;
  direction?: string;
  page?: number;
  size?: number;
  grouped?: boolean;
}

@Injectable({ providedIn: 'root' })
export class MyAssetService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/my/assets`;

  private toParams(p: MyAssetSearchParams): HttpParams {
    let params = new HttpParams();
    Object.entries(p).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    });
    return params;
  }

  search(p: MyAssetSearchParams): Observable<PageDto<AssetRowDto>> {
    return this.http.get<PageDto<AssetRowDto>>(`${this.apiUrl}/search`,
      { params: this.toParams({ ...p, grouped: false }) });
  }

  searchGrouped(p: MyAssetSearchParams): Observable<PageDto<AssetStackDto>> {
    return this.http.get<PageDto<AssetStackDto>>(`${this.apiUrl}/search`,
      { params: this.toParams({ ...p, grouped: true }) });
  }

  suggestTypes(q: string): Observable<TypeSuggestionDto[]> {
    return this.http.get<TypeSuggestionDto[]>(`${this.apiUrl}/types/suggest`,
      { params: new HttpParams().set('q', q) });
  }

  /** Kennzahlen des eigenen Accounts (Wert, Stacks, nach Kategorie/Standort). */
  summary(): Observable<MemberAssetDetailDto> {
    return this.http.get<MemberAssetDetailDto>(`${this.apiUrl}/summary`);
  }

  filters(categoryId?: number | null): Observable<MyFilterOptionsDto> {
    let params = new HttpParams();
    if (categoryId) params = params.set('categoryId', String(categoryId));
    return this.http.get<MyFilterOptionsDto>(`${this.apiUrl}/filters`, { params });
  }

  exportCsv(p: MyAssetSearchParams): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/export`, {
      params: this.toParams(p),
      responseType: 'blob'
    });
  }
}
