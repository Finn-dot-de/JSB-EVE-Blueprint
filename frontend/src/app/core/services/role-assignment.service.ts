import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthRoleSource } from './group.service';

/**
 * Was ein Klick auf diese Rolle bewirken würde - die Auskunft VOR der Tat.
 *
 * <p>Die Kennzeichen kommen fertig vom Server und werden hier bewusst nicht
 * nachgerechnet. Ob sich eine Rolle vergeben lässt, hängt an drei Fakten
 * (eingebaut, ein Titel vergibt sie, dauerhaft markiert), die auch der Dienst
 * beim eigentlichen Zuweisen prüft. Dieselbe Ableitung ein zweites Mal in
 * TypeScript hiesse: sie laufen irgendwann auseinander, und dann zeigt die
 * Oberfläche einen Knopf, der 400 liefert - oder verbirgt einen, der
 * funktioniert hätte.</p>
 */
export interface RoleStateDto {
  roleName: string;
  description: string;
  source: AuthRoleSource;
  /** Ob der Charakter die Rolle derzeit trägt. */
  held: boolean;
  /** Ob die Rolle den Rollen-Abgleich überdauert, statt alle zehn Minuten neu abgeleitet zu werden. */
  survivesSync: boolean;
  assignable: boolean;
  /** `false` bei einer Rolle, die ein Ingame-Titel vergibt - der Entzug hielte nicht. */
  revocable: boolean;
  /** Die Ingame-Titel, die diese Rolle vergeben. */
  grantingTitles: string[];
  /** Der anzuzeigende Satz, warum es so ist, wie es ist. Kommt fertig vom Server. */
  note: string;
}

export interface CharacterRolesDto {
  characterId: number;
  characterName: string;
  portraitUrl: string;
  /** Getragene Rollen zuerst, darin alphabetisch - die Reihenfolge macht der Server. */
  roles: RoleStateDto[];
}

export type RoleAuditAction = 'GRANT' | 'REVOKE';

/**
 * Ein Eintrag aus dem Nachweis: wer wem wann welche Rolle gab oder nahm.
 *
 * <p>Er rekonstruiert die Vergangenheit nicht. Ein leerer Verlauf heisst
 * "seit Einführung von Hand nichts geändert", nie "war schon immer da".</p>
 */
export interface RoleAuditDto {
  id: number;
  characterId: number;
  characterName: string;
  portraitUrl: string;
  roleName: string;
  action: RoleAuditAction;
  actorCharacterId: number;
  actorName: string;
  /** Ob der Handelnde sich selbst bedient hat - eigenes Feld, damit die Anzeige es hervorheben kann. */
  selfAssigned: boolean;
  reason: string | null;
  occurredAt: string;
}

/**
 * Rollen einzelner Charaktere: zuweisen, entziehen, nachlesen.
 *
 * <p>Eigener Dienst und nicht im `GroupService`: der liegt auf `/api/groups`
 * und beantwortet, welche Rollen es <em>gibt</em>. Hier geht es darum, wer sie
 * <em>hat</em> - eine andere Frage an einen anderen Endpunkt.</p>
 */
@Injectable({ providedIn: 'root' })
export class RoleAssignmentService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/roles`;

  rolesOf(characterId: number): Observable<CharacterRolesDto> {
    return this.http.get<CharacterRolesDto>(`${this.apiUrl}/characters/${characterId}`);
  }

  /**
   * Gibt dem Charakter eine Rolle.
   *
   * <p>Der Handelnde steht nicht im Rumpf - den nimmt der Server aus der
   * Sitzung, sonst schriebe der Aufrufer den Nachweis über sich selbst.</p>
   *
   * @param reason freiwillig; ein leerer Text ist ehrlicher als ein erzwungenes "x"
   */
  grant(characterId: number, roleName: string, reason: string): Observable<RoleAuditDto> {
    return this.http.post<RoleAuditDto>(`${this.apiUrl}/characters/${characterId}/grant`, {
      roleName,
      reason,
    });
  }

  /**
   * Nimmt dem Charakter eine Rolle wieder ab.
   *
   * <p>`POST` und nicht `DELETE`, weil der Grund in den Rumpf gehört: in der
   * Adresszeile landete er in jedem Zugriffsprotokoll.</p>
   */
  revoke(characterId: number, roleName: string, reason: string): Observable<RoleAuditDto> {
    return this.http.post<RoleAuditDto>(`${this.apiUrl}/characters/${characterId}/revoke`, {
      roleName,
      reason,
    });
  }

  auditFor(characterId: number): Observable<RoleAuditDto[]> {
    return this.http.get<RoleAuditDto[]>(`${this.apiUrl}/characters/${characterId}/audit`);
  }

  /** Die jüngsten Änderungen über alle Charaktere hinweg - der Server begrenzt auf 200. */
  recentAudit(): Observable<RoleAuditDto[]> {
    return this.http.get<RoleAuditDto[]>(`${this.apiUrl}/audit`);
  }
}
