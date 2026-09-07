import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FleetManagerComponent } from './fleet-manager.component';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { FleetService } from '../../services/fleet.service';
import { ToastService } from '../../services/toast.service';
import {
  AccountZeile,
  Anteil,
  FatStatistik,
  FleetStatisticsService,
  MeineFat,
} from '../../services/fleet-statistics.service';

/** Eine Quote, wie der Server sie liefert: Zähler und Nenner, nie ein Prozentwert. */
function anteil(zaehler: number, nenner: number): Anteil {
  return { zaehler, nenner };
}

/**
 * Eine Zeile der Tafel - ein Account, nicht ein Charakter.
 *
 * <p>`name` ist der des Mains; `charaktere` sind die, die tatsächlich geflogen
 * sind. Beides kann auseinanderfallen, und genau das prüfen die Tests unten.</p>
 */
function konto(name: string, flotten: number, over: Partial<AccountZeile> = {}): AccountZeile {
  return {
    accountId: name.length * 100 + flotten,
    name,
    charaktere: [name],
    flotten,
    ersteFlotte: '2026-06-10T18:00:00Z',
    letzteFlotte: '2026-09-01T18:00:00Z',
    verbunden: true,
    ...over,
  };
}

/**
 * Eine tragfähige Statistik als Ausgangspunkt.
 *
 * <p>Jeder Test verändert davon genau das, worum es ihm geht. So steht in jedem
 * Test nur die eine Abweichung, die die Aussage trägt.</p>
 */
function statistik(over: Partial<FatStatistik> = {}): FatStatistik {
  return {
    zeitraum: {
      tageGewaehlt: 90,
      tageAusgewertet: 90,
      von: '2026-06-08T00:00:00Z',
      bis: '2026-09-06T00:00:00Z',
      ersteFlotteInsgesamt: '2026-05-01T18:00:00Z',
      tageMitDaten: 90,
      gekuerzt: false,
      hinweis: null,
    },
    kopf: {
      flotten: 40,
      accounts: 22,
      charaktere: 37,
      fcs: 4,
      liveAnteil: anteil(31, 40),
      doktrin: {
        gereiht: true,
        haeufigste: 'Ferox',
        anteil: anteil(11, 40),
        ohneAngabe: 12,
        hinweis: null,
      },
    },
    auswertbar: true,
    hinweis: null,
    teilnahme: {
      zeilen: [konto('Zeta Pilot', 12), konto('Alpha Pilot', 20), konto('Mitte Pilot', 12)],
      einmalige: [konto('Gast Eins', 1), konto('Gast Zwei', 1)],
      ohneVerbindung: anteil(3, 22),
      hinweis: 'Bei 3 von 22 Accounts kennt das Auth nur einen einzigen Charakter.',
    },
    ...over,
  };
}

/**
 * Die eigene Sicht, wie der Server sie liefert.
 *
 * <p>Sie hat andere Felder als {@link statistik} - kein `kopf`, keine
 * `teilnahme`, keine Liste von Zeilen. Das ist der Punkt: Eine fremde Zeile
 * hat hier keinen Platz, und ein Test hält das weiter unten am Datensatz
 * fest.</p>
 */
function meineFat(over: Partial<MeineFat> = {}): MeineFat {
  return {
    zeitraum: statistik().zeitraum,
    flotten: anteil(6, 20),
    dabei: true,
    charaktere: ['Ich Selbst', 'Mein Erster Alt'],
    ersteFlotte: '2026-06-12T18:00:00Z',
    letzteFlotte: '2026-09-02T18:00:00Z',
    verbunden: true,
    hinweis: null,
    verbindungsHinweis: '2 Charaktere sind unter CharLink mit dir verknuepft.',
    ...over,
  };
}

