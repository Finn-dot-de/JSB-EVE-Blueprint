import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Ein Punkt innerhalb eines Registers. */
export interface MenuItemDto {
  label: string;
  url: string;
  icon: string;
  external: boolean;
}

/**
 * Ein Eintrag der obersten Ebene.
 *
 * Entweder ein Register - dann trägt `children` die Punkte und `url` ist leer -
 * oder ein einzelner Punkt ohne Register.
 */
export interface MenuEntryDto {
  label: string;
  icon: string;
  url: string | null;
  external: boolean;
  children: MenuItemDto[];
}

export interface NavCategoryDto {
  id: number;
  name: string;
  icon: string;
  sortOrder: number;
  linkCount: number;
}

export interface NavLinkDto {
  id: number;
  label: string;
  url: string;
  icon: string | null;
  categoryId: number | null;
  requiredRole: string | null;
  active: boolean;
  sortOrder: number;
}

/** Der vollständige Stand für die Verwaltung - auch die abgeschalteten Einträge. */
export interface NavAdminViewDto {
  categories: NavCategoryDto[];
  links: NavLinkDto[];
}

export interface SaveCategoryDto {
  id: number | null;
  name: string;
  icon: string | null;
}

export interface SaveLinkDto {
  id: number | null;
  label: string;
  url: string;
  icon: string | null;
  categoryId: number | null;
  requiredRole: string | null;
  active: boolean;
}

export type MoveKind = 'LINK' | 'CATEGORY';
export type MoveDirection = 'UP' | 'DOWN';

@Injectable({ providedIn: 'root' })
export class NavigationService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/navigation`;
  private adminUrl = `${environment.apiUrl}/admin/navigation`;

  /** Das eigene Menü, vom Server fertig sortiert. */
  menu(): Observable<MenuEntryDto[]> {
    return this.http.get<MenuEntryDto[]>(this.apiUrl);
  }

  overview(): Observable<NavAdminViewDto> {
    return this.http.get<NavAdminViewDto>(this.adminUrl);
  }

  saveCategory(category: SaveCategoryDto): Observable<NavCategoryDto> {
    return this.http.post<NavCategoryDto>(`${this.adminUrl}/categories`, category);
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.adminUrl}/categories/${id}`);
  }

  saveLink(link: SaveLinkDto): Observable<NavLinkDto> {
    return this.http.post<NavLinkDto>(`${this.adminUrl}/links`, link);
  }

  deleteLink(id: number): Observable<void> {
    return this.http.delete<void>(`${this.adminUrl}/links/${id}`);
  }

  /** Verschiebt einen Eintrag um eine Position innerhalb seiner Ebene. */
  move(kind: MoveKind, id: number, direction: MoveDirection): Observable<void> {
    return this.http.post<void>(`${this.adminUrl}/move`, { kind, id, direction });
  }
}
