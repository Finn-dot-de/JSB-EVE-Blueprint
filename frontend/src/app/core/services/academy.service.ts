import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Ein Thema, gesehen vom Aufrufer - <b>ohne</b> Lehrplan.
 *
 * <p>Spiegelt `AcademyDtos.TopicDto` eins zu eins, samt Reihenfolge der Felder.
 * Der Lehrplan fehlt mit Absicht: bei zwölf Themen gingen sonst zwölf
 * Lehrpläne über die Leitung, bei jedem Laden der Liste. Er kommt beim
 * Aufklappen über {@link AcademyService.getTopic} nach.</p>
 */
export interface TopicDto {
  id: number;
  title: string;
  summary: string;
  active: boolean;

  /** Wer das Thema halten darf. Leer heißt: nur der Autorenkreis sieht die Namen. */
  teacherRoleNames: string[];

  /**
   * Wie viele Leute Interesse bekundet haben - für jeden Angemeldeten sichtbar.
   * Karteileichen sind schon im Server herausgefallen.
   */
  interestCount: number;

  /**
   * Wie oft jeder Wochentag genannt wurde, in `DayOfWeek`-Ordnung.
   *
   * <p><b>Leer unterhalb von zwei Bekundungen</b> - das leere Objekt ist das
   * Signal "keine Verteilung anzeigen" und keine fehlende Antwort. Bei genau
   * einer Bekundung verriete "nur Mittwoch, USTZ" in einer Corp, in der sich
   * alle kennen, faktisch den Namen. Ab der Schwelle sind alle sieben Tage
   * enthalten, auch mit Wert 0, damit sich zwei Karten vergleichen lassen.</p>
   */
  weekdayCounts: Record<string, number>;

  /** Dasselbe für die fünf Zeitfenster, in Tagesreihenfolge, mit derselben Schwelle. */
  windowCounts: Record<string, number>;

  /** Die eigene Bekundung - mitgeliefert, damit die Oberfläche mit einem Ladevorgang auskommt. */
  myWeekdays: string[];
  myTimeWindows: string[];
  myNote: string | null;
  hasMyInterest: boolean;

  /** Ob der Betrachter Themen anlegen, ändern und löschen darf. */
  canEdit: boolean;

  /**
   * Ob er die <b>Namen</b> der Interessenten abrufen darf.
   *
   * <p>Steht neben `interestCount` und nicht in ihm: die Zahl ist eine
   * Auskunft, die Berechtigung eine Zusicherung. Wer aus einer Zahl auf ein
   * Recht schließt, baut eine Ableitung, die nirgends geschrieben steht und
   * deshalb still falsch wird.</p>
   */
  canViewInterest: boolean;
}

/** Das Thema samt Lehrplan - die Antwort auf das Aufklappen einer Karte. */
export interface TopicDetailDto {
  topic: TopicDto;

  /**
   * Roher Markdown-Text, kein fertiges HTML.
   *
   * <p>Das ist die Sicherheitsentscheidung dieses Features: gerendert wird
   * ausschließlich im Browser, aus einem Token-Modell heraus. `null`, solange
   * niemand einen Lehrplan geschrieben hat.</p>
   */
  description: string | null;
}

/** Ein Interessent mit Namen - nur für den Sichtkreis. */
export interface InterestDto {
  accountId: number;
  characterName: string;
  weekdays: string[];
  timeWindows: string[];
  note: string | null;

  /** ISO-8601 vom Server. */
  updatedAt: string;
}

/** Was die Verwaltung schickt; `id === null` heißt: neu. */
export interface SaveTopicDto {
  id: number | null;
  title: string;
  summary: string;
  description: string | null;
  active: boolean;
  teacherRoleNames: string[];
}

/**
 * Was eine Bekundung ausmacht - und was sie ausdrücklich <b>nicht</b> enthält.
 *
 * <p><b>Keine `accountId`, keine `characterId`.</b> Der Handelnde kommt
 * ausschließlich aus der Sitzung. Wer diesen Datensatz später um ein solches
 * Feld erweitert, hebt die Sicherheitseigenschaft auf - dann gäbe es plötzlich
 * eine fremde ID, die jemand hereinreichen könnte.</p>
 */