describe('FleetManagerComponent', () => {
  let component: FleetManagerComponent;
  let fleetService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let authService: {
    hasAnyRole: ReturnType<typeof vi.fn>;
    currentUser: ReturnType<typeof vi.fn>;
  };
  let statisticsService: {
    statistik: ReturnType<typeof vi.fn>;
    meineStatistik: ReturnType<typeof vi.fn>;
  };
  let clipboard: { writeText: ReturnType<typeof vi.fn> };

  const fleet = { id: 55, fleetName: 'Roam', trackingType: 'LIVE', trackingCode: 'abc' };

  beforeEach(() => {
    vi.useFakeTimers();

    fleetService = {
      getRecentFleets: vi.fn().mockReturnValue(of([fleet])),
      getFleetAttendance: vi.fn().mockReturnValue(of([])),
      createFleet: vi.fn().mockReturnValue(of(fleet)),
      syncFleetViaEsi: vi.fn().mockReturnValue(of(3)),
      closeFleet: vi.fn().mockReturnValue(of(null)),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };
    authService = {
      hasAnyRole: vi.fn().mockReturnValue(true),
      // Das Principal ist die Kennung des Mains - und die ist zugleich die
      // accountId der Tafel. Nur deshalb lässt sich die eigene Zeile finden.
      currentUser: vi.fn().mockReturnValue({ characterId: 5000, roles: [] }),
    };
    statisticsService = {
      statistik: vi.fn().mockReturnValue(of(statistik())),
      meineStatistik: vi.fn().mockReturnValue(of(meineFat())),
    };

    clipboard = { writeText: vi.fn().mockResolvedValue(undefined) };
    vi.stubGlobal('navigator', { clipboard });
    vi.stubGlobal('window', { location: { origin: 'https://auth.example.org' } });

    TestBed.configureTestingModule({
      providers: [
        { provide: FleetService, useValue: fleetService },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: AuthService, useValue: authService },
        { provide: FleetStatisticsService, useValue: statisticsService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new FleetManagerComponent());
  });

  afterEach(() => {
    component.ngOnDestroy();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  describe('Flottenliste', () => {
    it('lädt die Flotten beim Start und wählt die erste aus', () => {
      component.ngOnInit();

      expect(component.recentFleets()).toHaveLength(1);
      expect(component.selectedFleetId()).toBe(55);
      expect(component.selectedFleetObj()?.fleetName).toBe('Roam');
    });

    it('lädt die Liste regelmäßig nach', () => {
      // Ein laufender FAT soll ohne Neuladen aktuell bleiben.
      component.ngOnInit();
      expect(fleetService['getRecentFleets']).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(10_000);

      expect(fleetService['getRecentFleets']).toHaveBeenCalledTimes(2);
    });

    it('stoppt das Nachladen beim Verlassen der Seite', () => {
      component.ngOnInit();

      component.ngOnDestroy();
      vi.advanceTimersByTime(30_000);

      expect(fleetService['getRecentFleets']).toHaveBeenCalledTimes(1);
    });

    it('behält eine bereits gewählte Flotte beim Nachladen', () => {
      component.selectFleet(55);
      fleetService['getRecentFleets'].mockReturnValue(of([fleet, { ...fleet, id: 99 }]));

      component.loadRecentFleets();

      expect(component.selectedFleetId()).toBe(55);
    });

    it('lädt die Anwesenheitsliste zur gewählten Flotte', () => {
      component.selectFleet(55);

      expect(fleetService['getFleetAttendance']).toHaveBeenCalledWith(55);
    });
  });

  describe('Flotte anlegen', () => {
    it('legt ohne Namen gar nichts an', () => {
      component.fleetName = '';

      component.createFleet();

      expect(fleetService['createFleet']).not.toHaveBeenCalled();
    });

    it('schickt die Eingaben und räumt das Formular auf', () => {
      component.fleetName = 'Roam';
      component.doctrineInput = 'Armor';
      component.expiryMinutes = 30;
      component.trackingType = 'LINK';
      component.showCreateModal.set(true);

      component.createFleet();

      expect(fleetService['createFleet']).toHaveBeenCalledWith({
        fleetName: 'Roam',
        doctrine: 'Armor',
        linkExpiryMinutes: 30,
        trackingType: 'LINK',
      });
      expect(component.fleetName).toBe('');
      expect(component.showCreateModal()).toBe(false);
      expect(component.isCreating()).toBe(false);
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('zeigt die Meldung des Servers, wenn das Anlegen scheitert', () => {
      fleetService['createFleet'].mockReturnValue(
        throwError(() => ({ error: { message: 'Du bist in keiner Flotte.' } })),
      );
      component.fleetName = 'Roam';

      component.createFleet();

      expect(toastService['error']).toHaveBeenCalledWith('Du bist in keiner Flotte.');
      expect(component.isCreating()).toBe(false);
    });
  });

  describe('ESI-Abgleich und Beenden', () => {
    it('meldet die Zahl neu erfasster Teilnehmer', () => {
      component.syncEsi(55);

      expect(toastService['success']).toHaveBeenCalledWith(
        expect.stringContaining('3 neue Member'),
      );
      expect(component.isSyncing()).toBe(false);
    });

    it('zeigt die Meldung des Servers, wenn der Abgleich scheitert', () => {
      fleetService['syncFleetViaEsi'].mockReturnValue(
        throwError(() => ({ error: { message: 'Du bist offline.' } })),
      );

      component.syncEsi(55);

      expect(toastService['error']).toHaveBeenCalledWith('Du bist offline.');
      expect(component.isSyncing()).toBe(false);
    });

    it('beendet einen FAT erst nach Rückfrage', async () => {
      await component.closeFleet(55);

      expect(confirmService.ask).toHaveBeenCalled();
      expect(fleetService['closeFleet']).toHaveBeenCalledWith(55);
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('beendet nichts, wenn die Rückfrage verneint wird', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.closeFleet(55);

      expect(fleetService['closeFleet']).not.toHaveBeenCalled();
    });
  });

  describe('Teilnahme-Link', () => {
    it('baut die Adresse aus dem Tracking-Code', () => {
      expect(component.getJoinUrlFor('abc-123')).toBe(
        'https://auth.example.org/fleet/join/abc-123',
      );
    });

    it('bestätigt das Kopieren in die Zwischenablage', async () => {
      await component.copyLinkToClipboard('abc-123');

      expect(clipboard.writeText).toHaveBeenCalledWith(
        'https://auth.example.org/fleet/join/abc-123',
      );
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('meldet, wenn die Zwischenablage nicht mitspielt', async () => {
      clipboard.writeText.mockRejectedValue(new Error('verweigert'));

      await component.copyLinkToClipboard('abc-123');
      await Promise.resolve();

      expect(toastService['error']).toHaveBeenCalled();
    });
  });

  describe('Darstellung', () => {
    // `canSeeReadiness` steht hier nicht mehr: Der Kreis ist mit den drei
    // Fitting-Reitern nach 'fittings' gezogen und wird dort geprueft.
    it('meldet die Rechte für die Oberfläche', () => {
      expect(component.isFleetCommander).toBe(true);
    });

    it('kennt nur noch die beiden Reiter der Flottenteilnahme', () => {
      // Kein gespeicherter Zustand und keine fremde Kennung koennen den
      // Manager auf einen Reiter setzen, den es nicht mehr gibt: Die Vorgabe
      // ist FLEETS, und setTab nimmt nur noch die zwei uebrigen entgegen.
      expect(component.activeTab()).toBe('FLEETS');

      component.setTab('STATS');
      expect(component.activeTab()).toBe('STATS');

      component.setTab('FLEETS');
      expect(component.activeTab()).toBe('FLEETS');
    });
  });

  /**
   * Der Reiter FAT-Statistik.
   *
   * <p>Geprüft wird der Zustand, nicht das Aussehen: kein Fixture, keine
   * DOM-Abfrage. Was hier zählt, ist ohnehin keine Frage der Darstellung,
   * sondern eine der Redlichkeit - dass bei dünner Datenlage ein Satz dasteht
   * und keine Quote, dass die Gästeliste zugeklappt beginnt und dass die Tafel
   * als Nachschlagewerk öffnet und nicht als Bestenliste.</p>
   */
  describe('FAT-Statistik', () => {
    it('lädt beim Wechsel auf den Reiter und füllt die Signale', () => {
      component.setTab('STATS');

      expect(statisticsService.statistik).toHaveBeenCalledWith(90);
      expect(component.statistik()?.kopf.flotten).toBe(40);
      expect(component.loadingStatistik()).toBe(false);
      expect(component.statistikFehler()).toBeNull();
    });

    it('lädt nicht erneut, wenn die Zahlen schon dastehen', () => {
      // Eine Quartalsauswertung ändert sich nicht beim Hin- und Herklicken.
      component.setTab('STATS');
      statisticsService.statistik.mockClear();

      component.setTab('FLEETS');
      component.setTab('STATS');

      expect(statisticsService.statistik).not.toHaveBeenCalled();
    });

    it('rechnet den Reiter nicht im Sekundentakt nach', () => {
      // Die Flottenliste wird gepollt, diese Seite nicht - sie flackerte sonst
      // unter den Augen des Lesers, ohne dass sich etwas ändert.
      component.ngOnInit();
      component.setTab('STATS');
      statisticsService.statistik.mockClear();

      vi.advanceTimersByTime(60_000);

      expect(statisticsService.statistik).not.toHaveBeenCalled();
    });

    it('lädt beim Zeitraumwechsel neu und merkt sich den gewählten Zeitraum', () => {
      component.setTab('STATS');

      component.setStatistikTage(30);

      expect(component.statistikTage()).toBe(30);
      expect(statisticsService.statistik).toHaveBeenLastCalledWith(30);
    });

    it('lädt nicht neu, wenn derselbe Zeitraum noch einmal geklickt wird', () => {
      component.setTab('STATS');
      statisticsService.statistik.mockClear();

      component.setStatistikTage(90);

      expect(statisticsService.statistik).not.toHaveBeenCalled();
    });

    it('klappt beim Zeitraumwechsel die Gästeliste wieder zu', () => {
      // Ein anderer Zeitraum bringt eine andere Namensliste hervor. Die soll
      // man aufschlagen, nicht vorfinden.
      component.setTab('STATS');
      component.toggleEinmalige();

      component.setStatistikTage(180);

      expect(component.einmaligeGezeigt()).toBe(false);
    });

    it('meldet einen Fehler und verwirft die alten Zahlen', () => {
      // Zahlen aus einem anderen Zeitraum unter einer neuen Überschrift wären
      // die schlimmere Auskunft als gar keine.
      component.setTab('STATS');
      expect(component.statistik()).not.toBeNull();

      statisticsService.statistik.mockReturnValue(
        throwError(() => ({ error: { message: 'Die FAT-Statistik sehen nur FCs und Direktoren.' } })),
      );
      component.setStatistikTage(30);

      expect(toastService['error']).toHaveBeenCalledWith(
        'Die FAT-Statistik sehen nur FCs und Direktoren.',
      );
      expect(component.statistikFehler()).toBe('Die FAT-Statistik sehen nur FCs und Direktoren.');
      expect(component.statistik()).toBeNull();
      expect(component.loadingStatistik()).toBe(false);
    });

    it('meldet auch einen Fehler ohne Text des Servers', () => {
      statisticsService.statistik.mockReturnValue(throwError(() => new Error('offline')));

      component.setTab('STATS');

      expect(toastService['error']).toHaveBeenCalledWith(
        'Die FAT-Statistik konnte nicht geladen werden.',
      );
      expect(component.statistikFehler()).not.toBeNull();
    });

    describe('Rechte', () => {
      it('zeigt den Reiter nur der Flottenführung', () => {
        expect(component.canSeeStats).toBe(true);
        expect(authService.hasAnyRole).toHaveBeenCalledWith(
          ['ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38'],
        );
      });

      it('verbirgt den Reiter für alle anderen', () => {
        authService.hasAnyRole.mockReturnValue(false);

        expect(component.canSeeStats).toBe(false);
      });

      it('fragt genau den Rollenkreis des Servers ab, nicht den weiteren der Routen', () => {
        // `AccessRules.FLEET_STAFF` im Server hat drei Rollen, die gleichnamige
        // Konstante in app.routes.ts hat fünf. Der weitere Kreis hier hieße,
        // dass ein CEO den Reiter sieht und beim Öffnen ein 403 bekommt.
        authService.hasAnyRole.mockImplementation(
          (rollen: string[]) => rollen.includes('ROLE_CEO'));

        expect(component.canSeeStats).toBe(false);
        expect(component.isFleetCommander).toBe(true);
      });
    });

    describe('Die Hauptaussage', () => {
      it('steht erst, wenn geladen ist', () => {
        expect(component.leitsatz()).toBeNull();
      });

      it('nennt bei dünner Datenlage den Klartext statt einer Quote', () => {
        // Der Kern dieser Seite: "2 Flotten, das reicht für keine Quote" ist
        // eine bessere Auskunft als "100 %".
        const satz = '3 Flotten seit dem 14.08. - zu wenig fuer eine Auswertung.';
        statisticsService.statistik.mockReturnValue(of(statistik({
          auswertbar: false,
          hinweis: satz,
          kopf: {
            ...statistik().kopf, flotten: 3, accounts: 5, charaktere: 9, fcs: 1,
            liveAnteil: anteil(2, 3),
          },
        })));

        component.setTab('STATS');

        expect(component.leitsatz()?.text).toBe(satz);
        expect(component.leitsatz()?.ton).toBe('leise');
      });

      it('nennt sonst nur die absoluten Zahlen der Kopfzeile', () => {
        // Kein "alles in Ordnung": Diese Seite zählt Teilnahmen, sie misst
        // nichts. Was hier steht, kann der Leser selbst nachzählen.
        component.setTab('STATS');

        expect(component.leitsatz()?.ton).toBe('ruhig');
        expect(component.leitsatz()?.text)
          .toBe('40 Flotten in 90 Tagen, 22 Accounts aus 37 Charakteren, 4 FCs.');
        expect(component.leitsatz()?.zusatz).toBeNull();
      });

      it('reicht den Vorbehalt zur Spanne durch, statt ihn zu erfinden', () => {
        const s = statistik();
        const klammer = '90 Tage gewaehlt - Daten reichen 23 Tage zurueck.';
        statisticsService.statistik.mockReturnValue(of(statistik({
          auswertbar: false,
          hinweis: 'Zu wenig fuer eine Auswertung.',
          zeitraum: { ...s.zeitraum, tageMitDaten: 23, hinweis: klammer },
        })));

        component.setTab('STATS');

        expect(component.leitsatz()?.zusatz).toBe(klammer);
      });
    });

    describe('Leerzustand', () => {
      it('schweigt, solange es etwas zu zeigen gibt', () => {
        component.setTab('STATS');

        expect(component.leerGrund()).toBeNull();
      });

      it('unterscheidet: noch nie eine Flotte erfasst', () => {
        const s = statistik();
        statisticsService.statistik.mockReturnValue(of(statistik({
          auswertbar: false,
          kopf: {
            ...s.kopf, flotten: 0, accounts: 0, charaktere: 0, fcs: 0, liveAnteil: anteil(0, 0),
          },
          zeitraum: { ...s.zeitraum, ersteFlotteInsgesamt: null, tageMitDaten: null },
        })));

        component.setTab('STATS');

        expect(component.leerGrund()).toContain('noch nie eine Flotte erfasst');
      });

      it('unterscheidet: keine im Fenster, ältere gibt es', () => {
        // Hier hilft ein größerer Zeitraum - und genau das soll dastehen.
        const s = statistik();
        statisticsService.statistik.mockReturnValue(of(statistik({
          auswertbar: false,
          kopf: {
            ...s.kopf, flotten: 0, accounts: 0, charaktere: 0, fcs: 0, liveAnteil: anteil(0, 0),
          },
        })));

        component.setTab('STATS');

        expect(component.leerGrund()).toContain('größerer Zeitraum');
      });

      it('unterscheidet: Flotten ohne eine einzige Teilnahme', () => {
        // Nicht das Fliegen ist das Problem, sondern die Erfassung.
        const s = statistik();
        statisticsService.statistik.mockReturnValue(of(statistik({
          kopf: { ...s.kopf, flotten: 6, accounts: 0, charaktere: 0, liveAnteil: anteil(6, 6) },
        })));

        component.setTab('STATS');

        expect(component.leerGrund()).toContain('keine einzige erfasste Teilnahme');
      });
    });

    describe('Zurückhaltung', () => {
      it('hält die Accounts mit einer Flotte zugeklappt', () => {
        component.setTab('STATS');

        expect(component.einmaligeGezeigt()).toBe(false);
        expect(component.statistik()?.teilnahme.einmalige).toHaveLength(2);

        component.toggleEinmalige();
        expect(component.einmaligeGezeigt()).toBe(true);
      });

      it('öffnet die Teilnahmetabelle nach Namen und nicht als Bestenliste', () => {
        // Die Vorgabesortierung entscheidet, ob die Seite ein Nachschlagewerk
        // ist oder eine Rangliste - und eine Rangliste hat immer ein unteres Ende.
        component.setTab('STATS');

        expect(component.teilnahmeSortierung()).toBe('NAME');
        expect(component.teilnahmeZeilen().map(z => z.name))
          .toEqual(['Alpha Pilot', 'Mitte Pilot', 'Zeta Pilot']);
      });

      it('sortiert auf Wunsch nach Flotten, bei Gleichstand nach Namen', () => {
        component.setTab('STATS');

        component.toggleTeilnahmeSortierung();

        expect(component.teilnahmeSortierung()).toBe('FLOTTEN');
        // 'Mitte Pilot' und 'Zeta Pilot' haben beide 12 - ohne den zweiten
        // Vergleich spränge ihre Reihenfolge zwischen zwei Ladevorgängen.
        expect(component.teilnahmeZeilen().map(z => z.name))
          .toEqual(['Alpha Pilot', 'Mitte Pilot', 'Zeta Pilot']);
        expect(component.teilnahmeZeilen()[0].flotten).toBe(20);
      });

      it('schaltet die Sortierung wieder zurück', () => {
        component.toggleTeilnahmeSortierung();
        component.toggleTeilnahmeSortierung();

        expect(component.teilnahmeSortierung()).toBe('NAME');
      });

      it('kommt ohne geladene Zahlen mit einer leeren Tabelle zurecht', () => {
        expect(component.teilnahmeZeilen()).toEqual([]);
      });
    });

    /**
     * Die Tafel zählt je Account. Was das Frontend davon tragen muss, ist
     * nicht die Gruppierung selbst - die rechnet der Server -, sondern dass
     * die Beschriftung mitgeht und der Vorbehalt sichtbar bleibt.
     */
    describe('Je Account', () => {
      it('beschriftet die Kopfzahlen als Accounts und nennt die Charaktere daneben', () => {
        // Eine Zahl, die ihre Einheit wechselt, ohne dass die Beschriftung
        // mitgeht, ist eine stille Falschaussage: "37 Piloten" wäre nach dem
        // Umbau falsch, "22 Accounts" allein verschwiege die 37 Fenster.
        component.setTab('STATS');

        expect(component.leitsatz()?.text).toContain('22 Accounts aus 37 Charakteren');
      });

      it('zählt im Singular richtig', () => {
        const s = statistik();
        statisticsService.statistik.mockReturnValue(of(statistik({
          kopf: { ...s.kopf, accounts: 1, charaktere: 1, fcs: 1 },
        })));

        component.setTab('STATS');

        expect(component.leitsatz()?.text).toContain('1 Account aus 1 Charakter, 1 FC.');
      });

      it('reicht den Vorbehalt zur CharLink-Lage durch, statt ihn zu erfinden', () => {
        // Die Gruppierung ist nur so gut wie die CharLink-Daten, und der Satz
        // dazu kommt fertig vom Server. Hier zusammengesetzt stünde dieselbe
        // Aussage an zwei Stellen und driftete auseinander.
        component.setTab('STATS');

        expect(component.statistik()?.teilnahme.hinweis).toContain('3 von 22');
        expect(component.statistik()?.teilnahme.ohneVerbindung).toEqual(anteil(3, 22));
      });

      it('hält die Charakterliste einer Zeile zugeklappt und klappt sie einzeln auf', () => {
        // Neben jedem Namen drei weitere zu führen macht das Nachschlagewerk
        // unlesbar - aufgeklappt beantwortet die Zeile die einzige Frage, die
        // ein Direktor an eine Gruppierung hat: wer da geflogen ist.
        statisticsService.statistik.mockReturnValue(of(statistik({
          teilnahme: {
            ...statistik().teilnahme,
            zeilen: [konto('Haupt Charakter', 12, {
              accountId: 5000,
              charaktere: ['Erster Alt', 'Haupt Charakter', 'Zweiter Alt'],
            })],
          },
        })));
        component.setTab('STATS');

        expect(component.zeigtCharaktere(5000)).toBe(false);

        component.toggleCharaktere(5000);
        expect(component.zeigtCharaktere(5000)).toBe(true);

        component.toggleCharaktere(5000);
        expect(component.zeigtCharaktere(5000)).toBe(false);
      });

      it('klappt beim Zeitraumwechsel die Charakterlisten wieder zu', () => {
        // Ein anderer Zeitraum heißt andere Charaktere je Zeile. Eine offen
        // gebliebene Zeile zeigte eine Liste, die zum neuen Zeitraum gar nicht
        // gehört.
        component.setTab('STATS');
        component.toggleCharaktere(5000);

        component.setStatistikTage(30);

        expect(component.zeigtCharaktere(5000)).toBe(false);
      });

      it('sortiert nach dem Namen des Mains, auch wenn der selbst nie mitflog', () => {
        // Der Name kommt aus `characters` und nicht aus den Teilnahmezeilen -
        // sonst stünde hier der Alt, der zufällig zuerst beigetreten ist.
        statisticsService.statistik.mockReturnValue(of(statistik({
          teilnahme: {
            ...statistik().teilnahme,
            zeilen: [
              konto('Zeta Haupt', 3, { charaktere: ['Alpha Alt'] }),
              konto('Alpha Haupt', 3, { charaktere: ['Zeta Alt'] }),
            ],
          },
        })));
        component.setTab('STATS');

        expect(component.teilnahmeZeilen().map(z => z.name))
          .toEqual(['Alpha Haupt', 'Zeta Haupt']);
      });

      it('sortiert eine Zeile ohne jeden Namen mit, statt daran zu scheitern', () => {
        // Kennt das Auth den Account nicht und trug auch keine Teilnahmezeile
        // einen Namen, bleibt das Feld leer. Ohne den Rückfall spränge das
        // Sortieren mit einem Fehler heraus und die ganze Tafel bliebe leer.
        statisticsService.statistik.mockReturnValue(of(statistik({
          teilnahme: {
            ...statistik().teilnahme,
            zeilen: [konto('Alpha Haupt', 3), konto('x', 2, { name: null, charaktere: [] })],
          },
        })));
        component.setTab('STATS');

        expect(component.teilnahmeZeilen().map(z => component.nameOf(z)))
          .toEqual(['Alpha Haupt', 'Unbekannt']);
      });
    });

    /**
     * Die eigene Sicht.
     *
     * <p>Die Absicherung steht im Server: Ein Mitglied bekommt die fremden
     * Zeilen gar nicht erst geschickt. Was hier zu prüfen bleibt, ist die
     * Weiche - dass ein Mitglied die eigene Adresse fragt und die corpweite
     * nicht, dass die Beschriftung vor dem Klick sagt, was dahinter steht, und
     * dass eine Leerauskunft eine Auskunft bleibt statt einer Null.</p>
     */
    describe('Die eigene Sicht', () => {

      /** Jemand ohne jede Führungsrolle - der Regelfall in der Corporation. */
      function alsMitglied() {
        authService.hasAnyRole.mockReturnValue(false);
      }

      it('zeigt den Reiter jedem Angemeldeten und sagt in der Beschriftung, was dahinter steht', () => {
        // Stünde für alle dasselbe Wort da, erwartete ein Mitglied die Tafel
        // der Corporation und hielte seine eine Zeile für einen Fehler.
        expect(component.statsReiterTitel).toBe('FAT-Statistik');

        alsMitglied();

        expect(component.statsReiterTitel).toBe('Meine FAT-Statistik');
      });

      it('fragt als Mitglied die eigene Adresse - und die corpweite gar nicht', () => {
        // DIE REGEL: Der Zuschnitt hängt an der Adresse, nicht an einer
        // Anzeige. Würde hier die corpweite Adresse gefragt und das Ergebnis
        // im Frontend gefiltert, stünden die fremden Zeilen trotzdem in der
        // Antwort - und jeder Browser zeigt sie mit zwei Klicks.
        alsMitglied();

        component.setTab('STATS');

        expect(statisticsService.meineStatistik).toHaveBeenCalledWith(90);
        expect(statisticsService.statistik).not.toHaveBeenCalled();
        expect(component.meineFat()?.flotten).toEqual(anteil(6, 20));
        expect(component.statistik()).toBeNull();
      });

      it('lädt für die Führung weiterhin die volle Tafel und nicht die eigene Sicht', () => {
        component.setTab('STATS');

        // Ohne diese Zeile könnte der Umbau die Führung stillschweigend auf
        // die eigene Zeile setzen - sie hielte die Tafel dann für kaputt.
        expect(statisticsService.statistik).toHaveBeenCalledWith(90);
        expect(statisticsService.meineStatistik).not.toHaveBeenCalled();
        expect(component.statistik()?.teilnahme.zeilen).toHaveLength(3);
        expect(component.meineFat()).toBeNull();
      });

      it('trägt im eigenen Datensatz keine fremde Zeile - und kann gar keine tragen', () => {
        // Strukturell am Datensatz und nicht am Zufall eines Testbestands:
        // Es gibt kein `kopf`, keine `teilnahme`, keine Liste von Zeilen. Die
        // einzige Liste sind die eigenen Namen. Ohne diese Zeile genügt beim
        // nächsten Umbau ein zusätzliches Feld, und die Namensliste der
        // Corporation stünde in der Antwort eines Mitglieds.
        alsMitglied();
        component.setTab('STATS');
        const m = component.meineFat()!;

        expect(Object.keys(m)).not.toContain('kopf');
        expect(Object.keys(m)).not.toContain('teilnahme');
        for (const [feld, wert] of Object.entries(m)) {
          if (Array.isArray(wert)) {
            expect(wert.every(eintrag => typeof eintrag === 'string'))
              .toBe(true);
            expect(feld).toBe('charaktere');
          }
        }
      });

      it('nennt die eigene Zahl mit ihrem Nenner, weil "6" allein keine Aussage ist', () => {
        alsMitglied();

        component.setTab('STATS');

        expect(component.meinLeitsatz()?.ton).toBe('ruhig');
        expect(component.meinLeitsatz()?.text)
          .toBe('Du warst bei 6 von 20 Flotten der letzten 90 Tage dabei.');
      });

      it('sagt bei keiner einzigen Teilnahme den Klartext des Servers statt einer Null', () => {
        // Eine 0 sähe aus wie ein Befund über eine Person. Sie kann aber
        // ebenso gut heißen, dass die Erfassung fehlt - und das steht im Satz,
        // der fertig vom Server kommt.
        alsMitglied();
        const satz = 'In den letzten 90 Tagen sind 20 Flotten gefahren worden, '
          + 'bei keiner davon bist du erfasst.';
        statisticsService.meineStatistik.mockReturnValue(of(meineFat({
          flotten: anteil(0, 20),
          dabei: false,
          charaktere: [],
          ersteFlotte: null,
          letzteFlotte: null,
          hinweis: satz,
        })));

        component.setTab('STATS');

        expect(component.meinLeitsatz()?.ton).toBe('leise');
        expect(component.meinLeitsatz()?.text).toBe(satz);
      });

      it('steht erst, wenn geladen ist', () => {
        expect(component.meinLeitsatz()).toBeNull();
      });

      it('lädt beim Zeitraumwechsel die eigene Sicht neu, nicht die corpweite', () => {
        alsMitglied();
        component.setTab('STATS');

        component.setStatistikTage(180);

        expect(statisticsService.meineStatistik).toHaveBeenLastCalledWith(180);
        expect(statisticsService.statistik).not.toHaveBeenCalled();
      });

      it('verwirft bei einem Fehler die eigenen Zahlen, statt alte stehen zu lassen', () => {
        alsMitglied();
        component.setTab('STATS');
        expect(component.meineFat()).not.toBeNull();

        statisticsService.meineStatistik.mockReturnValue(throwError(() => new Error('offline')));
        component.setStatistikTage(30);

        // Zahlen aus einem anderen Zeitraum unter einer neuen Überschrift
        // wären die schlimmere Auskunft als gar keine.
        expect(component.meineFat()).toBeNull();
        expect(component.statistikFehler())
          .toBe('Deine FAT-Statistik konnte nicht geladen werden.');
        expect(component.loadingStatistik()).toBe(false);
      });

      it('hebt in der Tafel der Führung genau die eigene Zeile hervor', () => {
        // Wer seine eigene Zahl findet, kann als Einziger beurteilen, ob die
        // Zählung stimmt. Genau deshalb bekommt die Führung keine zweite,
        // eigene Ansicht - zwei Zahlen für denselben Sachverhalt wären eine
        // zu viel.
        const eigene = konto('Ich Selbst', 12, { accountId: 5000 });
        const fremde = konto('Jemand Anders', 12, { accountId: 5001 });

        expect(component.istEigeneZeile(eigene)).toBe(true);
        expect(component.istEigeneZeile(fremde)).toBe(false);
      });

      it('hebt keine Zeile hervor, solange niemand angemeldet ist', () => {
        // Ohne den Rückfall verglichen sich `undefined` und eine fehlende
        // accountId zu true, und eine fremde Zeile trüge die Markierung "das
        // bist du".
        authService.currentUser.mockReturnValue(null);

        expect(component.istEigeneZeile(konto('Jemand Anders', 3, { accountId: 5000 })))
          .toBe(false);
      });
    });

  });
});
