import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AltCalibrationDto,
  AltGroupDto,
  AltSignalConfigDto,
  AltSignalDto,
  AltSuggestionDto,
  CharacterService,
} from '../../services/character.service';
import { ConfirmService } from '../../services/confirm.service';
import { ToastService } from '../../services/toast.service';

/**
 * Ab wann eine Zahl in der Anzeige als "hoch" gilt.
 *
 * <p>Der Server liefert im Handlungsbereich ohnehin nur ab 80. Diese Grenze
 * faerbt lediglich, sie filtert nicht - die Schwelle selbst steht im Server
 * (`AltDetectionProperties.minProbability`, zur Laufzeit ueber
 * `eve.alt-detection.*` einstellbar) und darf hier nicht ein zweites Mal
 * entschieden werden.</p>
 *
 * <p>In der Kalibrieransicht wird ausdruecklich <b>nicht</b> danach gefaerbt:
 * dort gilt je Zeile die eigene `requiredThreshold`, und eine zweite,
 * hartkodierte Grenze daneben waere genau die Verwechslung, die diese Ansicht
 * aufloesen soll.</p>
 */
const HOHE_WAHRSCHEINLICHKEIT = 90;

/**
 * Die Bilanz eines Signals ueber die gelieferten Kalibrierzeilen.
 *
 * <p>`verfuegbar` von `gesamt` und nicht ein Ja/Nein: "das Signal gibt es"
 * und "das Signal lag in jeder einzelnen Zeile vor" sind verschiedene
 * Aussagen, und nur die zweite rechtfertigt es, eine Zahl zu glauben.</p>
 */
export interface SignalBilanz {
  signal: string;
  label: string;
  verfuegbar: number;
  gesamt: number;
}

/**
 * Was in der Spalte "Wahrscheinlichkeit" unter der Zahl steht.
 *
 * <p>Freie Funktion und keine Methode: so ist der Satz ohne Komponente
 * pruefbar, und er ist der eigentliche Ertrag der Spalte. Eine Zahl allein
 * verleitet zum Durchklicken - ein Director soll lesen, worauf sie beruht,
 * bevor er einen Knopf drueckt, dessen Folge er nicht zuruecknehmen kann.</p>
 *
 * <p>Der Ein-Signal-Fall ist der wichtige: 85 aus einem gleichen Nachnamen und
 * 85 aus drei uebereinstimmenden Signalen sehen in der Tabelle gleich aus und
 * sind es nicht. Ohne diesen Satz waere die Spalte irrefuehrend.</p>
 */
export function begruendungSatz(suggestion: AltSuggestionDto): string {
  const tragend = suggestion.signals.filter((signal) => signal.available);
  if (tragend.length <= 1) {
    const name = tragend[0]?.label ?? 'kein einziges Signal';
    return `Traegt nur ${name} - eine hohe Zahl aus einer einzigen Quelle ist ein Verdacht, kein Nachweis.`;
  }
  return `${suggestion.signalsUsed} von ${suggestion.signalsTotal} Signalen tragen: ${tragend
    .map((signal) => signal.label)
    .join(', ')}.`;
}

/**
 * Die Beschriftung eines Chips.
 *
 * <p>"nicht gemessen" statt einer 0: der Unterschied ist der ganze Punkt von
 * `AltSignalDto.available`. Ein Charakter ohne Mining-Zeilen hat keine
 * gepruefte Unaehnlichkeit, er hat gar keine Pruefung.</p>
 */
export function chipText(signal: AltSignalDto): string {
  return signal.available ? `${signal.label} ${signal.score}` : `${signal.label}: nicht gemessen`;
}

/**
 * Der Satz unter einer Gruppe nicht registrierter Charaktere.
 *
 * <p>Er nennt die Zahl ausdruecklich als die der <b>schwaechsten</b> Verbindung
 * und nicht als "die Wahrscheinlichkeit der Gruppe". Bei vier Mitgliedern gibt
 * es sechs Paare; stuende dort der Mittelwert, truege ein einziges sehr gutes
 * Paar drei zweifelhafte mit durch, und die Gruppe saehe belastbarer aus, als
 * ihr duennstes Glied es hergibt.</p>
 */