export interface SaveInterestDto {
  weekdays: string[];
  timeWindows: string[];
  note: string | null;
}

/**
 * Die Academy: Themen lesen, Interesse bekunden, Themen pflegen.
 *
 * <p>Zwei Basisadressen, wie beim `AuthGroupService`: die Pflege liegt unter
 * `/api/admin/academy` und damit hinter einem eigenen Rechtekreis. Getrennte
 * Pfade halten die beiden Kreise auch dann auseinander, wenn später jemand am
 * Controller schraubt.</p>
 *
 * <p>`withCredentials` setzt der Interceptor global - hier steht es deshalb
 * nicht noch einmal. `environment.apiUrl` trägt das `/api` bereits.</p>
 */
@Injectable({ providedIn: 'root' })
export class AcademyService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/academy`;
  private adminUrl = `${environment.apiUrl}/admin/academy`;

  /** Die angebotenen Themen samt Nachfragebild - ohne die Lehrpläne. */
  getTopics(): Observable<TopicDto[]> {
    return this.http.get<TopicDto[]>(`${this.apiUrl}/topics`);
  }

  /** Ein Thema samt Lehrplan - der Aufruf beim Aufklappen einer Karte. */
  getTopic(topicId: number): Observable<TopicDetailDto> {
    return this.http.get<TopicDetailDto>(`${this.apiUrl}/topics/${topicId}`);
  }

  /**
   * Bekundet Interesse oder schreibt die bestehende Bekundung um.
   *
   * <p>`PUT` und nicht `POST`: der Aufruf ist idempotent, er identifiziert sich
   * vollständig aus Pfad und Sitzung. Der Account steht bewusst nicht im Pfad -
   * ein Parameter dafür wäre eine Hintertür, jedem beliebigen Mitglied eine
   * beliebige Bekundung unterzuschieben.</p>
   *
   * @returns das Thema mit frisch gerechneten Zählern, damit die Oberfläche die
   *     eine Karte umschreiben kann statt die ganze Liste neu zu laden
   */
  saveInterest(topicId: number, interest: SaveInterestDto): Observable<TopicDto> {
    return this.http.put<TopicDto>(`${this.apiUrl}/topics/${topicId}/interest`, interest);
  }

  /**
   * Zieht die eigene Bekundung zurück.
   *
   * <p>Die Zeile verschwindet wirklich: es gibt keinen Zustand
   * "zurückgezogen", weil es nie einen Antrag gab.</p>
   */
  withdrawInterest(topicId: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/topics/${topicId}/interest`);
  }

  /**
   * Wer Interesse bekundet hat, mit Namen.
   *
   * <p>Der engste Kreis des Dienstes: Autorenkreis plus die am Thema
   * hinterlegten Ausbilderrollen. Jeder andere bekommt 403 - und keine leere
   * Liste, die behauptete, niemand habe Interesse. Der Fehler muss deshalb beim
   * Aufrufer ankommen und darf hier nicht abgefangen werden.</p>
   */
  getInterested(topicId: number): Observable<InterestDto[]> {
    return this.http.get<InterestDto[]>(`${this.apiUrl}/topics/${topicId}/interest`);
  }

  /** Alle Themen, auch die abgeschalteten - nur für den Autorenkreis. */
  getAdminTopics(): Observable<TopicDto[]> {
    return this.http.get<TopicDto[]>(`${this.adminUrl}/topics`);
  }

  saveTopic(topic: SaveTopicDto): Observable<TopicDto> {
    return this.http.post<TopicDto>(`${this.adminUrl}/topics`, topic);
  }

  deleteTopic(topicId: number): Observable<void> {
    return this.http.delete<void>(`${this.adminUrl}/topics/${topicId}`);
  }
}
