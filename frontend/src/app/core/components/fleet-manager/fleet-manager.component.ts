import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { computed } from '@angular/core';

import { FleetService, FleetEvent, FleetAttendance } from '../../services/fleet.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import {
  FleetStatisticsService,
  FatStatistik,
  MeineFat,
  AccountZeile,
  ZEITRAEUME,
  ZEITRAUM_VORGABE,
  ZeitraumTage
} from '../../services/fleet-statistics.service';
import { portrait } from '../../shared/eve-image.util';
import { copyText } from '../../shared/clipboard.util';

/**
 * Die zwei Reiter dieser Seite.
 *
 * <p>Gespeicherte Doktrinen, Readiness Board und Sandbox standen einmal
 * daneben. Sie sind nach "Fittings und Doktrinen" gezogen: Sie beantworten
 * "was fliegen wir und wer kann es" und nicht "wer war dabei".</p>
 */
type TabId = 'FLEETS' | 'STATS';

/**
 * Der eine Satz, der ganz oben steht.
 *
 * <p>Er sagt vor allen Zahlen, ob die Zahlen etwas bedeuten. Ohne ihn müsste
 * der Leser die Fallzahl selbst gegen die Schwelle halten - und genau das tut
 * niemand, wenn eine Tabelle daneben steht.</p>
 *
 * @property ton `leise` heißt: die Grundlage trägt nicht, und der Satz sagt
 *     warum. `ruhig` heißt: sie trägt, und die Kopfzahlen stehen für sich.
 *     Es gibt bewusst kein Rot mehr - nichts auf dieser Seite ist ein Befund
 *     über eine Person.
 * @property zusatz die Fallzahl oder die Einschränkung zum Satz - nie ein
 *     zweiter Befund, sonst wären es zwei Hauptaussagen
 */
export interface Leitsatz {
  ton: 'leise' | 'ruhig';
  text: string;
  zusatz: string | null;
}

/** Wie die Tabelle "Teilnahme je Account" sortiert wird. */
export type TeilnahmeSortierung = 'NAME' | 'FLOTTEN';

