import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface RequiredSkillDto {
  skillTypeId: number;
  skillName: string;
  level: number;
}

export interface MissingSkillDto {
  skillTypeId: number;
  skillName: string;
  requiredLevel: number;
  currentLevel: number;
}

export interface CharacterReadinessDto {
  characterId: number;
  characterName: string;
  portraitUrl: string;
  main: boolean;
  owned: number;
  skillDataAvailable: boolean;
  /** Rumpf und alle Module bedienbar. */
  canFly: boolean;
  /**
   * Nur der Rumpf bedienbar. Trennt "kann das Schiff gar nicht fliegen" von
   * "es fehlen bloß ein paar Modul-Skills" - zwei sehr verschiedene Abstände
   * zur Einsatzbereitschaft.
   */
  canFlyHull: boolean;
  skillsMet: number;
  skillsRequired: number;
  missingSkills: MissingSkillDto[];
}

export interface AccountReadinessDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  corporationName: string | null;
  owned: number;
  charactersOwning: number;
  canFly: boolean;
  pilotsCapable: number;
  skillDataAvailable: boolean;
  bestSkillsMet: number;
  skillsRequired: number;
  hasShip: boolean;
  hasSkills: boolean;
  isReady: boolean;
  characters: CharacterReadinessDto[];
}

/**
 * Ein Fit und wer ihn stellen kann.
 *
 * Geprüft wird der komplette Fit: der Hangar auf den Rumpf, die Skills auf
 * Rumpf, Module, Drohnen und Ladung. Zwei Fits derselben Hülle stehen darum
 * getrennt im Board - sie verlangen Unterschiedliches.
 */
export interface FitReadinessDto {
  fitId: number | null;
  fitName: string | null;
  typeId: number;
  typeName: string;
  iconUrl: string;
  renderUrl: string;
  /** Verbaute Module, Drohnen und Ladung. */
  moduleCount: number;
  /** Die Vereinigung über Rumpf und alle Module, höchste Stufe je Skill. */
  requiredSkills: RequiredSkillDto[];
  /** Davon der Anteil, der allein auf den Rumpf entfällt. */
  hullSkillsRequired: number;
  /** Einträge des EFT-Texts, die die Stammdaten nicht kannten - ungeprüft. */
  unresolved: string[];
  hullsTotal: number;
  accountsReady: number;
  accountsTotal: number;
  coverage: number;
  ready: AccountReadinessDto[];
  notReady: AccountReadinessDto[];
}

export interface DoctrineReadinessDto {
  doctrineName: string | null;
  accountsTotal: number;
  fitsChecked: number;
  fits: FitReadinessDto[];
}

export interface FitModuleDto {
  typeId: number;
  typeName: string;
  iconUrl: string;
  quantity: number;
  chargeName: string | null;
  chargeTypeId: number | null;
}

export interface FitSlotGroupDto {
  name: string;
  icon: string;
  moduleCount: number;
  modules: FitModuleDto[];
}

export interface ParsedFitDto {
  shipTypeId: number;
  shipTypeName: string;
  fitName: string | null;
  iconUrl: string;
  renderUrl: string;
  moduleCount: number;
  groups: FitSlotGroupDto[];
  unresolved: string[];
}

export interface SandboxResultDto {
  fit: ParsedFitDto;
  board: FitReadinessDto;
}

@Injectable({ providedIn: 'root' })
export class ReadinessService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/fleet/readiness`;

  doctrines(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/doctrines`);
  }

  checkBoard(doctrineName: string | null): Observable<DoctrineReadinessDto> {
    let params = new HttpParams();
    if (doctrineName) params = params.set('doctrineName', doctrineName);
    return this.http.get<DoctrineReadinessDto>(`${this.apiUrl}/board`, { params });
  }

  sandbox(eftString: string): Observable<SandboxResultDto> {
    return this.http.post<SandboxResultDto>(`${this.apiUrl}/sandbox`, { eftString });
  }
}