export function gruppenSatz(group: AltGroupDto): string {
  const tragend = group.signals.filter((signal) => signal.available);
  const quellen =
    tragend.length === 0
      ? 'kein einziges Signal'
      : tragend.map((signal) => signal.label).join(', ');
  return (
    `${group.members.length} Charaktere, ${paarZahl(group.members.length)} Paare. ` +
    `Die ${group.probability} beziehen sich auf die schwaechste Verbindung darin - jedes andere ` +
    `Paar der Gruppe liegt darueber. Dort tragen ${group.signalsUsed} von ${group.signalsTotal} ` +
    `Signalen: ${quellen}.`
  );
}

/** Wieviele Paare eine Gruppe dieser Groesse hat - n ueber 2. */
export function paarZahl(mitglieder: number): number {
  return mitglieder < 2 ? 0 : (mitglieder * (mitglieder - 1)) / 2;
}

/**
 * Wie oft jedes Signal in den gelieferten Kalibrierzeilen ueberhaupt vorlag.
 *
 * <p>Gezaehlt wird ueber <b>beide</b> Listen, Kontopaare und unregistrierte
 * Paare: die Frage "was traegt hier eigentlich" ist dieselbe, und getrennt
 * gezaehlt gaebe es zwei Zahlen fuer eine Aussage.</p>
 *
 * <p>Das ist ausdruecklich eine Aussage ueber die <em>gelieferten Zeilen</em>
 * und nicht ueber die Corporation: die Antwort ist auf `limit` gekuerzt. Die
 * Anzeige sagt das dazu - eine Hochrechnung von 20 Zeilen auf 300 Mitglieder
 * waere geraten, und Raten ist genau das, was diese Ansicht abschaffen soll.</p>
 */
export function signalBilanz(calibration: AltCalibrationDto): SignalBilanz[] {
  const bilanz = new Map<string, SignalBilanz>();
  const zaehle = (signals: AltSignalDto[]) => {
    for (const signal of signals) {
      const eintrag = bilanz.get(signal.signal) ?? {
        signal: signal.signal,
        label: signal.label,
        verfuegbar: 0,
        gesamt: 0,
      };
      eintrag.gesamt += 1;
      if (signal.available) {
        eintrag.verfuegbar += 1;
      }
      bilanz.set(signal.signal, eintrag);
    }
  };

  calibration.accountPairs.forEach((entry) => zaehle(entry.suggestion.signals));
  calibration.unregisteredPairs.forEach((pair) => zaehle(pair.signals));
  return [...bilanz.values()];
}

/**
 * In welchem Zustand ein Signal ist - und zwar in DREI Faellen, nicht zwei.
 *
 * <p>`ohne-daten` und `nichts-gerechnet` auseinanderzuhalten ist der ganze
 * Zweck: "in keinem der 549 gerechneten Paare lag es vor" ist eine Messung,
 * "es wurde ueberhaupt nichts gerechnet" ist keine. Beide zeigen eine 0, und
 * wer sie zusammenwirft, liest aus einem Ausfall eine Aussage heraus.</p>
 */
export type SignalStand = 'gerechnet' | 'ohne-daten' | 'nichts-gerechnet';

/**
 * Eine Zeile der Signal-Uebersicht: das eingestellte Gewicht und daneben die
 * Auskunft, ob das Signal ueberhaupt schon einmal Daten hatte.
 *
 * <p>`anteilProzent` ist bequem, aber nicht die Aussage - `verfuegbar` von
 * `gerechnet` bleibt daneben stehen. 100 % aus einem einzigen gerechneten Paar
 * und 100 % aus 549 sind dasselbe Prozent und nicht dieselbe Auskunft.</p>
 */
export interface SignalZeile {
  signal: string;
  label: string;
  gewicht: number;
  verfuegbar: number;
  gerechnet: number;
  anteilProzent: number;
  stand: SignalStand;
  auskunft: string;
}

/**
 * Der Satz, der ueber der Signal-Uebersicht stehen MUSS - sichtbar, nicht als
 * Kommentar.
 *
 * <p>Er ist der eigentliche Ertrag der Ansicht. Wer ein Gewicht verstellt und
 * danach dieselbe Liste wiedersieht, kann daraus zweierlei lesen: das Gewicht
 * wirkt nicht, oder das Signal hatte in keinem einzigen Paar Daten. Ohne diesen
 * Hinweis sucht der Nutzer den Fehler bei sich, obwohl schlicht noch nichts
 * erfasst ist - und das ist, solange die Erfassung erst Tage laeuft, der
 * Regelfall und nicht die Ausnahme.</p>
 */
