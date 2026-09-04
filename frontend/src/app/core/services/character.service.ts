import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Ein Charakter eines Accounts.
 *
 * <p>Ersetzt die frueheren, feldgleichen Typen `AltDto` und `AuthedAltDto` -
 * zwei Namen fuer dieselbe Antwort. Spiegelt `CharacterDtos.CharacterRefDto`
 * im Backend.</p>
 */
export interface CharacterRefDto {
  id: number;
  name: string;
  portraitUrl: string;
  isMain: boolean;
}

export interface AuthedMainDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  alts: CharacterRefDto[];
}

export interface UnauthedCharDto {
  id: number;
  name: string;
  portraitUrl: string;
}

export interface CorpStatsDto {
  corpId: number;
  corpName: string;
  totalEsiMembers: number;
  registeredMains: number;
  registeredAlts: number;
  totalRegisteredChars: number;
  authedMembers: AuthedMainDto[];     
  unauthedMembers: UnauthedCharDto[]; 
}

export interface AdminAccountCharDto {
  id: number;
  name: string;
  portraitUrl: string;
  corporationName: string;
}

export interface AdminAccountDto {
  mainId: number;
  mainName: string;
  portraitUrl: string;
  corporationName: string;
  alts: AdminAccountCharDto[];
}

/**
 * Ein einzelnes Signal eines Alt-Vorschlags. Spiegelt `CharacterDtos.AltSignalDto`.
 *
 * `available === false` heisst "nicht gemessen" und niemals "gemessen und
 * nichts gefunden" - deshalb ist `score` dort `null` und nicht 0. Die
 * Oberflaeche muss beide Faelle verschieden anzeigen: eine 0 sieht aus wie ein
 * Freispruch, obwohl gar nichts geprueft wurde.
 */
export interface AltSignalDto {
  signal: string;
  label: string;
  available: boolean;
  score: number | null;
  weightPercent: number;
  detail: string;
}

/**
 * Ein Verdacht: dieser nicht registrierte Charakter koennte zu diesem Konto
 * gehoeren. Spiegelt `CharacterDtos.AltSuggestionDto`.
 *
 * `probability` ist eine gewichtete Summe von Heuristiken und keine geeichte
 * Wahrscheinlichkeit. Die Aufschluesselung wandert deshalb mit: eine 94 aus
 * drei Signalen und eine 94 aus einem einzigen sind voellig verschiedene
 * Aussagen.
 */
export interface AltSuggestionDto {
  unauthedCharId: number;
  unauthedCharName: string;
  mainId: number;
  mainName: string;
  probability: number;
  signalsUsed: number;
  signalsTotal: number;
  signals: AltSignalDto[];
  corpId: number;
}

/**
 * Das Ergebnis einer bestaetigten Zuordnung. Spiegelt `CharacterDtos.AltLinkResultDto`.
 *
 * `linked` ist beim heutigen Server immer `false`: bestaetigt wird eine
 * Vormerkung, nicht die Zuordnung. `message` traegt den Klartext samt dem
 * naechsten Schritt - er wird angezeigt und nicht durch einen eigenen Satz
 * ersetzt, sonst verspraeche die Oberflaeche mehr, als geschehen ist.
 */
export interface AltLinkResultDto {
  unauthedCharId: number;
  unauthedCharName: string;
  mainId: number;
  mainName: string;
  probability: number;
  linked: boolean;
  message: string;
}

/**
 * Eine Gruppe nicht registrierter Charaktere, die vermutlich EIN Mensch sind.
 * Spiegelt `CharacterDtos.AltGroupDto`.
 *
 * Es gehoert kein bekanntes Konto dazu. Deshalb gibt es zu diesem Typ keinen
 * Bestaetigungsaufruf im Dienst - und darf auch keiner dazukommen: es gibt
 * niemanden, dem sich die Gruppe zuordnen liesse. `note` traegt den Klartext
 * des Servers, was der naechste Schritt ist; er liegt ausserhalb dieses
 * Programms.
 *
 * `probability` ist der Wert der SCHWAECHSTEN Verbindung in der Gruppe und
 * nicht der Mittelwert - eine Gruppe ist nur so belastbar wie ihr duennstes
 * Paar. `signals` schluesselt eben diese schwaechste Verbindung auf.
 */
export interface AltGroupDto {
  corpId: number;
  members: UnauthedCharDto[];
  probability: number;
  signalsUsed: number;
  signalsTotal: number;
  signals: AltSignalDto[];
  note: string;
}

/**
 * Ein bewertetes Paar zweier nicht registrierter Charaktere - auch UNTERHALB
 * der Schwelle. Spiegelt `CharacterDtos.AltPairDto`.
 *
 * `requiredThreshold` haengt an der Zahl tragender Signale und ist deshalb
 * nicht fuer alle Zeilen dieselbe. Ohne dieses Feld waere `aboveThreshold`
 * nicht nachvollziehbar, und die Kalibrierung liefe auf "glaub es mir" hinaus.
 */
export interface AltPairDto {
  leftId: number;
  leftName: string;
  rightId: number;
  rightName: string;
  corpId: number;
  probability: number;
  signalsUsed: number;
  signalsTotal: number;
  signals: AltSignalDto[];
  requiredThreshold: number;
  aboveThreshold: boolean;
}

/** Ein Kontopaar der Kalibrierung samt der Schwelle, die fuer genau dieses gilt. */
export interface AltCalibrationEntryDto {
  suggestion: AltSuggestionDto;
  requiredThreshold: number;
  aboveThreshold: boolean;
}

