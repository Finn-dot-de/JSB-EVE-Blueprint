import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface DiscordMapping {
  authRole: string;
  discordRoleId: string;
  description: string;
}

/** Ein mit dem Konto verknüpfter Charakter und die Rollen, die er ergäbe. */
export interface DiscordCharakterSoll {
  characterId: number;
  name: string;
  sollRollen: string[];
}

/**
 * Der Stand einer einzelnen Rolle.
 *
 * <p>`NICHT_FESTSTELLBAR` ist kein Sonderfall von `FEHLT`. Verweigert Discord
 * die Auskunft, ist über die Rollen des Kontos <em>nichts</em> bekannt - wer das
 * als "fehlt" anzeigt, meldet ausgerechnet am Server-Owner sämtliche Rollen als
 * verloren, und zwar dauerhaft.</p>
 */
export type DiscordZustand = 'VORHANDEN' | 'FEHLT' | 'NICHT_FESTSTELLBAR';

/**
 * Warum eine Auth-Rolle in Discord nicht ankommt - wortgleich zur Aufzählung im
 * Backend (`DiscordRollenBefund.Ursache`).
 *
 * <p>Als Aufzählung und nicht als Text, damit sich die Fälle unterscheiden
 * lassen: "fehlt" ist die halbe Auskunft, gehandelt wird nach der Ursache. Der
 * ausformulierte Satz dazu kommt als `grund` mit - ihn hier ein zweites Mal zu
 * schreiben hieße, zwei Texte für dieselbe Aussage zu pflegen.</p>
 */
export type DiscordUrsache =
  | 'KEIN_MAPPING'
  | 'MAPPING_OHNE_ROLLEN_ID'
  | 'KEINE_VERKNUEPFUNG'
  | 'ZUGRIFF_VERWEIGERT'
  | 'KONTO_NICHT_AUF_SERVER'
  | 'DISCORD_NICHT_ERREICHBAR'
  | 'ROLLE_AUF_SERVER_UNBEKANNT'
  | 'ABGLEICH_STEHT_AUS'
  | 'UNBEKANNT';

/** Eine Zeile der Gegenüberstellung: eine Auth-Rolle und was in Discord aus ihr wurde. */
export interface DiscordRollenBefund {
  authRolle: string;
  /** `null`, wenn keine Discord-Rolle zugeordnet ist - genau das ist eine der Ursachen. */
  discordRoleId: string | null;
  /** Der Name auf dem Server, falls er sich lesen ließ. Kür - die ID genügt zur Arbeit. */
  discordRoleName: string | null;
  zustand: DiscordZustand;
  /** `null`, solange die Rolle sitzt. */
  ursache: DiscordUrsache | null;
  /** Dieselbe Aussage im Klartext, ggf. mit Einzelheiten, die kein fester Text kennt. */
  grund: string | null;
}

/**
 * Eine Rolle, die das Konto trägt, ohne dass eine Auth-Rolle sie fordert.
 *
 * <p>`verwaltet: false` heißt: Das Auth hat sie nie vergeben und weiß über sie
 * nichts - eine Farb-, Ping- oder Standardrolle. Sie ist <b>kein Befund</b> und
 * darf nirgends als "überzählig" erscheinen.</p>
 */
export interface DiscordVorhandeneRolle {
  discordRoleId: string;
  name: string | null;
  verwaltet: boolean;
}

/**
 * Dieselbe Prüfung, gelesen aus der Sicht <b>eines Charakters</b>.
 *
 * <p>Gerechnet wird je Konto - anders fiele der Fall "zwei Charaktere, ein
 * Konto" auseinander. Gefragt wird aber nach Charakteren: "Was hat Tom, und was
 * fehlt ihm." `mainCharacterName` gehört dazu, weil das Soll am Main hängt:
 * Steht in Toms Zeile eine Rolle, die Tom selbst gar nicht hat, kommt sie von
 * dort.</p>
 */
export interface DiscordCharacterAudit {
  characterId: number;
  characterName: string;
  mainCharacterId: number;
  mainCharacterName: string;
  discordUserId: string | null;
  verknuepft: boolean;
  /** Ob der Ist-Zustand überhaupt gelesen werden konnte. */
  pruefbar: boolean;
  hinweis: string | null;
  rollen: DiscordRollenBefund[];
  weitereDiscordRollen: DiscordVorhandeneRolle[];
  sollUneinig: boolean;
}

/** Eine Rolle und was der angestoßene Abgleich mit ihr gemacht hat. */
export interface DiscordSyncZeile {
  /** `null` bei einer verwalteten Rolle, die dieser Charakter nicht haben soll. */
  authRolle: string | null;
  discordRoleId: string;
  aktion: 'VERGEBEN' | 'ENTZOGEN';
  erfolg: boolean;
  grund: string | null;
}