export const SIGNAL_UEBERSICHT_HINWEIS =
  'Die Spalte "Daten in Paaren" ist die wichtigste. Wer ein Gewicht verstellt und danach ' +
  'dieselbe Liste wiedersieht, kann daraus zweierlei lesen: das Gewicht wirkt nicht, oder das ' +
  'Signal hatte in keinem einzigen Paar Daten. Solange die Erfassung erst Tage laeuft, ist der ' +
  'zweite Fall der Regelfall. Ein Signal mit 0 von n Paaren ist deshalb nicht schwach, sondern ' +
  'stumm - ein anderes Gewicht aendert daran nichts.';

/**
 * Was fuer die Erkennung erfasst wird und wie lange es bleibt.
 *
 * <p>Steht hier und nicht nur in der Vorlage, damit die Zusage zu Nachrichten
 * pruefbar ist. Sie ist woertlich das, was der Code haelt: `CharacterMailCount`
 * hat kein Textfeld und keine Mail-ID, und `EsiService.EsiMailHeaderResponse`
 * liest nur Absender, Empfaenger und Zeitpunkt ein - der Betreff existiert im
 * Prozess nie als Wert. Die Oberflaeche darf deshalb nirgends den Eindruck
 * erwecken, Inhalte seien verfuegbar.</p>
 *
 * <p>Die Fristen sind ebenfalls nachgeschlagen und nicht geschaetzt:
 * `eve.alt-sources.presence-retention` und `...isk-transfer-retention` stehen
 * auf 90 Tagen, und `AltSourceRetentionScheduler` loescht taeglich. Kontakte
 * und Nachrichtenanzahlen haben keine Frist, weil jeder Lauf sie vollstaendig
 * ersetzt (`AltSourceStore.replaceContacts` / `replaceMailCounts`) - eine
 * Aufbewahrungsfrist hinzuschreiben, die es nicht gibt, waere die schlimmere
 * Ungenauigkeit.</p>
 */
export const ERFASSUNG_SAETZE: string[] = [
  'Fuer diese Erkennung wird zu registrierten Charakteren erfasst: ISK-Ueberweisungen zwischen ' +
    'Charakteren, die eigene Kontaktliste, die Anzahl gewechselter Nachrichten je Gegenueber und ' +
    'in welchem System Corp-Mitglieder gesehen wurden.',
  'Von Nachrichten wird ausschliesslich gezaehlt. Betreff und Text werden weder gespeichert noch ' +
    'eingelesen, und es wird auch keine Mail-ID abgelegt - es gibt damit keinen Schluessel, mit ' +
    'dem sich ein Inhalt spaeter nachladen liesse.',
  // Bewusst OHNE Zahl: die Fristen stehen in eve.alt-sources.* und lassen sich
  // ueber die .env aendern, ohne dass jemand diese Datei anfasst. Eine hier
  // eingetragene "90" wuerde beim ersten Verstellen zur Falschaussage - und
  // zwar in genau dem Kasten, dessen Zweck es ist, verlaesslich zu sein.
  // Ebenfalls bewusst nicht mehr behauptet: dass jeder Lauf die Kontakte und
  // Nachrichtenanzahlen vollstaendig ersetze. Das Ersetzen geschieht je
  // Charakter und nur, wenn er im Lauf vorkommt; wer sein Token entzieht oder
  // fuer wen die Quelle abgeschaltet ist, faellt heraus. Deshalb haben jetzt
  // ALLE vier Quellen eine Frist und einen taeglichen Loeschlauf.
  'Alle vier Quellen unterliegen einer Aufbewahrungsfrist: was aelter ist, wird taeglich ' +
    'geloescht. Das gilt auch fuer Charaktere, die inzwischen aus der Erfassung herausgefallen ' +
    'sind - ihre Daten bleiben nicht liegen.',
];

/**
 * Die Signal-Uebersicht aus der Antwort des Servers.
 *
 * <p>Die Zahlen kommen unveraendert aus `signalConfig` und werden hier
 * ausdruecklich NICHT aus den gelieferten Zeilen nachgerechnet: die Lieferung
 * ist auf `limit` gekuerzt und nach Wert sortiert, also gerade nicht
 * repraesentativ dafuer, wo Daten lagen. `signalBilanz` daneben zaehlt bewusst
 * etwas anderes - die gezeigten Zeilen - und sagt das im Leerzustand auch
 * dazu.</p>
 */
export function signalUebersicht(calibration: AltCalibrationDto): SignalZeile[] {
  return (calibration.signalConfig ?? []).map((config) => zeileFuer(config));
}

