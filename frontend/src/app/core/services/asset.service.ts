import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/* =========================================================
   Interfaces (Spiegel der Java-Records in AssetDtos)
   ========================================================= */

export interface AssetRowDto {
  itemId: number;
  characterId: number;
  characterName: string;
  mainId: number;
  mainName: string;
  corporationId: number;
  corporationName: string;
  typeId: number;
  typeName: string;
  groupId: number;
  groupName: string;
  categoryId: number;
  categoryName: string;
  quantity: number;
  locationName: string;
  systemName: string | null;
  regionName: string | null;
  locationFlag: string | null;
  singleton: boolean;
  unitPrice: number;
  totalValue: number;
}

export interface AssetStackDto {
  typeId: number;
  typeName: string;
  groupName: string;
  categoryName: string;
  mainId: number;
  mainName: string;
  corporationName: string;
  quantity: number;
  locationCount: number;
  unitPrice: number;
  totalValue: number;
}

export interface PageDto<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  pageValue: number;
  grandTotalValue: number;
}

export interface HolderLocationDto {
  locationId: number;
  locationName: string;
  systemName: string | null;
  regionName: string | null;
  locationFlag: string | null;
  quantity: number;
}

export interface HolderCharacterDto {
  characterId: number;
  characterName: string;
  portraitUrl: string;
  quantity: number;
  locations: HolderLocationDto[];
}

export interface HolderDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  corporationName: string;
  totalQuantity: number;
  totalValue: number;
  characters: HolderCharacterDto[];
}

export interface TypeHoldersDto {
  typeId: number;
  typeName: string;
  groupName: string | null;
  iconUrl: string;
  unitPrice: number;
  totalQuantity: number;
  totalValue: number;
  holderCount: number;
  holders: HolderDto[];
}

export interface NamedValueDto { name: string; quantity: number; value: number; }

export interface TopTypeDto {
  typeId: number; typeName: string; groupName: string;
  iconUrl: string; quantity: number; value: number; holders: number;
}

export interface TopHolderDto {
  mainId: number; mainName: string; portraitUrl: string;
  corporationName: string; stacks: number; value: number;
}

export interface SummaryDto {
  totalStacks: number;
  totalItems: number;
  distinctTypes: number;
  trackedCharacters: number;
  totalValue: number;
  valueByCorporation: NamedValueDto[];
  valueByCategory: NamedValueDto[];
  topTypes: TopTypeDto[];
  topHolders: TopHolderDto[];
  topRegions: NamedValueDto[];
}

export interface LocationBucketDto {
  locationId: number;
  locationName: string;
  systemName: string | null;
  regionName: string | null;
  stacks: number;
  value: number;
}

export interface MemberAssetDetailDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  corporationName: string | null;
  totalValue: number;
  totalStacks: number;
  byCategory: NamedValueDto[];
  byLocation: LocationBucketDto[];
  topItems: AssetStackDto[];
}

export interface DoctrineShipDto { typeId: number; typeName: string; iconUrl: string; owned: number; }

export interface DoctrineReadinessRowDto {
  mainId: number; mainName: string; portraitUrl: string; corporationName: string;
  shipsOwned: number; shipsTotal: number; coverage: number; ships: DoctrineShipDto[];
}

export interface DoctrineReadinessDto {
  doctrineName: string | null;
  requiredShips: DoctrineShipDto[];
  membersReady: number;
  membersTotal: number;
  rows: DoctrineReadinessRowDto[];
}

export interface IdNameDto { id: number; name: string; }

export interface FilterOptionsDto {
  categories: IdNameDto[];
  groups: IdNameDto[];
  locations: IdNameDto[];
  regions: string[];
  locationFlags: string[];
  corporations: IdNameDto[];
  mains: IdNameDto[];
}

export interface TypeSuggestionDto {
  typeId: number; typeName: string; groupName: string;
  iconUrl: string; totalQuantity: number;
}

export interface AssetSearchParams {
  q?: string | null;
  typeId?: number | null;
  groupId?: number | null;
  categoryId?: number | null;
  characterId?: number | null;
  mainId?: number | null;
  corporationId?: number | null;
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

/* ========================================================= */

@Injectable({ providedIn: 'root' })
export class AssetService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/assets`;

  private toParams(p: AssetSearchParams): HttpParams {
    let params = new HttpParams();
    Object.entries(p).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    });
    return params;
  }

  search(p: AssetSearchParams): Observable<PageDto<AssetRowDto>> {
    return this.http.get<PageDto<AssetRowDto>>(`${this.apiUrl}/search`,
      { params: this.toParams({ ...p, grouped: false }) });
  }

  searchGrouped(p: AssetSearchParams): Observable<PageDto<AssetStackDto>> {
    return this.http.get<PageDto<AssetStackDto>>(`${this.apiUrl}/search`,
      { params: this.toParams({ ...p, grouped: true }) });
  }

  suggestTypes(q: string): Observable<TypeSuggestionDto[]> {
    return this.http.get<TypeSuggestionDto[]>(`${this.apiUrl}/types/suggest`,
      { params: new HttpParams().set('q', q) });
  }

  holders(typeId: number): Observable<TypeHoldersDto> {
    return this.http.get<TypeHoldersDto>(`${this.apiUrl}/holders/${typeId}`);
  }

  summary(): Observable<SummaryDto> {
    return this.http.get<SummaryDto>(`${this.apiUrl}/summary`);
  }

  filters(categoryId?: number | null): Observable<FilterOptionsDto> {
    let params = new HttpParams();
    if (categoryId) params = params.set('categoryId', String(categoryId));
    return this.http.get<FilterOptionsDto>(`${this.apiUrl}/filters`, { params });
  }

  memberDetail(mainId: number): Observable<MemberAssetDetailDto> {
    return this.http.get<MemberAssetDetailDto>(`${this.apiUrl}/member/${mainId}`);
  }

  doctrines(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/doctrines`);
  }

  doctrineReadiness(doctrineName: string): Observable<DoctrineReadinessDto> {
    return this.http.get<DoctrineReadinessDto>(`${this.apiUrl}/doctrines/readiness`,
      { params: new HttpParams().set('doctrineName', doctrineName) });
  }

  /** CSV-Download - laeuft ueber Blob, damit das Auth-Cookie mitgeschickt wird. */
  exportCsv(p: AssetSearchParams): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/export`, {
      params: this.toParams(p),
      responseType: 'blob'
    });
  }

  resolveLocations(): Observable<any> {
    return this.http.post(`${this.apiUrl}/locations/resolve`, {});
  }
}