@Component({
  selector: 'app-fleet-manager',
  standalone: true,
  // RouterLink nur fuer den einen Weg, den der Vorbehalt unter der
  // Teilnahmetafel anbietet: Wer dort liest, dass Accounts unverknuepft sind,
  // soll die Alt-Erkennung nicht erst suchen muessen.
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './fleet-manager.component.html',
  // Zwei Blätter, weil das Budget aus angular.json je Komponentenblatt gilt und
  // nicht je Komponente. Seit dem Umzug der drei Fitting-Reiter reichte auch
  // eines - die Trennung bleibt trotzdem, weil sie eine Sache trennt und nicht
  // nur Bytes: die FAT-Statistik hat ihre eigene Formensprache. Die Begründung
  // steht ausführlich in fat-statistik.scss.
  styleUrls: ['./fleet-manager.component.scss', './fat-statistik.scss']
})
export class FleetManagerComponent implements OnInit, OnDestroy {
  public authService = inject(AuthService);
  private fleetService = inject(FleetService);
  private statisticsService = inject(FleetStatisticsService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  // Bildadressen kommen aus den gemeinsamen Utilities - hier werden sie nur
  // noch fuer das Template sichtbar gemacht.
  protected readonly portrait = portrait;

  activeTab = signal<TabId>('FLEETS');

  // --- Fleet State ---
  recentFleets = signal<FleetEvent[]>([]);
  attendanceList = signal<FleetAttendance[]>([]);
  selectedFleetId = signal<number | null>(null);

  showCreateModal = signal(false);
  fleetName = '';
  doctrineInput = '';
  expiryMinutes = 60;
  trackingType: 'LIVE' | 'LINK' = 'LIVE';

  isCreating = signal(false);
  isSyncing = signal(false);
  private pollingInterval: any;

  selectedFleetObj = computed(() => {
    return this.recentFleets().find(f => f.id === this.selectedFleetId());
  });

  // --- FAT-Statistik State ---
  readonly zeitraeume = ZEITRAEUME;

  statistik = signal<FatStatistik | null>(null);
  statistikTage = signal<ZeitraumTage>(ZEITRAUM_VORGABE);
  loadingStatistik = signal(false);
  statistikFehler = signal<string | null>(null);

  /**
   * Die eigene Sicht - ein eigenes Signal und kein zurechtgestutztes
   * `statistik()`.
   *
   * <p>Derselbe Gedanke wie im Server: Zwei Zustände, die nicht dieselben
   * Felder haben, können nicht versehentlich ineinander laufen. Wer die
   * Corp-Tafel sieht, sieht sie ganz; wer sie nicht sieht, hat sie auch nicht
   * im Speicher stehen.</p>
   */
  meineFat = signal<MeineFat | null>(null);

  /** Ob die Accounts mit genau einer Flotte ausgeklappt sind. */
  einmaligeGezeigt = signal(false);

  /**
   * Welche Zeilen ihre Charakternamen zeigen. Schlüssel ist die accountId.
   *
   * <p>Zugeklappt als Vorgabe: Die Tafel ist ein Nachschlagewerk, und neben
   * jedem Namen drei weitere zu führen macht sie unlesbar. Aufgeklappt
   * beantwortet die Zeile die einzige Frage, die ein Direktor an eine
   * Gruppierung hat - wer da eigentlich geflogen ist und ob die Zuordnung
   * stimmt.</p>
   */
  charaktereGezeigt = signal<Set<number>>(new Set());

  /**
   * Vorgabe `NAME`, umschaltbar auf `FLOTTEN`.
   *
   * <p>Die Vorgabesortierung entscheidet, ob die Tabelle ein Nachschlagewerk
   * ist oder eine Bestenliste - und eine Bestenliste hat immer ein unteres
   * Ende. Wer nach Flotten sortieren will, kann das; er tut es dann
   * absichtlich.</p>
   */
  teilnahmeSortierung = signal<TeilnahmeSortierung>('NAME');

  get isFleetCommander(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38', 'ROLE_69']);
  }

  /**
   * Wer die FAT-Statistik sehen darf.
   *
   * <p>Genau die drei Rollen aus `AccessRules.FLEET_STAFF` im Server, und
   * bewusst <b>nicht</b> die gleichnamige Konstante in `app.routes.ts`: die
   * führt zusätzlich ROLE_CEO und ROLE_69 und ist mit dem Server nicht mehr
   * deckungsgleich. Der weitere Kreis hier hieße, dass ein CEO den Reiter
   * sieht und beim Öffnen ein 403 bekommt - eine Rolle anzubieten, die der
   * Server ablehnt, ist schlimmer als sie wegzulassen.</p>
   *
   * <p>Das ist die Anzeigegrenze, nicht die Sperre. Die Sperre steht im
   * `FleetStatisticsService` des Servers und greift auch dann, wenn jemand
   * die Adresse direkt aufruft. Wer den Kreis dort ändert, muss ihn hier
   * mitändern.</p>
   */
  get canSeeStats(): boolean {
    return this.authService.hasAnyRole(['ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38']);
  }

  /**
   * Die Beschriftung des Reiters - und damit ein Versprechen.
   *
   * <p>"Meine FAT-Statistik" sagt vor dem Klick, was dahinter steht: die eigene
   * Teilnahme und nicht die Tafel der Corporation. Stünde für alle dasselbe
   * Wort da, erwartete ein Mitglied die Tafel und hielte die eine Zeile für
   * einen Fehler. Dieselbe Grenze wie im Server - `AccessRules.FLEET_STAFF`
   * gegen `AccessRules.AUTHENTICATED`.</p>
   */
  get statsReiterTitel(): string {
    return this.canSeeStats ? 'FAT-Statistik' : 'Meine FAT-Statistik';
  }

  /**
   * Ob diese Zeile der Tafel dem Betrachter selbst gehört.
   *
   * <p>Die Kennung des Accounts ist die des Mains, und genau die steht in der
   * Sitzung - der JwtAuthenticationFilter setzt den Main als Principal. Die
   * Hervorhebung ist keine Spielerei: In einer Tafel mit dreißig Namen sucht
   * jeder zuerst sich selbst, und wer seine eigene Zahl findet, kann als
   * Einziger beurteilen, ob die Zählung stimmt. Genau deshalb bekommt die
   * Führung keine zweite, eigene Ansicht - zwei Zahlen für denselben
   * Sachverhalt wären eine mehr als nötig.</p>
   */
  istEigeneZeile(zeile: AccountZeile): boolean {
    const eigene = this.authService.currentUser()?.characterId;
    // Die Prüfung auf null steht davor, weil sonst zwei fehlende Werte gleich
    // wären und eine fremde Zeile die Markierung "das bist du" trüge.
    return eigene != null && zeile.accountId === eigene;
  }

  ngOnInit() {
    this.loadRecentFleets();
    this.pollingInterval = setInterval(() => this.loadRecentFleets(), 10000);
  }

  ngOnDestroy() {
    if (this.pollingInterval) clearInterval(this.pollingInterval);
  }

  setTab(tab: TabId) {
    this.activeTab.set(tab);

    // Einmal beim Wechsel und danach nur auf Zeitraumwechsel oder Knopfdruck.
    // Der 10-Sekunden-Takt der Flottenliste hat hier nichts zu suchen: eine
    // Quartalsauswertung ändert sich nicht in zehn Sekunden, sie flackerte nur
    // unter den Augen des Lesers.
    if (tab === 'STATS' && !this.statistik() && !this.meineFat() && !this.loadingStatistik()) {
      this.loadStatistik();
    }
  }

  // ================= Fleet Logic =================
  // (Unverändert)

  loadRecentFleets() {
    this.fleetService.getRecentFleets().subscribe(fleets => {
      this.recentFleets.set(fleets);
      if (this.selectedFleetId()) {
        this.loadAttendance(this.selectedFleetId()!);
      } else if (fleets.length > 0) {
        this.selectFleet(fleets[0].id);
      }
    });
  }

  selectFleet(eventId: number) {
    this.selectedFleetId.set(eventId);
    this.loadAttendance(eventId);
  }

  loadAttendance(eventId: number) {
    this.fleetService.getFleetAttendance(eventId).subscribe(att => {
      this.attendanceList.set(att);
    });
  }

  createFleet() {
    if (!this.fleetName) return;
    this.isCreating.set(true);
    this.fleetService.createFleet({
      fleetName: this.fleetName,
      doctrine: this.doctrineInput,
      linkExpiryMinutes: this.expiryMinutes,
      trackingType: this.trackingType
    }).subscribe({
      next: () => {
        this.isCreating.set(false);
        this.showCreateModal.set(false);
        this.fleetName = '';
        this.doctrineInput = '';
        this.loadRecentFleets();
        this.toastService.success('Flotte erfolgreich gestartet!');
      },
      error: (err) => {
        this.isCreating.set(false);
        this.toastService.error(err.error?.message || 'Fehler beim Erstellen der Flotte.');
      }
    });
  }

  syncEsi(fleetId: number) {
    this.isSyncing.set(true);
    this.fleetService.syncFleetViaEsi(fleetId).subscribe({
      next: (count) => {
        this.toastService.success(`Sync abgeschlossen: ${count} neue Member erfasst!`);
        this.isSyncing.set(false);
        this.loadAttendance(fleetId);
      },
      error: (err) => {
        this.toastService.error(err.error?.message || 'ESI Fehler beim Synchronisieren.');
        this.isSyncing.set(false);
      }
    });
  }

  async closeFleet(fleetId: number) {
    const confirmed = await this.confirmService.ask(
      'Tracking beenden?',
      'Möchtest du das Tracking für diesen FAT wirklich beenden?',
      'FAT beenden',
      'Abbrechen'
    );
    if (confirmed) {
      this.fleetService.closeFleet(fleetId).subscribe({
        next: () => {
          this.toastService.info('Flotten-Tracking wurde beendet.');
          this.loadRecentFleets();
        }
      });
    }
  }

  copyLinkToClipboard(code: string): Promise<void> {
    return copyText(this.getJoinUrlFor(code)).then((ok) =>
      ok
        ? this.toastService.success('PAP-Link erfolgreich kopiert!')
        : this.toastService.error('Fehler beim Kopieren des PAP-Links.'));
  }

  getJoinUrlFor(code: string): string {
    return `${window.location.origin}/fleet/join/${code}`;
  }

  // ================= FAT-Statistik =================

  /**
   * Lädt die Sicht, die dem Betrachter zusteht.
   *
   * <p>Die Weiche steht hier und nicht in einem Endpunkt mit Schalter: Der
   * Server hat zwei Adressen, und welche gefragt wird, entscheidet dieselbe
   * Rollengrenze, die dort am `@PreAuthorize` steht. Ruft ein Mitglied die
   * Corp-Adresse trotzdem auf, weist der Server ab - die Weiche hier ist
   * Bequemlichkeit, keine Sicherung.</p>
   */
  loadStatistik() {
    if (this.canSeeStats) {
      this.ladeCorpStatistik();
    } else {
      this.ladeMeineFat();
    }
  }

  private ladeCorpStatistik() {
    this.loadingStatistik.set(true);
    this.statistikFehler.set(null);

    this.statisticsService.statistik(this.statistikTage()).subscribe({
      next: (data) => {
        this.statistik.set(data);
        this.loadingStatistik.set(false);
        // Beim Neuladen wieder zu: ein anderer Zeitraum bringt eine andere
        // Gästeliste hervor, und die soll man aufschlagen, nicht vorfinden.
        this.einmaligeGezeigt.set(false);
        // Dasselbe für die aufgeklappten Charakterlisten: Ein anderer Zeitraum
        // heißt andere Charaktere je Zeile - eine offene Zeile zeigte sonst
        // eine Liste, die zum neuen Zeitraum gar nicht gehört.
        this.charaktereGezeigt.set(new Set());
      },
      error: (err) => {
        this.loadingStatistik.set(false);
        // Der alte Stand wird verworfen und nicht stehen gelassen: Zahlen aus
        // einem anderen Zeitraum unter einer neuen Überschrift wären die
        // schlimmere Auskunft als gar keine.
        this.statistik.set(null);
        const meldung = err?.error?.message || 'Die FAT-Statistik konnte nicht geladen werden.';
        this.statistikFehler.set(meldung);
        this.toastService.error(meldung);
      }
    });
  }

  /**
   * Die eigene Sicht.
   *
   * <p>Sie beantwortet eine andere Frage als die Tafel - "wie stehe ich da"
   * statt "wer war dabei" - und kommt deshalb aus einer eigenen Adresse in ein
   * eigenes Signal. Ein zurechtgestutztes `statistik()` wäre derselbe Fehler
   * wie ein leergeräumtes DTO im Server.</p>
   */
  private ladeMeineFat() {
    this.loadingStatistik.set(true);
    this.statistikFehler.set(null);

    this.statisticsService.meineStatistik(this.statistikTage()).subscribe({
      next: (data) => {
        this.meineFat.set(data);
        this.loadingStatistik.set(false);
      },
      error: (err) => {
        this.loadingStatistik.set(false);
        // Wie oben: Zahlen aus einem anderen Zeitraum unter einer neuen
        // Überschrift wären die schlimmere Auskunft als gar keine.
        this.meineFat.set(null);
        const meldung = err?.error?.message
          || 'Deine FAT-Statistik konnte nicht geladen werden.';
        this.statistikFehler.set(meldung);
        this.toastService.error(meldung);
      }
    });
  }

  /** Zeitraumwechsel lädt neu - der Server rechnet, nicht das Frontend. */
  setStatistikTage(tage: ZeitraumTage) {
    if (this.statistikTage() === tage) return;
    this.statistikTage.set(tage);
    this.loadStatistik();
  }

  /**
   * Der Satz ganz oben - siehe {@link Leitsatz}.
   *
   * <p>Trägt die Grundlage nicht, steht ihr Klartext hier und nicht erst als
   * Fußnote unter der Tafel: Wer die Tabelle liest, ohne zu wissen, dass sie
   * auf drei Flotten beruht, liest sie falsch. Trägt sie, nennt der Satz nur
   * die absoluten Zahlen - sie behaupten nichts, was der Leser nicht selbst
   * nachzählen könnte.</p>
   */
  leitsatz = computed<Leitsatz | null>(() => {
    const s = this.statistik();
    if (!s) return null;

    if (!s.auswertbar) {
      return {
        ton: 'leise',
        text: s.hinweis ?? 'Für eine Auswertung liegen zu wenige Flotten im Zeitraum.',
        zusatz: s.zeitraum.hinweis
      };
    }

    // Absichtlich kein "alles in Ordnung": Diese Seite zählt Teilnahmen, sie
    // misst nichts. Der Zusatz ist deshalb nur die Klammer zur Spanne.
    //
    // "Accounts aus Charakteren" und nicht mehr "Piloten": Die Tafel zählt je
    // Account, und eine Zahl, deren Einheit sich ändert, ohne dass die
    // Beschriftung mitgeht, ist eine stille Falschaussage.
    return {
      ton: 'ruhig',
      text: `${s.kopf.flotten} Flotten in ${s.zeitraum.tageAusgewertet} Tagen, `
        + `${s.kopf.accounts} ${s.kopf.accounts === 1 ? 'Account' : 'Accounts'} `
        + `aus ${s.kopf.charaktere} ${s.kopf.charaktere === 1 ? 'Charakter' : 'Charakteren'}, `
        + `${s.kopf.fcs} ${s.kopf.fcs === 1 ? 'FC' : 'FCs'}.`,
      zusatz: s.zeitraum.hinweis
    };
  });

  /**
   * Derselbe Satz ganz oben, nur für die eigene Sicht.
   *
   * <p>Er sagt in einem Zug, was die Zahlen darunter bedeuten - und im Leerfall
   * sagt er den Klartext des Servers, statt eine Null stehen zu lassen, die
   * aussieht wie ein Befund über eine Person. Der Satz kommt fertig vom Server:
   * dieselbe Aussage soll nicht an zwei Stellen entstehen und auseinanderdriften.</p>
   */
  meinLeitsatz = computed<Leitsatz | null>(() => {
    const m = this.meineFat();
    if (!m) return null;

    if (!m.dabei) {
      return {
        ton: 'leise',
        text: m.hinweis ?? 'Im gewählten Zeitraum ist keine Teilnahme von dir erfasst.',
        zusatz: m.zeitraum.hinweis
      };
    }

    // Mit Nenner, und ohne Prozentwert: "6" allein ist keine Aussage, "6 von
    // 20" ist eine - und der Leser weiß selbst, wann er im Urlaub war.
    return {
      ton: 'ruhig',
      text: `Du warst bei ${m.flotten.zaehler} von ${m.flotten.nenner} `
        + `${m.flotten.nenner === 1 ? 'Flotte' : 'Flotten'} der letzten `
        + `${m.zeitraum.tageAusgewertet} Tage dabei.`,
      zusatz: m.zeitraum.hinweis
    };
  });

  /** Die Teilnahmetafel in der gewählten Reihenfolge. */
  teilnahmeZeilen = computed<AccountZeile[]>(() => {
    const zeilen = this.statistik()?.teilnahme.zeilen ?? [];
    const sortiert = [...zeilen];
    if (this.teilnahmeSortierung() === 'FLOTTEN') {
      // Bei Gleichstand nach Namen, damit die Reihenfolge zwischen zwei
      // Ladevorgängen nicht springt.
      sortiert.sort((a, b) => b.flotten - a.flotten || this.nameOf(a).localeCompare(this.nameOf(b)));
    } else {
      sortiert.sort((a, b) => this.nameOf(a).localeCompare(this.nameOf(b)));
    }
    return sortiert;
  });

  toggleTeilnahmeSortierung() {
    this.teilnahmeSortierung.update(s => (s === 'NAME' ? 'FLOTTEN' : 'NAME'));
  }

  toggleEinmalige() {
    this.einmaligeGezeigt.update(v => !v);
  }

  /** Klappt die Charakterliste einer Zeile auf oder zu. */
  toggleCharaktere(accountId: number) {
    this.charaktereGezeigt.update(offen => {
      // Eine neue Menge statt einer veränderten: Ein Signal, dessen Wert
      // dieselbe Referenz behält, meldet keine Änderung.
      const naechste = new Set(offen);
      if (!naechste.delete(accountId)) naechste.add(accountId);
      return naechste;
    });
  }

  zeigtCharaktere(accountId: number): boolean {
    return this.charaktereGezeigt().has(accountId);
  }

  /**
   * Warum nichts dasteht - und zwar welcher der drei Gründe es ist.
   *
   * <p>"Keine Daten" ist keine Auskunft. Es macht einen Unterschied, ob die
   * Corporation noch nie eine Flotte erfasst hat, ob im gewählten Fenster
   * keine liegt (dann hilft ein größerer Zeitraum) oder ob es Flotten gab, an
   * denen niemand teilgenommen hat (dann ist die Erfassung das Problem, nicht
   * das Fliegen).</p>
   */
  leerGrund = computed<string | null>(() => {
    const s = this.statistik();
    if (!s || (s.kopf.flotten > 0 && s.kopf.accounts > 0)) return null;

    if (!s.zeitraum.ersteFlotteInsgesamt) {
      return 'Es ist noch nie eine Flotte erfasst worden. Sobald im Reiter '
        + '"Aktive Flotten" die erste läuft, füllt sich diese Seite von selbst.';
    }
    if (s.kopf.flotten === 0) {
      return `In den letzten ${s.zeitraum.tageAusgewertet} Tagen liegt keine Flotte - `
        + 'ältere gibt es aber. Ein größerer Zeitraum zeigt sie.';
    }
    return `${s.kopf.flotten} Flotten, aber keine einzige erfasste Teilnahme. `
      + 'Die Flotten sind angelegt worden, nur hat niemand den Link geklickt und '
      + 'kein ESI-Abgleich hat gegriffen.';
  });

  /**
   * Ein Name, der nie leer ist.
   *
   * <p>Er fehlt, wenn das Auth den Account gar nicht kennt und auch keine
   * Teilnahmezeile einen Namen trug. Sortieren darf daran nicht scheitern.</p>
   */
  nameOf(zeile: { name?: string | null }): string {
    return zeile.name ?? 'Unbekannt';
  }
}