/** Eine Zeile samt ihrer Auskunft - freie Funktion, damit der Satz pruefbar ist. */
export function zeileFuer(config: AltSignalConfigDto): SignalZeile {
  const stand: SignalStand =
    config.examinedPairs === 0
      ? 'nichts-gerechnet'
      : config.availableInPairs === 0
        ? 'ohne-daten'
        : 'gerechnet';

  return {
    signal: config.signal,
    label: config.label,
    gewicht: config.weightPercent,
    verfuegbar: config.availableInPairs,
    gerechnet: config.examinedPairs,
    anteilProzent:
      config.examinedPairs === 0
        ? 0
        : Math.round((config.availableInPairs / config.examinedPairs) * 100),
    stand,
    auskunft: auskunftFuer(config, stand),
  };
}

/**
 * Was in der letzten Spalte steht.
 *
 * <p>Im Fall `ohne-daten` steht dort ausdruecklich "noch keine Daten" und
 * nirgends "schwach": ein stummes Signal ist kein niedriger Wert, sondern gar
 * keiner. Wer das verwechselt, dreht am Gewicht statt an der Erfassung.</p>
 */
function auskunftFuer(config: AltSignalConfigDto, stand: SignalStand): string {
  if (stand === 'nichts-gerechnet') {
    return (
      'Es wurde kein einziges Paar gerechnet - ueber dieses Signal ist damit nichts gesagt, ' +
      'weder gut noch schlecht.'
    );
  }
  if (stand === 'ohne-daten') {
    return (
      `Noch keine Daten: in keinem der ${config.examinedPairs} gerechneten Paare lag dieses ` +
      `Signal vor. Das Gewicht ${config.weightPercent} kann derzeit nichts bewirken - das ist ` +
      'kein schwaches Signal, sondern ein stummes.'
    );
  }
  return `Lag in ${config.availableInPairs} von ${config.examinedPairs} gerechneten Paaren vor.`;
}

/**
 * Welche Signale schon Daten liefern und welche noch stumm sind.
 *
 * <p>Gehoert in den Leerzustand, weil dort die falsche Erklaerung droht: eine
 * leere Vorschlagsliste sieht nach vier Wochen Erfassung wie ein Befund aus und
 * nach drei Tagen wie ein Fehler - es ist beide Male dieselbe leere Liste. Nur
 * diese Aufzaehlung sagt, welcher der beiden Faelle vorliegt.</p>
 */
export function signalStandSaetze(calibration: AltCalibrationDto): string[] {
  const zeilen = signalUebersicht(calibration);
  if (zeilen.length === 0) {
    return [];
  }

  const gerechnet = zeilen[0].gerechnet;
  if (gerechnet === 0) {
    // Ohne gerechnete Paare traegt jede Zahl der Uebersicht eine 0, und keine
    // davon bedeutet etwas. Ein "noch keine Daten" waere hier eine erfundene
    // Messung - der Satz oben hat den Ausfall bereits benannt.
    return [];
  }

  const saetze: string[] = [];
  const mitDaten = zeilen.filter((zeile) => zeile.stand === 'gerechnet');
  const stumm = zeilen.filter((zeile) => zeile.stand === 'ohne-daten');

  saetze.push(
    mitDaten.length === 0
      ? `Kein einziges der ${zeilen.length} Signale hatte in den ${gerechnet} gerechneten Paaren ` +
        'Daten. Die Erkennung rechnet dann zwar, hat aber nichts, womit sie rechnen koennte.'
      : `Daten geliefert haben bisher: ` +
        mitDaten
          .map((zeile) => `${zeile.label} in ${zeile.verfuegbar} von ${gerechnet} Paaren`)
          .join(', ') +
        '.',
  );

  if (stumm.length > 0) {
    saetze.push(
      `Noch keine Daten hat: ${stumm.map((zeile) => zeile.label).join(', ')}. Diese Signale sind ` +
        'nicht schwach, sondern stumm - solange die Erfassung erst wenige Tage laeuft, koennen ' +
        'sie nichts beitragen, auch mit einem hoeheren Gewicht nicht.',
    );
  }

  return saetze;
}

