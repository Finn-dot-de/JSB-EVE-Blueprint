import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PingErwaehnung } from '../shared/fleet-ping-nachricht.util';

export type { PingErwaehnung };

/** Der Lebenslauf eines Pings - Spiegel von `PingZustand`. */
export type PingZustand = 'GEPOSTET' | 'GEAENDERT' | 'ABGESAGT';

/**
 * Was an den Server geht.
 *
 * <p>`formupTime` ist ISO-8601 **mit** Versatz (`2026-09-03T19:00:00Z`). Ohne
 * Versatz weist Jackson die Anfrage ab, und das ist beabsichtigt: eine Zeit
 * ohne Zone wäre entweder die des Servers, die des Browsers oder EVE-Zeit, und
 * welche gemeint war, ließe sich hinterher nicht mehr feststellen. `null` heißt
 * "form up now" und ist keine fehlende Angabe.</p>
 *
 * <p>`srpCovered` darf `null` sein: "nicht gesagt" ist eine eigene Aussage und
 * darf nicht als "nein" hinausgehen.</p>
 */
export interface PingRequestDto {
  fleetType: string;
  doctrine: string | null;
  formupLocation: string;
  formupTime: string | null;
  comms: string | null;
  srpCovered: boolean | null;
  notes: string | null;
  erwaehnung: PingErwaehnung;
  /**
   * Welche Rolle bei `ROLLE` gerufen wird - die Discord-Rollenkennung.
   *
   * <p>Sie kommt aus der Auswahl und damit aus dem Request. Der Server prüft
   * sie gegen die im Auth gepflegten Zuordnungen und weist eine unbekannte
   * Kennung ab - er setzt sie **nicht** still auf eine Vorgabe zurück. Das
   * Formular muss deshalb tatsächlich eine wählen; `null` bedeutet "keine
   * Angabe" und trifft dann die serverseitige Vorbelegung, falls es eine
   * gibt.</p>
   */
  rolleId: string | null;
}

/**
 * Eine Rolle, die sich anpingen lässt.
 *
 * <p>`name` ist der echte Discord-Rollenname, wenn er zu holen war, sonst der
 * Auth-Rollenname. Eine nackte `1539289011737329796` kann niemand zuordnen -
 * der FC wählt nach dem Namen, den er im Discord sieht.</p>
 */
export interface PingRolleDto {
  discordRoleId: string;
  authRole: string;
  name: string;
  /** Die im Server vorbelegte Rolle. Höchstens eine. */
  vorbelegt: boolean;
}

/** Ein Ping, wie ihn die Rechenschaftsliste zeigt. */
export interface PingResponseDto {
  id: number;
  fcCharacterId: number;
  fcCharacterName: string;
  fleetType: string;
  doctrine: string | null;
  formupLocation: string;
  formupTime: string | null;
  comms: string | null;
  srpCovered: boolean | null;
  notes: string | null;
  erwaehnung: PingErwaehnung;
  /** Bei `ROLLE`: welche Rolle es getroffen hat. Sonst `null`. */
  erwaehnungRolleId: string | null;
  zustand: PingZustand;
  /** Die Discord-Nachrichten-ID - der einzige Faden zwischen Liste und Kanal. */
  discordMessageId: string;
  createdAt: string;
  updatedAt: string;
  cancelledAt: string | null;
  cancelReason: string | null;
}

/**
 * Ob die Funktion überhaupt eingerichtet ist.
 *
 * <p>`rolleKonfiguriert` steht getrennt daneben, weil eine nicht hinterlegte
 * Rolle nicht die ganze Funktion abschaltet, sondern genau eine Auswahl
 * wirkungslos macht. Ohne diese Auskunft böte die Oberfläche eine Erwähnung an,
 * die still verpufft - und der FC glaubte, er hätte gepingt.</p>
 */
export interface PingStatusDto {
  verfuegbar: boolean;
  rolleKonfiguriert: boolean;
  hinweis: string | null;
}

/**
 * Die Flotten-Pings.
 *
 * <p>Eine dünne Hülle um je eine Adresse. Bewusst getrennt vom `FleetService`:
 * der bedient `/api/fleets` und damit den rückwirkenden FAT-Nachweis. Ein Ping
 * ist die Ankündigung vorher und weiß nichts über Teilnehmer - dieselbe
 * Trennung wie zwischen `FleetEvent` und `FleetPing` im Server.</p>
 */
@Injectable({ providedIn: 'root' })
export class FleetPingService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/fleet/pings`;

  /** Ob ein Discord-Kanal hinterlegt ist - vor dem ersten Formular zu fragen. */
  status(): Observable<PingStatusDto> {
    return this.http.get<PingStatusDto>(`${this.apiUrl}/status`);
  }

  /**
   * Die Rollen, die sich anpingen lassen.
   *
   * <p>Erst beim Wählen abgerufen und nicht beim Öffnen des Reiters: Der Server
   * fragt dafür die Rollen des Discord-Servers ab, und das kostet einen Aufruf
   * nach draußen, den die meisten Pings nie brauchen.</p>
   */
  rollen(): Observable<PingRolleDto[]> {
    return this.http.get<PingRolleDto[]>(`${this.apiUrl}/rollen`);
  }

  /** Die letzten Pings, neueste zuerst. Der Server begrenzt auf 50. */
  letzte(): Observable<PingResponseDto[]> {
    return this.http.get<PingResponseDto[]>(this.apiUrl);
  }

  senden(dto: PingRequestDto): Observable<PingResponseDto> {
    return this.http.post<PingResponseDto>(this.apiUrl, dto);
  }

  /** PUT und nicht POST: derselbe Ping, dieselbe Discord-Nachricht. */
  bearbeiten(id: number, dto: PingRequestDto): Observable<PingResponseDto> {
    return this.http.put<PingResponseDto>(`${this.apiUrl}/${id}`, dto);
  }

  /**
   * Die Absage.
   *
   * <p>POST auf `/absage` und nicht DELETE: gelöscht wird nichts. Der Ping
   * bleibt in der Liste, und im Kanal bleibt die Nachricht stehen - sie sagt ab
   * jetzt nur etwas anderes. Ein DELETE verspräche ein Verschwinden, das es
   * nicht gibt.</p>
   */
  absagen(id: number, grund: string | null): Observable<PingResponseDto> {
    return this.http.post<PingResponseDto>(`${this.apiUrl}/${id}/absage`, { grund });
  }
}
