import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Eine Gruppe, gesehen vom angemeldeten Charakter.
 *
 * Die drei Kennzeichen am Ende kommen vom Server und nicht aus einer Rechnung
 * im Browser: nur der Server weiß, welche Rollen der Charakter wirklich trägt
 * und welche Anfrage noch offen ist.
 */
export interface GroupDto {
  id: number;
  name: string;
  description: string | null;
  roleName: string;
  /**
   * Die Rollen, die über Anfragen entscheiden - keine Charaktere mehr.
   *
   * Hinter einer Rolle stehen mehrere Träger; ein Name samt Portrait wäre
   * deshalb gelogen. Zuständig ist, wer mindestens eine davon trägt - eine
   * Gruppe kann durchaus zwei Kreise haben ("FC_Strat UND FC_Skirmish").
   *
   * Die Liste ist immer da und kommt sortiert vom Server; leer heißt: nur die
   * globalen Admins entscheiden.
   */
  leaderRoleNames: string[];
  /**
   * Wie viele Charaktere die Rolle tragen.
   *
   * Nicht die Zahl der offenen Anfragen - eine Gruppe kann mit laufendem
   * Antrag durchaus noch null Mitglieder haben.
   */
  memberCount: number;
  isMember: boolean;
  hasPendingRequest: boolean;
  /** Ob der Betrachter mindestens eine der Leitungsrollen dieser Gruppe trägt. */
  isLeader: boolean;
}

export interface GroupRequestDto {
  requestId: number;
  groupId: number;
  groupName: string;
  characterId: number;
  characterName: string;
  portraitUrl: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  /** ISO-8601 aus `Instant` - für die Anzeige mit der `date`-Pipe. */
  requestedAt: string;
}

/** Was die Verwaltung beim Anlegen oder Ändern schickt; `id === null` heißt: neu. */
export interface SaveGroupDto {
  id: number | null;
  name: string;
  description: string | null;
  /**
   * Darf beim Anlegen leer bleiben: dann leitet der Server den Namen aus dem
   * Gruppennamen ab und legt die Rolle an. Das Formular schlägt denselben Namen
   * vor, damit dort steht, was gleich entsteht.
   */
  roleName: string;
  /** Leere Liste erlaubt - dann entscheiden allein die Admins über die Anfragen. */
  leaderRoleNames: string[];
}

export type GroupDecision = 'approve' | 'reject';

/**
 * Die Gruppen (SIGs) und ihre Beitrittsanfragen.
 *
 * <p>Bewusst getrennt vom `GroupService`: der bedient die Titel- und
 * Rollenverwaltung unter denselben Pfaden `/api/groups/...`. Der
 * Rollenkatalog für das Modal - Vorschlagsliste der Gruppenrolle und
 * Kästchenliste der Leitungsrollen - wird deshalb weiterhin dort geholt; hier
 * entsteht keine zweite Quelle dafür.</p>
 */
@Injectable({
  providedIn: 'root',
})
export class AuthGroupService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/groups`;

  /**
   * Die Pflege liegt unter einem eigenen Präfix, weil `/api/groups` mit
   * `/titles` und `/roles` bereits belegt ist - ein `POST /api/groups` würde
   * dort mit den bestehenden Endpunkten kollidieren.
   */
  private adminUrl = `${environment.apiUrl}/admin/groups`;

  getGroups(): Observable<GroupDto[]> {
    return this.http.get<GroupDto[]>(this.apiUrl);
  }

  applyForGroup(groupId: number): Observable<GroupRequestDto> {
    return this.http.post<GroupRequestDto>(`${this.apiUrl}/${groupId}/apply`, {});
  }

  /**
   * Austritt aus der eigenen Gruppe - ohne Rückfrage beim Leiter.
   *
   * <p>Der Charakter steht bewusst nicht im Pfad: der Server nimmt den
   * angemeldeten. Sonst wäre der Endpunkt ein Hebel, Fremde hinauszuwerfen.
   * Ein Wiedereintritt läuft über {@link applyForGroup}.</p>
   */
  leaveGroup(groupId: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/${groupId}/leave`, {});
  }

  /** Der Server schneidet zu: ein Leiter bekommt nur seine Gruppen, ein Admin alle. */
  getOpenRequests(): Observable<GroupRequestDto[]> {
    return this.http.get<GroupRequestDto[]>(`${this.apiUrl}/requests`);
  }

  decideRequest(requestId: number, decision: GroupDecision): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/requests/${requestId}/${decision}`, {});
  }

  getAdminGroups(): Observable<GroupDto[]> {
    return this.http.get<GroupDto[]>(this.adminUrl);
  }

  saveGroup(group: SaveGroupDto): Observable<GroupDto> {
    return this.http.post<GroupDto>(this.adminUrl, group);
  }

  deleteGroup(id: number): Observable<void> {
    return this.http.delete<void>(`${this.adminUrl}/${id}`);
  }
}