/**
 * Die Saetze, die im Leerzustand stehen muessen.
 *
 * <p>Der Leerzustand ist hier der <b>Regelfall</b> und nicht die Ausnahme. Vor
 * einer leeren Liste kann niemand unterscheiden, ob nichts gefunden wurde oder
 * ob nichts gerechnet hat - und genau diese Frage stellt der Nutzer. Deshalb
 * stehen hier Zahlen und kein Trostsatz.</p>
 *
 * <p><b>Jede Zahl stammt aus der Antwort des Servers.</b> Keine wird hier
 * abgeleitet. Insbesondere wird aus `examinedUnregisteredPairs` <em>nicht</em>
 * auf die Zahl der geprueften Charaktere zurueckgerechnet: die Paarzahl ist
 * ueber alle betreuten Corporationen aufsummiert, die Umkehrung von n ueber 2
 * gaebe deshalb eine erfundene Zahl, die aussaehe wie eine gemessene.</p>
 */
export function leerzustandSaetze(calibration: AltCalibrationDto): string[] {
  const saetze: string[] = [];
  const gerechnet = calibration.examinedAccountPairs + calibration.examinedUnregisteredPairs;

  if (gerechnet === 0) {
    saetze.push(
      'Es wurde kein einziges Paar gerechnet. Das ist kein Fund, sondern ein Ausfall: entweder ' +
        'hat die Corporation keine nicht registrierten Mitglieder, oder die Mitgliederliste kam ' +
        'nicht von ESI zurueck.',
    );
  } else {
    saetze.push(
      `Gerechnet wurden ${calibration.examinedAccountPairs} Paare "unregistriert gegen Konto" ` +
        `und ${calibration.examinedUnregisteredPairs} Paare "unregistriert gegen unregistriert". ` +
        'Die Erkennung laeuft also - sie hat nur nichts ueber der Schwelle gefunden.',
    );
  }

  saetze.push(
    `Die Schwelle liegt bei ${calibration.minProbability} Punkten. Traegt nur ein einziges ` +
      `Signal, sind es ${calibration.minProbabilitySingleSignal}; unter ` +
      `${calibration.minAvailableSignals} vorliegenden Signalen wird ein Paar gar nicht erst ` +
      'bewertet.',
  );

  // Welche Signale ueberhaupt schon Daten liefern - und zwar VOR der Bilanz
  // ueber die gezeigten Zeilen. "Keine Vorschlaege" heisst bei frischer
  // Erfassung etwas voellig anderes als nach vier Wochen: dort ist die Liste
  // leer, weil die Haelfte der Signale noch stumm ist, hier waere sie ein
  // Befund. Ohne diesen Unterschied liest der Nutzer aus demselben Bildschirm
  // zwei gegensaetzliche Aussagen heraus.
  saetze.push(...signalStandSaetze(calibration));

  const bilanz = signalBilanz(calibration);
  if (bilanz.length > 0) {
    const zeilen = calibration.accountPairs.length + calibration.unregisteredPairs.length;
    saetze.push(
      `In den ${zeilen} unten gezeigten Zeilen lag vor: ` +
        bilanz
          .map((eintrag) => `${eintrag.label} ${eintrag.verfuegbar} von ${eintrag.gesamt} mal`)
          .join(', ') +
        '. Das zaehlt die gezeigten Zeilen und nicht die ganze Corporation - die Antwort ist auf ' +
        `${calibration.limit} Zeilen je Liste gekuerzt.`,
    );

    const stumm = bilanz.filter((eintrag) => eintrag.verfuegbar === 0);
    if (stumm.length > 0) {
      saetze.push(
        `Kein einziges Mal vorgelegen hat: ${stumm.map((e) => e.label).join(', ')}. Ohne ` +
          'Director-Token gibt es keine Beitrittsdaten, dann traegt allein der Name - und ein Name ' +
          'allein muss die hoehere Ein-Signal-Schwelle nehmen.',
      );
    }
  }

  return saetze;
}

