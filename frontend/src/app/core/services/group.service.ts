import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Ein Corp-Titel samt der Rolle, die er in dieser Anwendung vergibt. */
export interface CorpTitleDto {
  titleId: number;
  name: string;
  mappedRole: string | null;
}

/**
 * Woher eine Rolle stammt. Nur `CUSTOM` lässt sich bearbeiten und löschen -
 * `BUILT_IN` steckt im Programm, `TITLE` hängt an einer Titel-Zuordnung.
 */
export type AuthRoleSource = 'BUILT_IN' | 'CUSTOM' | 'TITLE';

export interface AuthRoleDto {
  name: string;
  description: string;
  source: AuthRoleSource;
  /** Bleibt bei einer Neuberechnung der Rollen erhalten, statt neu abgeleitet zu werden. */
  special: boolean;
  /** Die Ingame-Titel, die diese Rolle derzeit vergeben. */
  grantingTitles: string[];
}

export interface SaveRoleDto {
  name: string;
  description: string;
  special: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class GroupService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/groups`;

  getCorporationTitles(): Observable<CorpTitleDto[]> {
    return this.http.get<CorpTitleDto[]>(`${this.apiUrl}/titles`);
  }

  /** Ein leerer Rollenname löst die Zuordnung - der Titel vergibt dann nichts mehr. */
  saveTitleMapping(titleId: number, roleName: string): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/titles/mapping`, { titleId, roleName });
  }

  getRoles(): Observable<AuthRoleDto[]> {
    return this.http.get<AuthRoleDto[]>(`${this.apiUrl}/roles`);
  }

  /** Der Server normalisiert den Namen und gibt die Rolle so zurück, wie sie gespeichert wurde. */
  saveRole(role: SaveRoleDto): Observable<AuthRoleDto> {
    return this.http.post<AuthRoleDto>(`${this.apiUrl}/roles`, role);
  }

  deleteRole(roleName: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/roles/${encodeURIComponent(roleName)}`);
  }
}