/**
 * Was ein von Hand angestoßener Abgleich bewirkt hat.
 *
 * <p>`ausgefuehrt` und `rollen` sind getrennt, weil "es ist nichts passiert"
 * zweierlei heißen kann: Der Abgleich lief und hatte nichts zu tun, oder er lief
 * gar nicht erst. Im zweiten Fall steht der Grund in `hinweis`.</p>
 */
export interface DiscordSyncErgebnis {
  characterId: number;
  characterName: string;
  mainCharacterId: number;
  mainCharacterName: string;
  discordUserId: string | null;
  ausgefuehrt: boolean;
  hinweis: string | null;
  rollen: DiscordSyncZeile[];
}

/**
 * Das Prüfergebnis für EIN Discord-Konto - Bezugsgröße ist das Konto, nicht
 * der Charakter.
 *
 * <p>Die Feldnamen stehen wortgleich so, wie das Backend sie liefert
 * (`DiscordRoleAudit`), deutsche Namen eingeschlossen. Eine Umbenennung beim
 * Empfang bräuchte eine zweite Liste, die niemand mitpflegt - und ein
 * verlesenes `pruefbar` hieße hier, dass am Server-Owner sämtliche Rollen als
 * fehlend gemeldet würden.</p>
 */
export interface DiscordRoleAudit {
  discordUserId: string;
  mainCharacterId: number;
  mainCharacterName: string;
  charaktere: DiscordCharakterSoll[];
  /** Die Gegenüberstellung Zeile für Zeile - je Auth-Rolle des Mains eine. */
  rollen: DiscordRollenBefund[];
  /** Was das Konto sonst noch trägt, auch das von Hand Vergebene. */
  weitereDiscordRollen: DiscordVorhandeneRolle[];
  /** Soll ja, Ist nein. */
  fehlendeRollen: string[];
  /** Ist ja, Soll nein - ausschließlich verwaltete Rollen. */
  ueberzaehligeRollen: string[];
  /** Ob der Ist-Zustand überhaupt gelesen werden konnte. */
  pruefbar: boolean;
  /** Warum nicht, falls nicht. */
  hinweis: string | null;
  /** Mehrere Charaktere an einem Konto mit verschiedenen Soll-Rollen. */
  sollUneinig: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class DiscordService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/discord`;

  getStatus(): Observable<{ connected: boolean }> {
    return this.http.get<{ connected: boolean }>(`${this.apiUrl}/status`);
  }

  getMappings(): Observable<DiscordMapping[]> {
    return this.http.get<DiscordMapping[]>(`${this.apiUrl}/mappings`);
  }

  /**
   * Der Soll-Ist-Vergleich je Discord-Konto. Ändert nichts.
   *
   * <p>Kostet je verknüpftem Konto einen Aufruf an Discord, deshalb wird sie
   * nicht beim Öffnen der Seite geladen, sondern auf Anforderung.</p>
   */
  getAudit(): Observable<DiscordRoleAudit[]> {
    return this.http.get<DiscordRoleAudit[]>(`${this.apiUrl}/audit`);
  }

  /**
   * Die Gegenüberstellung für genau einen Charakter. Ändert nichts.
   *
   * <p>Kostet einen Aufruf an Discord statt einen je Konto - gedacht für die
   * Rücksicht unmittelbar nach einem angestoßenen Abgleich. Dafür die ganze
   * Übersicht neu zu laden hieße, für eine Zeile jedes verknüpfte Konto erneut
   * abzufragen.</p>
   */
  getCharacterAudit(characterId: number): Observable<DiscordCharacterAudit> {
    return this.http.get<DiscordCharacterAudit>(`${this.apiUrl}/audit/characters/${characterId}`);
  }

  /**
   * Führt den Abgleich für einen Charakter sofort aus und meldet, was dabei
   * herauskam.
   *
   * <p>POST, weil der Aufruf in Discord etwas ändert - die einzige Stelle
   * dieser Seite, die das tut. Die Antwort ist der Zweck: Wer ihn anstößt, tut
   * es, weil vorher etwas nicht ging.</p>
   */
  stosseAbgleichAn(characterId: number): Observable<DiscordSyncErgebnis> {
    return this.http.post<DiscordSyncErgebnis>(`${this.apiUrl}/sync/${characterId}`, {});
  }

  saveMapping(mapping: DiscordMapping): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/mappings`, mapping);
  }

  disconnect(): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/disconnect`);
  }
}