/**
 * Die Vorschlaege fuer die Alt-Erkennung, die beobachteten Gruppen und die
 * Kalibrieransicht.
 *
 * <h2>Drei Bereiche, und warum sie sichtbar getrennt sind</h2>
 * <ol>
 *   <li><b>Vorschlaege</b> - der einzige Bereich mit einer Schaltflaeche. Hier
 *       entsteht eine Vormerkung, die sich ueber diese Oberflaeche nicht
 *       zuruecknehmen laesst.</li>
 *   <li><b>Gruppen</b> - eine <em>Beobachtung</em>. Es gibt kein Konto, dem sich
 *       eine solche Gruppe zuordnen liesse, der Server hat dazu bewusst keinen
 *       Bestaetigungs-Endpunkt, und deshalb steht hier auch keine Schaltflaeche.
 *       Eine, die nichts bewirkt, suggerierte, das Programm habe die Sache
 *       erledigt - es hat nur etwas bemerkt.</li>
 *   <li><b>Kalibrierung</b> - Zahlen unterhalb der Schwelle. Hier wird nichts
 *       bestaetigt. Sie steht hinter einem Trenner und in eigener Sprache,
 *       damit niemand eine Kalibrierzeile fuer einen Vorschlag haelt: die
 *       Zeilen sehen einander sonst zum Verwechseln aehnlich, tragen aber
 *       gegenteilige Bedeutung - die eine ist ueber der Schwelle, die andere
 *       ausdruecklich darunter.</li>
 * </ol>
 *
 * <p>Zu den Klassen: `.surface-panel` ist die einzige der Klassen, die wirklich
 * global in `styles.scss` steht. Die uebrigen Bausteine (`.page-head`,
 * `.hinweis`, `.eve-table`, `.chip`, `.btn-primary`, `.btn-secondary`,
 * `.section-divider`) sind im Projekt durchweg komponenten-lokal und stehen
 * deshalb woertlich aus `academy.component.scss` uebernommen in der eigenen
 * `.scss` - nicht neu erfunden.</p>
 */
@Component({
  selector: 'app-alt-suggestions',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './alt-suggestions.component.html',
  styleUrls: ['./alt-suggestions.component.scss'],
})
export class AltSuggestionsComponent implements OnInit {
  private characterService = inject(CharacterService);
  private confirmService = inject(ConfirmService);
  private toastService = inject(ToastService);

  readonly hoheWahrscheinlichkeit = HOHE_WAHRSCHEINLICHKEIT;

  /** Der Satz ueber der Signal-Uebersicht - sichtbar, nicht als Kommentar. */
  readonly signalHinweis = SIGNAL_UEBERSICHT_HINWEIS;

  /** Was erfasst wird und wie lange es bleibt. */
  readonly erfassung = ERFASSUNG_SAETZE;

  suggestions = signal<AltSuggestionDto[]>([]);
  loading = signal<boolean>(true);

  groups = signal<AltGroupDto[]>([]);
  groupsLoading = signal<boolean>(true);

  calibration = signal<AltCalibrationDto | null>(null);
  calibrationLoading = signal<boolean>(false);

  /**
   * Warum die Kalibrierung fehlt - `null` heisst: sie fehlt nicht.
   *
   * <p>Eigenes Feld statt eines Toasts allein: die Zahlen des Leerzustands
   * haengen daran. Faellt der Abruf aus und stuende dort nichts, saehe der
   * Leerzustand wie ein sauberes "nichts gefunden" aus, obwohl gar nicht
   * gemessen wurde - genau die Verwechslung, gegen die diese Ansicht gebaut
   * ist.</p>
   */
  calibrationFehler = signal<string | null>(null);

  /**
   * Warum die Listen leer sind, wenn es an einem Ausfall lag.
   *
   * <p>Ein fehlgeschlagener Abruf hinterlaesst dieselbe leere Liste wie ein
   * sauberes "nichts gefunden". Ohne dieses Feld stuende im Leerzustand "die
   * Erkennung laeuft also" ueber einem Abruf, der nie angekommen ist - der
   * Toast ist da laengst weggeblendet.</p>
   */
  ladefehler = signal<string | null>(null);

  /**
   * Der Charakter, dessen Bestaetigung gerade laeuft - `null` heisst: keine.
   *
   * <p>Eine ID statt eines Ja/Nein: sperrte ein Kennzeichen alle Knoepfe, saehe
   * der Nutzer nicht, welche Zeile gerade arbeitet. Dasselbe Muster wie
   * `pendingRole` in der Rollenverwaltung.</p>
   */
  pending = signal<number | null>(null);

  /** Ob ueberhaupt etwas anzuzeigen ist - trennt Leerzustand von Ladezustand. */
  isEmpty = computed(() => !this.loading() && this.suggestions().length === 0);

  /** Dasselbe fuer die Gruppen; sie laden unabhaengig und koennen einzeln leer sein. */
  groupsEmpty = computed(() => !this.groupsLoading() && this.groups().length === 0);

  /**
   * Beide Listen fertig und beide leer - der Regelfall.
   *
   * <p>Erst dieser Zustand loest den Abruf der Kalibrierung aus. Nicht frueher:
   * die Kalibrierung rechnet alle Paare beider Richtungen und fragt dabei die
   * Mitgliederlisten erneut bei ESI ab. Wer etwas gefunden hat, braucht diese
   * Rechnung nicht - er sieht ja, dass es laeuft.</p>
   */
  nichtsGefunden = computed(() => this.isEmpty() && this.groupsEmpty());