/**
 * Ein Signal mit seinem Gewicht und der Auskunft, wie oft es ueberhaupt Daten
 * hatte. Spiegelt `CharacterDtos.AltSignalConfigDto`.
 *
 * `availableInPairs` von `examinedPairs` ist der Grund, warum es diesen Typ
 * gibt. Wer ein Gewicht verstellt und danach dieselbe Liste wiedersieht, hat
 * zwei ununterscheidbare Ursachen vor sich: das Gewicht wirkt nicht, oder das
 * Signal hatte in keinem einzigen Paar Daten. Nur diese Zahl trennt sie - und
 * solange die Erfassung erst Tage laeuft, ist der zweite Fall der Regelfall.
 *
 * Die Zahlen zaehlen ALLE gerechneten Paare und nicht die gelieferten Zeilen:
 * die Lieferung ist gekuerzt und nach Wert sortiert, also gerade nicht
 * repraesentativ dafuer, wo Daten lagen.
 */
export interface AltSignalConfigDto {
  signal: string;
  label: string;
  weightPercent: number;
  availableInPairs: number;
  examinedPairs: number;
}

/**
 * Was der Scorer denkt, BEVOR die Schwelle filtert.
 * Spiegelt `CharacterDtos.AltCalibrationDto`.
 *
 * Der Zweck ist genau eine Unterscheidung: eine leere Vorschlagsliste sagt
 * nicht, ob nichts gefunden wurde oder ob nichts gerechnet hat.
 * `examinedAccountPairs` und `examinedUnregisteredPairs` trennen die beiden
 * Faelle - eine 0 dort heisst "nichts gerechnet", jede andere Zahl heisst
 * "gerechnet und nichts ueber der Schwelle".
 *
 * Hier wird nichts bestaetigt. Kein Feld dieser Antwort fuehrt zu einer
 * Zuordnung oder einer Vormerkung, und der Dienst hat dazu bewusst keinen
 * Gegenstueck-Aufruf.
 */
export interface AltCalibrationDto {
  limit: number;
  maxLimit: number;
  examinedAccountPairs: number;
  examinedUnregisteredPairs: number;
  minProbability: number;
  minProbabilitySingleSignal: number;
  minAvailableSignals: number;
  signalConfig: AltSignalConfigDto[];
  accountPairs: AltCalibrationEntryDto[];
  unregisteredPairs: AltPairDto[];
}

@Injectable({ providedIn: 'root' })
export class CharacterService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/characters`;

  getMyAlts(): Observable<CharacterRefDto[]> {
    return this.http.get<CharacterRefDto[]>(`${this.apiUrl}/alts`);
  }

  getCorpStats(): Observable<CorpStatsDto[]> {
    return this.http.get<CorpStatsDto[]>(`${this.apiUrl}/corp-stats`);
  }

  setMainCharacter(characterId: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/set-main/${characterId}`, {});
  }

  getAllAccounts(): Observable<AdminAccountDto[]> {
    return this.http.get<AdminAccountDto[]>(`${this.apiUrl}/admin/accounts`);
  }

  /** Nicht registrierte Corp-Charaktere samt vermutetem Konto, ab 80 Punkten. */
  getAltSuggestions(): Observable<AltSuggestionDto[]> {
    return this.http.get<AltSuggestionDto[]>(`${this.apiUrl}/alt-suggestions`);
  }

  /**
   * Bestaetigt einen Vorschlag - als Vormerkung, nicht als Zuordnung.
   *
   * Der Server schreibt nichts nach `characters.main_character_id`; er haelt
   * fest, dass die Fuehrung den Verdacht fuer richtig haelt. Der Rueckgabewert
   * sagt in `linked` und `message`, was tatsaechlich geschehen ist.
   */
  confirmAltSuggestion(unauthedCharId: number, mainId: number): Observable<AltLinkResultDto> {
    return this.http.post<AltLinkResultDto>(`${this.apiUrl}/alt-suggestions/confirm`, {
      unauthedCharId,
      mainId,
    });
  }

  /**
   * Gruppen nicht registrierter Charaktere, die vermutlich ein Mensch sind.
   *
   * Nur lesen. Es gibt dazu ausdruecklich keinen Bestaetigungsaufruf: zu einer
   * solchen Gruppe gehoert kein Konto, dem man sie zuordnen koennte.
   */
  getAltGroups(): Observable<AltGroupDto[]> {
    return this.http.get<AltGroupDto[]>(`${this.apiUrl}/alt-groups`);
  }

  /**
   * Die Kalibrieransicht: die besten Paare OHNE Schwellenfilter.
   *
   * `limit` wandert nur mit, wenn es gesetzt ist - sonst entschiede die
   * Oberflaeche still ueber die Vorgabe, die im Server steht
   * (`eve.alt-detection.calibration-default-limit`). Der Server kappt den Wert
   * ohnehin auf seine Obergrenze; hier wird er deshalb nicht ein zweites Mal
   * begrenzt, sonst gaebe es zwei Wahrheiten.
   */
  getAltCalibration(limit?: number): Observable<AltCalibrationDto> {
    const options = limit === undefined ? {} : { params: { limit: String(limit) } };
    return this.http.get<AltCalibrationDto>(
      `${this.apiUrl}/alt-suggestions/calibration`,
      options,
    );
  }
}
