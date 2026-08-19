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
   * Wie viele Charaktere die Rolle tragen - oder `null`, wenn der Betrachter
   * gar nicht erfahren darf, wer in der Gruppe ist.
   *
   * Nicht die Zahl der offenen Anfragen - eine Gruppe kann mit laufendem
   * Antrag durchaus noch null Mitglieder haben.
   *
   * Bewusst `null` und nicht `0`: die Null behauptete, die Gruppe sei leer -
   * dieselbe Falschaussage, aus der der Server für Unberechtigte eine 403 macht
   * statt einer leeren Liste.
   *
   * `null` ist trotzdem KEIN Rechtekennzeichen mehr: dafür steht
   * {@link canViewMembers} daneben. Wer hier auf ein Recht schließt, baut die
   * Kopplung wieder auf, die genau deshalb aufgelöst wurde - heute fällt beides
   * zusammen, aber nur, weil der Server es heute so füllt.
   */
  memberCount: number | null;
  /**
   * Ob der Betrachter erfahren darf, WER in dieser Gruppe ist.
   *
   * Die Zusicherung zur Zahl darüber: der Server setzt beides aus derselben
   * Prüfung, die auch über `GET /{id}/members` entscheidet
   * (`AuthGroupService.mayViewMembers`). `true` heißt deshalb zeichengenau
   * "diese Liste bekommst du", `false` heißt "403".
   *
   * Ein eigenes Feld und nicht das Fehlen der Zahl: die Zahl ist eine Auskunft,
   * dies eine Berechtigung. Werden die beiden im Backend je entkoppelt - Zahl
   * für alle, Liste nur für den Kreis oder umgekehrt -, folgt die Oberfläche
   * dem Feld und wird nicht still falsch.
   *
   * `boolean` und nicht `boolean | null`: "unbekannt, ob erlaubt" gibt es
   * nicht - der Server weiß es immer.
   */
  canViewMembers: boolean;
  isMember: boolean;
  hasPendingRequest: boolean;
  /** Ob der Betrachter mindestens eine der Leitungsrollen dieser Gruppe trägt. */
  isLeader: boolean;
}

/**
 * Ein Mitglied einer Gruppe: ein Charakter, der ihre `roleName` trägt.
 *
 * <p>Dieselben drei Felder wie der Kopf einer {@link GroupRequestDto} - die
 * Mitgliederliste steht in derselben Oberfläche wie die Anfrageliste, und zwei
 * Feldnamen für dieselbe Person wären dort nur eine Stolperstelle.</p>
 *
 * <p>Ohne Rechtekennzeichen am Mitglied: ob entfernt werden darf, hängt an der
 * Gruppe und nicht an der Person - das sagt {@link GroupDto.isLeader}.</p>
 */
export interface GroupMemberDto {
  characterId: number;
  characterName: string;
  portraitUrl: string;
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

  /**
   * Die Mitglieder einer Gruppe - Name und Portrait, nach Namen sortiert.
   *
   * <p>Ein eigener Rechtekreis, und zwar der engste des Dienstes: wer die
   * Gruppe sieht, sieht deshalb noch lange nicht, wer in ihr ist. Erlaubt sind
   * Direktoren, CEO, IT-Admin und A38; jeder andere bekommt 403 - und keine
   * leere Liste, die behauptete, die Gruppe sei leer.</p>
   *
   * <p>Der Betrachter steht trotzdem nicht im Pfad: der Server nimmt den
   * angemeldeten Charakter. Ob er darf, entscheidet allein er; die Oberfläche
   * fragt vorher {@link GroupDto.canViewMembers} und bietet den Aufruf gar nicht
   * erst an, wo er zuverlässig in eine 403 liefe.</p>
   *
   * <p>Bewusst ein eigener Aufruf und kein Feld an {@link GroupDto}: eine
   * Corporation mit zwanzig SIGs würde beim Seitenaufbau sonst zwanzig
   * Mitgliederlisten mitschleppen, von denen keine jemand ansieht.</p>
   */
  getMembers(groupId: number): Observable<GroupMemberDto[]> {
    return this.http.get<GroupMemberDto[]>(`${this.apiUrl}/${groupId}/members`);
  }

  /**
   * Nimmt einem <b>fremden</b> Charakter die Rolle dieser Gruppe ab.
   *
   * <p>Der einzige Aufruf dieses Dienstes mit einer fremden Charakter-Id - das
   * genaue Gegenteil von {@link leaveGroup}, wo sie mit Absicht fehlt. Ob der
   * Aufrufer darf, entscheidet allein der Server; `isLeader` blendet hier nur
   * den Knopf aus.</p>
   *
   * <p>`DELETE`, weil wirklich etwas entfernt wird; die Antwort ist leer.</p>
   */
  removeMember(groupId: number, characterId: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${groupId}/members/${characterId}`);
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