  /**
   * Die Signal-Uebersicht - leer, solange die Kalibrierung nicht geladen ist.
   *
   * <p>Sie haengt bewusst an derselben Antwort wie alles andere in diesem
   * Bereich: eine eigene Ladequelle koennte gefuellt sein, waehrend die Zeilen
   * daneben fehlen, und dann stuenden Gewichte ueber Zahlen, die zu einem
   * anderen Lauf gehoeren.</p>
   */
  signalZeilen = computed(() => {
    const calibration = this.calibration();
    return calibration === null ? [] : signalUebersicht(calibration);
  });

  /** Die Saetze des Leerzustands - leer, solange die Zahlen fehlen. */
  leerzustand = computed(() => {
    const calibration = this.calibration();
    return calibration === null ? [] : leerzustandSaetze(calibration);
  });

  ngOnInit() {
    this.load();
    this.loadGroups();
  }

  /**
   * Holt die Vorschlaege.
   *
   * <p>Ein Fehler leert die Liste. Sonst stuende nach einem fehlgeschlagenen
   * Neuladen der alte Stand da, und der naechste Klick bestaetigte einen
   * Vorschlag, den es vielleicht gar nicht mehr gibt.</p>
   */
  private load() {
    this.loading.set(true);
    this.characterService.getAltSuggestions().subscribe({
      next: (suggestions) => {
        this.suggestions.set(suggestions);
        this.loading.set(false);
        this.ladefehler.set(null);
        this.zahlenNachladen();
      },
      error: (error: unknown) => {
        const message = this.messageOf(error, 'Die Alt-Vorschlaege konnten nicht geladen werden.');
        this.suggestions.set([]);
        this.loading.set(false);
        this.ladefehler.set(message);
        this.toastService.error(message);
        this.zahlenNachladen();
      },
    });
  }

  /**
   * Holt die Gruppen nicht registrierter Charaktere.
   *
   * <p>Eigener Abruf und nicht mit den Vorschlaegen zusammengelegt: die beiden
   * Endpunkte koennen einzeln ausfallen, und ein gemeinsamer Ladezustand
   * verschwiege, welcher von beiden es war. Ein Fehler leert hier nur die
   * Gruppen - die Vorschlagsliste daneben bleibt bedienbar.</p>
   */
  private loadGroups() {
    this.groupsLoading.set(true);
    this.characterService.getAltGroups().subscribe({
      next: (groups) => {
        this.groups.set(groups);
        this.groupsLoading.set(false);
        this.zahlenNachladen();
      },
      error: (error: unknown) => {
        const message = this.messageOf(error, 'Die Gruppen konnten nicht geladen werden.');
        this.groups.set([]);
        this.groupsLoading.set(false);
        this.ladefehler.set(message);
        this.toastService.error(message);
        this.zahlenNachladen();
      },
    });
  }

  /**
   * Holt die Kalibrierzahlen, sobald feststeht, dass beide Listen leer sind.
   *
   * <p>Ohne sie stuende der Nutzer vor einer leeren Seite und wuesste nicht, ob
   * nichts gefunden wurde oder nichts laeuft. Sie werden hier <b>nicht</b>
   * angefordert, wenn schon etwas dasteht: dann ist die Frage beantwortet, und
   * die Rechnung ist teuer.</p>
   */
  private zahlenNachladen() {
    if (!this.nichtsGefunden() || this.calibration() !== null || this.calibrationLoading()) {
      return;
    }
    this.ladeKalibrierung();
  }

  /**
   * Laedt die Kalibrierung - vom Leerzustand ausgeloest oder auf Knopfdruck.
   *
   * <p>Der Abruf ist rein lesend. Er bestaetigt nichts, merkt nichts vor und
   * traegt deshalb auch keine Rueckfrage: es gibt nichts, was der Nutzer
   * bereuen koennte.</p>
   */
  ladeKalibrierung() {
    if (this.calibrationLoading()) {
      return;
    }
    this.calibrationLoading.set(true);
    this.calibrationFehler.set(null);
    this.characterService.getAltCalibration().subscribe({
      next: (calibration) => {
        this.calibration.set(calibration);
        this.calibrationLoading.set(false);
      },
      error: (error: unknown) => {
        const message = this.messageOf(
          error,
          'Die Kalibrierzahlen konnten nicht geladen werden.',
        );
        this.calibration.set(null);
        this.calibrationFehler.set(message);
        this.calibrationLoading.set(false);
        this.toastService.error(message);
      },
    });
  }

