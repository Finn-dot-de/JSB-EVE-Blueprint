import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Ein Skill auf einer geforderten Stufe. */
export interface SkillEntryDto {
  skillTypeId: number;
  skillName: string;
  level: number;
}

/**
 * Ein benannter Skillplan.
 *
 * Er ergänzt, was die Stammdaten nicht hergeben: die Voraussetzungen eines
 * Moduls sagen nur, ob es sich einschalten lässt - nicht, ob der Pilot damit
 * etwas ausrichtet.
 */
export interface SkillPlanDto {
  id: number;
  name: string;
  description: string | null;
  skills: SkillEntryDto[];
  /** An wie vielen Fittings der Plan hängt. */
  usedByFittings: number;
}

export interface SaveSkillPlanDto {
  id: number | null;
  name: string;
  description: string | null;
  skills: SkillEntryDto[];
}

export interface SkillOptionDto {
  typeId: number;
  typeName: string;
}

export interface ImportResultDto {
  skills: SkillEntryDto[];
  /** Zeilen, zu denen kein Skill gefunden wurde. */
  unresolved: string[];
}

@Injectable({ providedIn: 'root' })
export class SkillPlanService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/skill-plans`;

  list(): Observable<SkillPlanDto[]> {
    return this.http.get<SkillPlanDto[]>(this.apiUrl);
  }

  /** Vorschläge für den Plus-Knopf. */
  searchSkills(query: string): Observable<SkillOptionDto[]> {
    const params = new HttpParams().set('q', query);
    return this.http.get<SkillOptionDto[]>(`${this.apiUrl}/skills`, { params });
  }

  save(plan: SaveSkillPlanDto): Observable<SkillPlanDto> {
    return this.http.post<SkillPlanDto>(this.apiUrl, plan);
  }

  delete(planId: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${planId}`);
  }

  /** Wandelt einen eingefügten Plantext in Einträge um, ohne etwas zu speichern. */
  importPlanText(planText: string): Observable<ImportResultDto> {
    return this.http.post<ImportResultDto>(`${this.apiUrl}/import`, { planText });
  }

  /** Legt fest, welche Pläne an einem Fitting hängen. */
  assign(doctrineId: number, planIds: number[]): Observable<void> {
    return this.http.put<void>(`${this.apiUrl}/assign/${doctrineId}`, { planIds });
  }
}