  begruendung(suggestion: AltSuggestionDto): string {
    return begruendungSatz(suggestion);
  }

  chipBeschriftung(signal: AltSignalDto): string {
    return chipText(signal);
  }

  gruppenBeschreibung(group: AltGroupDto): string {
    return gruppenSatz(group);
  }

  /** Ein stabiler Schluessel je Gruppe - der Server liefert keine ID dafuer. */
  gruppenSchluessel(group: AltGroupDto): string {
    return group.members.map((member) => member.id).join('-');
  }

  isPending(suggestion: AltSuggestionDto): boolean {
    return this.pending() === suggestion.unauthedCharId;
  }

  /**
   * Der Text der Rueckfrage.
   *
   * <p>Er nennt beide Namen und sagt, was der Klick wirklich tut. Der Server
   * schreibt <b>nicht</b> nach `characters.main_character_id`, sondern legt eine
   * Vormerkung an, die er nie stillschweigend ersetzt - ueber diese Oberflaeche
   * gibt es also keinen Weg zurueck. Eine Rueckfrage, die das verschweigt, ist
   * eine Formalitaet und keine Sicherung: sie liesse den Director glauben, der
   * Charakter haenge danach am Konto, und ein Irrtum bliebe unbemerkt im
   * Nachweis stehen.</p>
   */
  rueckfrageText(suggestion: AltSuggestionDto): string {
    return (
      `Moechtest du ${suggestion.unauthedCharName} wirklich ${suggestion.mainName} zuordnen? ` +
      `${suggestion.probability} % aus ${suggestion.signalsUsed} von ${suggestion.signalsTotal} Signalen. ` +
      `Der Klick merkt die Zuordnung nur vor: ${suggestion.unauthedCharName} haengt danach noch NICHT ` +
      `an dem Konto - das geschieht erst, wenn der Charakter sich selbst per EVE-Login als Alt anmeldet. ` +
      `Die Vormerkung selbst laesst sich hier nicht zuruecknehmen und steht mit deinem Namen im Nachweis.`
    );
  }

  /**
   * Bestaetigt den Vorschlag - nach Rueckfrage.
   *
   * <p>Gemeldet wird die `message` des Servers und nicht ein eigener Erfolgssatz:
   * nur der Server weiss, ob tatsaechlich zugeordnet oder nur vorgemerkt wurde,
   * und er sagt es im Klartext samt naechstem Schritt.</p>
   */
  async verknuepfen(suggestion: AltSuggestionDto) {
    if (this.pending()) {
      return;
    }

    const confirmed = await this.confirmService.ask(
      'Alt verknuepfen?',
      this.rueckfrageText(suggestion),
      'Verknuepfen',
      'Abbrechen',
    );
    if (!confirmed) {
      return;
    }

    this.pending.set(suggestion.unauthedCharId);
    this.characterService
      .confirmAltSuggestion(suggestion.unauthedCharId, suggestion.mainId)
      .subscribe({
        next: (result) => {
          this.pending.set(null);
          this.toastService.success(result.message);
          // Neu laden statt die Zeile zu entfernen: der Server rechnet die
          // Vorschlaege bei jedem Aufruf neu, und mit dem bestaetigten Paar
          // faellt oft auch ein konkurrierender Vorschlag desselben Charakters weg.
          this.load();
          // Die Gruppen mit: eine Vormerkung nimmt den Charakter aus den
          // Kandidaten, damit zerfaellt womoeglich eine Gruppe, die daneben
          // sonst weiter mit ihm dastuende.
          this.loadGroups();
        },
        error: (error: unknown) => {
          this.pending.set(null);
          this.toastService.error(
            this.messageOf(error, 'Der Vorschlag konnte nicht bestaetigt werden.'),
          );
        },
      });
  }

  /**
   * Die Begruendung des Servers, sonst der Ersatztext.
   *
   * <p>Hier steht das Entscheidende - etwa dass der Charakter laengst
   * registriert ist oder schon jemand anderes ihn vorgemerkt hat. Ein pauschales
   * "hat nicht geklappt" liesse den Nutzer vor einer Schaltflaeche zurueck, die
   * scheinbar grundlos nichts tut.</p>
   */
  private messageOf(error: unknown, fallback: string): string {
    const body = (error as HttpErrorResponse | null)?.error as { message?: string } | null;
    const message = typeof body?.message === 'string' ? body.message.trim() : '';
    return message || fallback;
  }
}
