import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AltSuggestionsComponent,
  ERFASSUNG_SAETZE,
  SIGNAL_UEBERSICHT_HINWEIS,
  begruendungSatz,
  chipText,
  gruppenSatz,
  leerzustandSaetze,
  paarZahl,
  signalBilanz,
  signalStandSaetze,
  signalUebersicht,
  zeileFuer,
} from './alt-suggestions.component';
import {
  AltCalibrationDto,
  AltGroupDto,
  AltPairDto,
  AltSignalConfigDto,
  AltSignalDto,
  AltSuggestionDto,
  CharacterService,
} from '../../services/character.service';
import { ConfirmService } from '../../services/confirm.service';
import { ToastService } from '../../services/toast.service';

/**
 * Die Alt-Vorschlaege.
 *
 * <p>Geprueft wird der Zustand und nicht das Aussehen: kein Fixture, keine
 * DOM-Abfrage. Was in der Spalte "Wahrscheinlichkeit" steht, entscheidet
 * {@link begruendungSatz}; was die Rueckfrage verspricht, entscheidet
 * `rueckfrageText`. Beides ist hier direkt pruefbar.</p>
 *
 * <p>Der wichtigste Fall ist der Abbruch: ein Dienst, der trotz "Abbrechen"
 * gerufen wird, legt eine Vormerkung an, die sich ueber diese Oberflaeche nicht
 * zurueckholen laesst.</p>
 */
describe('AltSuggestionsComponent', () => {
  const signalDto = (over: Partial<AltSignalDto> = {}): AltSignalDto => ({
    signal: 'NAME',
    label: 'Namensaehnlichkeit',
    available: true,
    score: 85,
    weightPercent: 40,
    detail: 'Gleicher Nachname.',
    ...over,
  });

  const vorschlag = (over: Partial<AltSuggestionDto> = {}): AltSuggestionDto => ({
    unauthedCharId: 2002,
    unauthedCharName: 'Comander Video',
    mainId: 1001,
    mainName: 'Comander Audio',
    probability: 93,
    signalsUsed: 2,
    signalsTotal: 3,
    signals: [
      signalDto(),
      signalDto({ signal: 'JOIN', label: 'Beitritts-Cluster', score: 100, weightPercent: 45 }),
      signalDto({
        signal: 'MINING',
        label: 'Mining-Aktivitaet',
        available: false,
        score: null,
        weightPercent: 15,
        detail: 'Keine Mining-Zeilen fuer den unregistrierten Charakter.',
      }),
    ],
    corpId: 98000001,
    ...over,
  });

  const gruppe = (over: Partial<AltGroupDto> = {}): AltGroupDto => ({
    corpId: 98000001,
    members: [
      { id: 3001, name: 'Vexor Prime', portraitUrl: '' },
      { id: 3002, name: 'Vexor Secundus', portraitUrl: '' },
    ],
    probability: 95,
    signalsUsed: 1,
    signalsTotal: 3,
    signals: [
      signalDto({ score: 95, detail: 'Durchnummerierter Zwilling.' }),
      signalDto({ signal: 'JOIN', label: 'Beitritts-Cluster', available: false, score: null }),
      signalDto({ signal: 'MINING', label: 'Mining-Aktivitaet', available: false, score: null }),
    ],
    note: 'Keiner dieser Charaktere ist registriert - der naechste Schritt ist eine Nachfrage im Spiel.',
    ...over,
  });

  const paar = (over: Partial<AltPairDto> = {}): AltPairDto => ({
    leftId: 3001,
    leftName: 'Vexor Prime',
    rightId: 3002,
    rightName: 'Vexor Secundus',
    corpId: 98000001,
    probability: 72,
    signalsUsed: 1,
    signalsTotal: 3,
    signals: [
      signalDto({ score: 72 }),
      signalDto({ signal: 'JOIN', label: 'Beitritts-Cluster', available: false, score: null }),
      signalDto({ signal: 'MINING', label: 'Mining-Aktivitaet', available: false, score: null }),
    ],
    requiredThreshold: 90,
    aboveThreshold: false,
    ...over,
  });

  const signalKonfig = (over: Partial<AltSignalConfigDto> = {}): AltSignalConfigDto => ({
    signal: 'NAME',
    label: 'Namensaehnlichkeit',
    weightPercent: 40,
    availableInPairs: 549,
    examinedPairs: 549,
    ...over,
  });

  /**
   * Die sieben Signale, wie der Server sie heute liefert - vier davon stumm.
   *
   * Genau dieser Zuschnitt ist der Alltag kurz nach der Umstellung: Name,
   * Beitritt und Mining haben Daten, die vier neuen Quellen noch nicht. 549 ist
   * die Summe der beiden gerechneten Paarzahlen der Kalibrierung.
   */
  const alleSieben = (): AltSignalConfigDto[] => [
    signalKonfig(),
    signalKonfig({
      signal: 'JOIN',
      label: 'Beitritts-Cluster',
      weightPercent: 45,
      availableInPairs: 12,
    }),
    signalKonfig({
      signal: 'MINING',
      label: 'Mining-Aktivitaet',
      weightPercent: 15,
      availableInPairs: 3,
    }),
    signalKonfig({
      signal: 'ISK',
      label: 'ISK-Ueberweisungen',
      weightPercent: 50,
      availableInPairs: 0,
    }),
    signalKonfig({
      signal: 'CONTACT',
      label: 'Kontaktliste',
      weightPercent: 25,
      availableInPairs: 0,
    }),
    signalKonfig({
      signal: 'MAIL',
      label: 'Nachrichtenanzahl',
      weightPercent: 8,
      availableInPairs: 0,
    }),
    signalKonfig({
      signal: 'PRESENCE',
      label: 'Gemeinsamer Aufenthalt',
      weightPercent: 30,
      availableInPairs: 0,
    }),
  ];

  const kalibrierung = (over: Partial<AltCalibrationDto> = {}): AltCalibrationDto => ({
    limit: 20,
    maxLimit: 200,
    examinedAccountPairs: 143,
    examinedUnregisteredPairs: 406,
    minProbability: 80,
    minProbabilitySingleSignal: 90,
    minAvailableSignals: 1,
    signalConfig: alleSieben(),
    accountPairs: [{ suggestion: vorschlag(), requiredThreshold: 80, aboveThreshold: true }],
    unregisteredPairs: [paar()],
    ...over,
  });

  /** Ein Fehler, wie ihn der Interceptor durchreicht - mit und ohne Meldung. */
  const fehler = (message?: string) =>
    throwError(() => (message ? { error: { message } } : { error: null }));

  let characterService: {
    getAltSuggestions: ReturnType<typeof vi.fn>;
    getAltGroups: ReturnType<typeof vi.fn>;
    getAltCalibration: ReturnType<typeof vi.fn>;
    confirmAltSuggestion: ReturnType<typeof vi.fn>;
  };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirm: { ask: ReturnType<typeof vi.fn> };

  function build(
    vorschlaege: AltSuggestionDto[] = [vorschlag()],
    bestaetigt = true,
    gruppen: AltGroupDto[] = [gruppe()],
  ) {
    characterService = {
      getAltSuggestions: vi.fn().mockReturnValue(of(vorschlaege)),
      getAltGroups: vi.fn().mockReturnValue(of(gruppen)),
      getAltCalibration: vi.fn().mockReturnValue(of(kalibrierung())),
      confirmAltSuggestion: vi.fn().mockReturnValue(
        of({
          unauthedCharId: 2002,
          unauthedCharName: 'Comander Video',
          mainId: 1001,
          mainName: 'Comander Audio',
          probability: 93,
          linked: false,
          message: 'Vorgemerkt. Comander Video ist damit noch NICHT zugeordnet.',
        }),
      ),
    };
    toast = { success: vi.fn(), error: vi.fn() };
    confirm = { ask: vi.fn().mockResolvedValue(bestaetigt) };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: CharacterService, useValue: characterService },
        { provide: ToastService, useValue: { ...toast, info: vi.fn() } },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    return TestBed.runInInjectionContext(() => new AltSuggestionsComponent());
  }

  beforeEach(() => vi.clearAllMocks());

  // ================= Laden =================

  it('fuellt das Signal beim Start', () => {
    const c = build();
    c.ngOnInit();

    expect(characterService.getAltSuggestions).toHaveBeenCalledTimes(1);
    expect(c.suggestions()).toHaveLength(1);
    expect(c.suggestions()[0].unauthedCharName).toBe('Comander Video');
    expect(c.loading()).toBe(false);
    expect(c.isEmpty()).toBe(false);
  });

  it('unterscheidet den Leerzustand vom Ladezustand', () => {
    // Vor ngOnInit ist loading gesetzt und isEmpty falsch - sonst stuende
    // "kein Vorschlag" da, waehrend der Server noch bei ESI fragt.
    const c = build([]);
    expect(c.loading()).toBe(true);
    expect(c.isEmpty()).toBe(false);

    c.ngOnInit();
    expect(c.loading()).toBe(false);
    expect(c.isEmpty()).toBe(true);
  });

  it('leert die Liste, wenn das Laden fehlschlaegt', () => {
    // Bliebe der alte Stand stehen, bestaetigte der naechste Klick einen
    // Vorschlag, den es womoeglich nicht mehr gibt.
    const c = build();
    c.ngOnInit();
    characterService.getAltSuggestions.mockReturnValue(fehler('ESI antwortet nicht.'));

    c.ngOnInit();

    expect(c.suggestions()).toEqual([]);
    expect(c.loading()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('ESI antwortet nicht.');
  });

  it('nimmt den Ersatztext, wenn der Server keinen Grund nennt', () => {
    const c = build();
    characterService.getAltSuggestions.mockReturnValue(fehler());

    c.ngOnInit();

    expect(toast.error).toHaveBeenCalledWith('Die Alt-Vorschlaege konnten nicht geladen werden.');
  });

  // ================= Die Rueckfrage =================

  it('nennt in der Rueckfrage beide echten Namen', () => {
    const c = build();
    const text = c.rueckfrageText(vorschlag());

    expect(text).toContain('Comander Video');
    expect(text).toContain('Comander Audio');
    expect(text).toContain('93 %');
  });

  it('sagt in der Rueckfrage, dass noch nichts zugeordnet ist und nichts zurueckgeht', () => {
    // Eine Rueckfrage, die die Folge verschweigt, ist eine Formalitaet: der
    // Director glaubte sonst, der Charakter haenge danach am Konto.
    const c = build();
    const text = c.rueckfrageText(vorschlag());

    expect(text).toContain('NICHT');
    expect(text).toContain('EVE-Login');
    expect(text).toContain('nicht zuruecknehmen');
  });

  it('ruft den Dienst NICHT, wenn abgebrochen wird', async () => {
    const c = build([vorschlag()], false);
    c.ngOnInit();

    await c.verknuepfen(vorschlag());

    expect(confirm.ask).toHaveBeenCalledTimes(1);
    expect(characterService.confirmAltSuggestion).not.toHaveBeenCalled();
    expect(toast.success).not.toHaveBeenCalled();
    expect(c.pending()).toBeNull();
  });

  // ================= Bestaetigen =================

  it('ruft den Dienst mit beiden IDs und laedt danach neu', async () => {
    const c = build();
    c.ngOnInit();

    await c.verknuepfen(vorschlag());

    expect(characterService.confirmAltSuggestion).toHaveBeenCalledWith(2002, 1001);
    // Zweimal: einmal ngOnInit, einmal nach der Bestaetigung.
    expect(characterService.getAltSuggestions).toHaveBeenCalledTimes(2);
    expect(c.pending()).toBeNull();
  });

  it('meldet den Klartext des Servers und keinen eigenen Erfolgssatz', async () => {
    // Nur der Server weiss, ob zugeordnet oder bloss vorgemerkt wurde. Ein
    // eigenes "Verknuepft!" waere hier schlicht falsch.
    const c = build();
    c.ngOnInit();

    await c.verknuepfen(vorschlag());

    expect(toast.success).toHaveBeenCalledWith(
      'Vorgemerkt. Comander Video ist damit noch NICHT zugeordnet.',
    );
  });

  it('zeigt bei einem Fehler die Begruendung des Servers', async () => {
    const c = build();
    c.ngOnInit();
    characterService.confirmAltSuggestion.mockReturnValue(
      fehler('Fuer dieses Paar gibt es bereits eine Vormerkung.'),
    );

    await c.verknuepfen(vorschlag());

    expect(toast.error).toHaveBeenCalledWith('Fuer dieses Paar gibt es bereits eine Vormerkung.');
    expect(toast.success).not.toHaveBeenCalled();
    expect(c.pending()).toBeNull();
    // Nach einem Fehler wird NICHT neu geladen: sonst verschwaende die Zeile
    // moeglicherweise, und niemand saehe mehr, worauf sich die Meldung bezog.
    expect(characterService.getAltSuggestions).toHaveBeenCalledTimes(1);
  });

  it('nimmt den Ersatztext, wenn der Fehler keinen Grund traegt', async () => {
    const c = build();
    c.ngOnInit();
    characterService.confirmAltSuggestion.mockReturnValue(fehler());

    await c.verknuepfen(vorschlag());

    expect(toast.error).toHaveBeenCalledWith('Der Vorschlag konnte nicht bestaetigt werden.');
  });

  it('laesst keinen zweiten Klick durch, solange einer laeuft', async () => {
    const c = build();
    c.ngOnInit();
    c.pending.set(2002);

    await c.verknuepfen(vorschlag());

    expect(confirm.ask).not.toHaveBeenCalled();
    expect(characterService.confirmAltSuggestion).not.toHaveBeenCalled();
  });

  it('markiert nur die laufende Zeile', () => {
    const c = build();
    expect(c.isPending(vorschlag())).toBe(false);

    c.pending.set(2002);
    expect(c.isPending(vorschlag())).toBe(true);
    expect(c.isPending(vorschlag({ unauthedCharId: 3003 }))).toBe(false);
  });

  // ================= Die Begruendung neben der Zahl =================

  it('nennt die tragenden Signale, wenn mehrere gemessen wurden', () => {
    expect(begruendungSatz(vorschlag())).toBe(
      '2 von 3 Signalen tragen: Namensaehnlichkeit, Beitritts-Cluster.',
    );
  });

  it('warnt ausdruecklich, wenn die Zahl nur auf einem Signal beruht', () => {
    // 85 aus einem gleichen Nachnamen und 85 aus drei Signalen sehen in der
    // Tabelle gleich aus und sind es nicht. Ohne diesen Satz waere die Spalte
    // irrefuehrend.
    const nurName = vorschlag({
      probability: 85,
      signalsUsed: 1,
      signals: [
        signalDto(),
        signalDto({ signal: 'JOIN', label: 'Beitritts-Cluster', available: false, score: null }),
        signalDto({ signal: 'MINING', label: 'Mining-Aktivitaet', available: false, score: null }),
      ],
    });

    expect(begruendungSatz(nurName)).toBe(
      'Traegt nur Namensaehnlichkeit - eine hohe Zahl aus einer einzigen Quelle ist ein Verdacht, kein Nachweis.',
    );
  });

  it('behauptet auch ohne ein einziges Signal keine Messung', () => {
    const leer = vorschlag({ signalsUsed: 0, signals: [] });

    expect(begruendungSatz(leer)).toContain('kein einziges Signal');
  });

  it('schreibt bei einem nicht gemessenen Signal "nicht gemessen" statt einer 0', () => {
    // Der Unterschied ist der ganze Punkt von AltSignalDto.available: eine 0
    // saehe aus wie ein Freispruch, obwohl gar nichts geprueft wurde.
    expect(chipText(signalDto())).toBe('Namensaehnlichkeit 85');
    expect(chipText(signalDto({ available: false, score: null }))).toBe(
      'Namensaehnlichkeit: nicht gemessen',
    );
  });

  it('reicht die Anzeige-Helfer der Vorlage durch', () => {
    const c = build();

    expect(c.begruendung(vorschlag())).toBe(begruendungSatz(vorschlag()));
    expect(c.chipBeschriftung(signalDto())).toBe(chipText(signalDto()));
    expect(c.gruppenBeschreibung(gruppe())).toBe(gruppenSatz(gruppe()));
  });

  // ================= Gruppen Unregistrierter =================

  it('laedt die Gruppen mit eigenem Aufruf und zeigt sie an', () => {
    const c = build();
    c.ngOnInit();

    expect(characterService.getAltGroups).toHaveBeenCalledTimes(1);
    expect(c.groups()).toHaveLength(1);
    expect(c.groups()[0].members.map((m) => m.name)).toEqual(['Vexor Prime', 'Vexor Secundus']);
    expect(c.groupsLoading()).toBe(false);
    expect(c.groupsEmpty()).toBe(false);
  });

  it('haelt die Vorschlaege bedienbar, wenn nur die Gruppen ausfallen', () => {
    // Zwei Endpunkte, zwei Ladezustaende. Ein gemeinsamer verschwiege, welcher
    // von beiden ausgefallen ist - und sperrte die obere Liste ohne Grund.
    const c = build();
    characterService.getAltGroups.mockReturnValue(fehler('ESI antwortet nicht.'));

    c.ngOnInit();

    expect(c.groups()).toEqual([]);
    expect(c.suggestions()).toHaveLength(1);
    expect(toast.error).toHaveBeenCalledWith('ESI antwortet nicht.');
  });

  it('bietet zu einer Gruppe keinerlei Bestaetigung an', () => {
    // Es gibt kein Konto, dem sich die Gruppe zuordnen liesse, und der Server
    // hat dazu bewusst keinen Endpunkt. Eine Schaltflaeche, die nichts bewirkt,
    // liesse den Director glauben, die Sache sei erledigt.
    const c = build();
    c.ngOnInit();

    const methoden = Object.getOwnPropertyNames(Object.getPrototypeOf(c));
    expect(methoden.filter((name) => /gruppe/i.test(name)).sort()).toEqual([
      'gruppenBeschreibung',
      'gruppenSchluessel',
    ]);
    expect(characterService.confirmAltSuggestion).not.toHaveBeenCalled();
    expect(characterService).not.toHaveProperty('confirmAltGroup');
  });

  it('nennt im Gruppensatz die schwaechste Verbindung und die Zahl der Paare', () => {
    const satz = gruppenSatz(gruppe());

    expect(satz).toContain('2 Charaktere, 1 Paare');
    expect(satz).toContain('schwaechste Verbindung');
    expect(satz).toContain('1 von 3');
    expect(paarZahl(4)).toBe(6);
    expect(paarZahl(1)).toBe(0);
  });

  it('behauptet auch bei einer Gruppe ohne tragendes Signal keine Messung', () => {
    const stumm = gruppe({
      signalsUsed: 0,
      signals: [signalDto({ available: false, score: null })],
    });

    expect(gruppenSatz(stumm)).toContain('kein einziges Signal');
  });

  it('bildet je Gruppe einen stabilen Schluessel aus den Mitgliedern', () => {
    // Der Server liefert keine ID fuer eine Gruppe. Ohne stabilen Schluessel
    // baute die Vorlage die Zeilen bei jedem Neuladen komplett neu auf.
    const c = build();

    expect(c.gruppenSchluessel(gruppe())).toBe('3001-3002');
  });

  // ================= Die Kalibrierung =================

  it('laedt die Kalibrierung nicht mit, solange etwas gefunden wurde', () => {
    // Sie rechnet alle Paare beider Richtungen und fragt ESI erneut. Wer etwas
    // dastehen hat, sieht ja bereits, dass die Erkennung laeuft.
    const c = build();
    c.ngOnInit();

    expect(characterService.getAltCalibration).not.toHaveBeenCalled();
    expect(c.calibration()).toBeNull();
  });

  it('laedt die Kalibrierung auf Anforderung getrennt nach', () => {
    const c = build();
    c.ngOnInit();

    c.ladeKalibrierung();

    expect(characterService.getAltCalibration).toHaveBeenCalledTimes(1);
    expect(c.calibration()?.examinedAccountPairs).toBe(143);
    expect(c.calibration()?.unregisteredPairs).toHaveLength(1);
    expect(c.calibrationLoading()).toBe(false);
    expect(c.calibrationFehler()).toBeNull();
  });

  it('laesst keinen zweiten Kalibrierabruf los, solange einer laeuft', () => {
    const c = build();
    c.ngOnInit();
    c.calibrationLoading.set(true);

    c.ladeKalibrierung();

    expect(characterService.getAltCalibration).not.toHaveBeenCalled();
  });

  it('merkt sich, warum die Kalibrierzahlen fehlen', () => {
    // Nur ein Toast reichte nicht: an diesen Zahlen haengt der Leerzustand.
    // Fehlten sie kommentarlos, saehe ein Ausfall aus wie ein sauberes
    // "nichts gefunden".
    const c = build();
    c.ngOnInit();
    characterService.getAltCalibration.mockReturnValue(fehler('Kein Director-Token.'));

    c.ladeKalibrierung();

    expect(c.calibration()).toBeNull();
    expect(c.calibrationFehler()).toBe('Kein Director-Token.');
    expect(toast.error).toHaveBeenCalledWith('Kein Director-Token.');
  });

  it('bestaetigt aus der Kalibrieransicht heraus nichts', () => {
    // Gaebe es hier einen Weg, liesse sich ueber die Kalibrierung genau die
    // Schwelle aushebeln, die sie sichtbar machen soll: sie liefert
    // ausdruecklich auch die Paare DARUNTER.
    const c = build([], true, []);
    c.ngOnInit();

    c.ladeKalibrierung();

    expect(c.calibration()?.unregisteredPairs[0].aboveThreshold).toBe(false);
    expect(characterService.confirmAltSuggestion).not.toHaveBeenCalled();
    expect(confirm.ask).not.toHaveBeenCalled();
  });

  // ================= Der Leerzustand =================

  it('holt die Zahlen von selbst, sobald beide Listen leer bleiben', () => {
    const c = build([], true, []);
    c.ngOnInit();

    expect(c.nichtsGefunden()).toBe(true);
    expect(characterService.getAltCalibration).toHaveBeenCalledTimes(1);
    expect(c.calibration()).not.toBeNull();
  });

  it('holt die Zahlen genau einmal, obwohl beide Listen sie anstossen', () => {
    const c = build([], true, []);
    c.ngOnInit();
    c.ngOnInit();

    // Zweimal ngOnInit, aber die Kalibrierung liegt nach dem ersten Durchlauf
    // vor - ein zweiter Vollabzug ueber ESI waere reine Verschwendung.
    expect(characterService.getAltCalibration).toHaveBeenCalledTimes(1);
  });

  it('nennt im Leerzustand, wieviel gerechnet wurde und wo die Schwelle liegt', () => {
    const saetze = leerzustandSaetze(kalibrierung());

    expect(saetze[0]).toContain('143 Paare "unregistriert gegen Konto"');
    expect(saetze[0]).toContain('406 Paare "unregistriert gegen unregistriert"');
    expect(saetze[0]).toContain('Die Erkennung laeuft also');
    expect(saetze[1]).toContain('80 Punkten');
    expect(saetze[1]).toContain('90');
  });

  it('unterscheidet im Leerzustand "nichts gefunden" von "nichts gerechnet"', () => {
    // Das ist der ganze Zweck der Ansicht. Beide Faelle hinterlassen dieselbe
    // leere Liste, und nur diese Zahl trennt sie.
    const saetze = leerzustandSaetze(
      kalibrierung({
        examinedAccountPairs: 0,
        examinedUnregisteredPairs: 0,
        // Wurde nichts gerechnet, traegt auch die Signal-Uebersicht ueberall
        // Nullen. Sie darf daraus KEIN "noch keine Daten" machen: das waere
        // eine Messung, die es nicht gab.
        signalConfig: alleSieben().map((eintrag) => ({
          ...eintrag,
          availableInPairs: 0,
          examinedPairs: 0,
        })),
        accountPairs: [],
        unregisteredPairs: [],
      }),
    );

    expect(saetze[0]).toContain('kein einziges Paar gerechnet');
    expect(saetze[0]).toContain('Ausfall');
    expect(saetze.join(' ')).not.toContain('Die Erkennung laeuft also');
  });

  it('sagt im Leerzustand, welche Signale ueberhaupt vorlagen', () => {
    const saetze = leerzustandSaetze(kalibrierung()).join(' ');

    // Ein Vorschlag mit Namen und Beitritt, ein Paar nur mit Namen: der Name
    // lag zweimal vor, der Beitritt einmal, Mining nie.
    expect(saetze).toContain('Namensaehnlichkeit 2 von 2 mal');
    expect(saetze).toContain('Beitritts-Cluster 1 von 2 mal');
    expect(saetze).toContain('Mining-Aktivitaet 0 von 2 mal');
  });

  it('sagt im Leerzustand ausdruecklich, dass ohne Director-Token nur der Name traegt', () => {
    const ohneBeitritt = kalibrierung({
      accountPairs: [
        {
          suggestion: vorschlag({
            signals: [
              signalDto(),
              signalDto({
                signal: 'JOIN',
                label: 'Beitritts-Cluster',
                available: false,
                score: null,
              }),
            ],
          }),
          requiredThreshold: 90,
          aboveThreshold: false,
        },
      ],
      unregisteredPairs: [],
    });

    const saetze = leerzustandSaetze(ohneBeitritt).join(' ');

    expect(saetze).toContain('Kein einziges Mal vorgelegen hat: Beitritts-Cluster');
    expect(saetze).toContain('Ohne Director-Token');
    expect(saetze).toContain('traegt allein der Name');
  });

  it('sagt dazu, dass die Signalbilanz nur die gezeigten Zeilen zaehlt', () => {
    // Eine Hochrechnung von 20 Zeilen auf die ganze Corporation waere geraten -
    // und Raten ist genau das, was diese Ansicht abschaffen soll.
    const saetze = leerzustandSaetze(kalibrierung()).join(' ');

    expect(saetze).toContain('nicht die ganze Corporation');
    expect(saetze).toContain('20 Zeilen je Liste');
  });

  it('zaehlt die Signalbilanz ueber beide Listen zusammen', () => {
    const bilanz = signalBilanz(kalibrierung());

    expect(bilanz.map((e) => e.signal)).toEqual(['NAME', 'JOIN', 'MINING']);
    expect(bilanz[0]).toEqual({
      signal: 'NAME',
      label: 'Namensaehnlichkeit',
      verfuegbar: 2,
      gesamt: 2,
    });
    expect(bilanz[2].verfuegbar).toBe(0);
  });

  it('haelt den Leerzustand leer, solange die Zahlen fehlen', () => {
    // Lieber gar nichts als ein erfundener Satz: das Signal traegt genau die
    // Zahlen des Servers und sonst keine.
    const c = build([], true, []);

    expect(c.leerzustand()).toEqual([]);

    c.ngOnInit();
    expect(c.leerzustand().length).toBeGreaterThan(0);
  });

  it('merkt sich, dass die Liste durch einen Ausfall leer ist', () => {
    // Sonst stuende "die Erkennung laeuft also" ueber einem Abruf, der nie
    // angekommen ist - der Toast ist dann laengst weggeblendet.
    const c = build([], true, []);
    characterService.getAltSuggestions.mockReturnValue(fehler('ESI antwortet nicht.'));

    c.ngOnInit();

    expect(c.ladefehler()).toBe('ESI antwortet nicht.');
    expect(c.isEmpty()).toBe(true);
  });

  it('raeumt den Ausfallgrund weg, sobald ein Abruf wieder durchkommt', () => {
    const c = build([], true, []);
    characterService.getAltSuggestions.mockReturnValue(fehler('ESI antwortet nicht.'));
    c.ngOnInit();
    expect(c.ladefehler()).not.toBeNull();

    characterService.getAltSuggestions.mockReturnValue(of([vorschlag()]));
    c.ngOnInit();

    expect(c.ladefehler()).toBeNull();
  });

  // ================= Die Signal-Uebersicht =================

  it('laedt die Signal-Uebersicht mit und zeigt alle sieben Signale', () => {
    // Ohne diese Zeile faellt auf, dass die Uebersicht gar nicht ankommt: sie
    // haengt an derselben Antwort wie die Paartabellen, und ein vergessenes
    // Feld im DTO liesse die Liste still leer bleiben - die Seite saehe dann
    // aus wie vorher, obwohl der Auftrag genau diese Liste war.
    const c = build([], true, []);
    c.ngOnInit();

    expect(c.signalZeilen()).toHaveLength(7);
    expect(c.signalZeilen().map((zeile) => zeile.signal)).toEqual([
      'NAME',
      'JOIN',
      'MINING',
      'ISK',
      'CONTACT',
      'MAIL',
      'PRESENCE',
    ]);
    expect(c.signalZeilen()[0].label).toBe('Namensaehnlichkeit');
    expect(c.signalZeilen()[3].gewicht).toBe(50);
  });

  it('haelt die Signal-Uebersicht leer, solange die Kalibrierung fehlt', () => {
    // Sonst stuenden Gewichte ohne die Zahl daneben, die sie einzuordnen
    // erlaubt - und genau diese Zahl ist der Zweck der Ansicht.
    const c = build();

    expect(c.signalZeilen()).toEqual([]);
  });

  it('weist ein Signal ohne Daten als "noch keine Daten" aus und nicht als schwach', () => {
    // Ohne diese Unterscheidung dreht der Nutzer am Gewicht, obwohl die
    // Erfassung das Problem ist: "0 von 549" heisst nicht "schwaches Signal",
    // sondern "stummes Signal". Ein hoeheres Gewicht aendert daran nichts.
    const isk = zeileFuer(
      signalKonfig({ signal: 'ISK', label: 'ISK-Ueberweisungen', availableInPairs: 0 }),
    );

    expect(isk.stand).toBe('ohne-daten');
    expect(isk.verfuegbar).toBe(0);
    expect(isk.gerechnet).toBe(549);
    expect(isk.auskunft).toContain('Noch keine Daten');
    expect(isk.auskunft).toContain('in keinem der 549 gerechneten Paare');
    expect(isk.auskunft).toContain('kein schwaches Signal, sondern ein stummes');
    expect(isk.auskunft).not.toContain('schwach.');
  });

  it('trennt "noch keine Daten" von "es wurde ueberhaupt nichts gerechnet"', () => {
    // Beide zeigen eine 0. Nur der erste Fall ist eine Messung; wer sie
    // zusammenwirft, liest aus einem Ausfall eine Aussage ueber das Signal
    // heraus, die niemand erhoben hat.
    const stumm = zeileFuer(signalKonfig({ availableInPairs: 0, examinedPairs: 549 }));
    const ungerechnet = zeileFuer(signalKonfig({ availableInPairs: 0, examinedPairs: 0 }));

    expect(stumm.stand).toBe('ohne-daten');
    expect(ungerechnet.stand).toBe('nichts-gerechnet');
    expect(ungerechnet.auskunft).toContain('kein einziges Paar gerechnet');
    expect(ungerechnet.auskunft).toContain('nichts gesagt');
    expect(ungerechnet.auskunft).not.toContain('Noch keine Daten');
    // Kein Prozentsatz aus einer Division durch Null - eine NaN in der Spalte
    // saehe aus wie ein Rechenfehler der Erkennung.
    expect(ungerechnet.anteilProzent).toBe(0);
  });

  it('rechnet den Anteil nur dort, wo ueberhaupt gerechnet wurde', () => {
    // Der Anteil ist die Bequemlichkeit, nicht die Aussage: 100 % aus einem
    // einzigen Paar und 100 % aus 549 sind dasselbe Prozent und nicht dieselbe
    // Auskunft. Deshalb stehen beide Zahlen daneben.
    const zeilen = signalUebersicht(kalibrierung());

    expect(zeilen[0].anteilProzent).toBe(100);
    expect(zeilen[1].anteilProzent).toBe(2);
    expect(zeilen[1].auskunft).toBe('Lag in 12 von 549 gerechneten Paaren vor.');
  });

  it('nennt ueber der Uebersicht sichtbar, warum die Datenzahl die wichtigste ist', () => {
    // Der Satz gehoert auf die Seite und nicht in einen Kommentar: wer ein
    // Gewicht verstellt und dieselbe Liste wiedersieht, sucht den Fehler sonst
    // bei sich, obwohl schlicht noch nichts erfasst ist.
    const c = build();

    expect(c.signalHinweis).toBe(SIGNAL_UEBERSICHT_HINWEIS);
    expect(c.signalHinweis).toContain('das Gewicht wirkt nicht');
    expect(c.signalHinweis).toContain('in keinem einzigen Paar Daten');
    expect(c.signalHinweis).toContain('nicht schwach, sondern stumm');
  });

  it('bestaetigt aus der Kalibrieransicht heraus auch mit der Uebersicht nichts', () => {
    // Gaebe es hier einen Bedienpunkt, liesse sich ueber die Kalibrierung genau
    // die Schwelle aushebeln, die sie sichtbar machen soll - sie liefert
    // ausdruecklich auch die Paare DARUNTER. Die Uebersicht ist reine Anzeige;
    // die Gewichte stehen in eve.alt-detection.* und nicht auf dieser Seite.
    const c = build([], true, []);
    c.ngOnInit();
    c.ladeKalibrierung();

    expect(c.signalZeilen().length).toBeGreaterThan(0);
    expect(characterService.confirmAltSuggestion).not.toHaveBeenCalled();
    expect(confirm.ask).not.toHaveBeenCalled();

    const methoden = Object.getOwnPropertyNames(Object.getPrototypeOf(c));
    // Genau ein Weg zu einer Bestaetigung, und der haengt an der oberen Liste.
    expect(methoden.filter((name) => /verknuepf|bestaetig|confirm/i.test(name))).toEqual([
      'verknuepfen',
    ]);
    expect(characterService).not.toHaveProperty('setSignalWeight');
  });

  // ================= Was aufgezeichnet wird =================

  it('sagt bei Nachrichten ausdruecklich, dass nur gezaehlt und nichts gelesen wird', () => {
    // Die Zusage ist bindend und im Code durchgesetzt: CharacterMailCount hat
    // kein Textfeld und keine Mail-ID, EsiMailHeaderResponse liest weder
    // Betreff noch Mail-ID ein. Ein Satz, der hier mehr verspraeche - oder
    // weniger klar waere - liesse die Oberflaeche den Eindruck erwecken,
    // Inhalte seien verfuegbar.
    const c = build();
    const text = c.erfassung.join(' ');

    expect(c.erfassung).toBe(ERFASSUNG_SAETZE);
    expect(text).toContain('Anzahl gewechselter Nachrichten');
    expect(text).toContain('ausschliesslich gezaehlt');
    expect(text).toContain('Betreff und Text werden weder gespeichert noch eingelesen');
    expect(text).toContain('keine Mail-ID');
  });

  it('nennt die vier neuen Quellen und die Aufbewahrungsfrist', () => {
    // Ohne diese Zeile kann der Text still auf den alten Stand zurueckfallen,
    // waehrend die Erkennung laengst Bewegungsdaten erfasst - und niemand auf
    // der Seite erfaehrt es.
    const text = ERFASSUNG_SAETZE.join(' ');

    expect(text).toContain('ISK-Ueberweisungen');
    expect(text).toContain('Kontaktliste');
    expect(text).toContain('in welchem System Corp-Mitglieder gesehen wurden');
    expect(text).toContain('Aufbewahrungsfrist');
    expect(text).toContain('taeglich');

    // KEINE Zahl im Text. Die Fristen stehen in eve.alt-sources.* und sind ueber
    // die .env aenderbar; eine hier eingetragene "90" waere nach dem ersten
    // Verstellen eine Falschaussage - ausgerechnet in dem Kasten, dessen Zweck
    // Verlaesslichkeit ist. Der Test haelt die Abwesenheit fest, weil sonst der
    // naechste, der den Satz "konkreter" macht, genau das wieder einbaut.
    expect(text).not.toMatch(/\d+\s*Tage/);

    // Und die Zusage muss ALLE Quellen decken. Frueher stand hier, Kontakte und
    // Nachrichtenanzahlen braeuchten keine Frist, weil jeder Lauf sie ersetze -
    // das galt aber nur je Charakter und nur, wenn er im Lauf vorkam. Wer sein
    // Token entzog, blieb fuer immer gespeichert.
    expect(text).toContain('Alle vier Quellen');
    expect(text).toContain('herausgefallen');
  });

  // ================= Der Leerzustand nennt die stummen Signale =================

  it('nennt im Leerzustand, welche Signale schon Daten liefern und welche nicht', () => {
    // "Keine Vorschlaege" heisst bei frischer Erfassung etwas anderes als nach
    // vier Wochen. Ohne diese Aufzaehlung ist beides derselbe leere Bildschirm,
    // und der Nutzer haelt einen Anlaufzustand fuer einen Befund.
    const saetze = leerzustandSaetze(kalibrierung()).join(' ');

    expect(saetze).toContain('Daten geliefert haben bisher: Namensaehnlichkeit in 549 von 549');
    expect(saetze).toContain('Beitritts-Cluster in 12 von 549 Paaren');
    expect(saetze).toContain(
      'Noch keine Daten hat: ISK-Ueberweisungen, Kontaktliste, Nachrichtenanzahl, ' +
        'Gemeinsamer Aufenthalt',
    );
    expect(saetze).toContain('nicht schwach, sondern stumm');
  });

  it('erfindet im Leerzustand keinen Signalstand, wenn nichts gerechnet wurde', () => {
    // Bei 0 gerechneten Paaren traegt die Uebersicht ueberall Nullen. Ein
    // "noch keine Daten" daraus waere eine Messung, die es nicht gab - der Satz
    // ueber den Ausfall steht bereits davor.
    const saetze = signalStandSaetze(
      kalibrierung({
        signalConfig: alleSieben().map((eintrag) => ({
          ...eintrag,
          availableInPairs: 0,
          examinedPairs: 0,
        })),
      }),
    );

    expect(saetze).toEqual([]);
  });

  it('sagt es ausdruecklich, wenn kein einziges Signal Daten hatte', () => {
    // Sonst stuende dort ein leerer Satzanfang "Daten geliefert haben bisher:"
    // - und das laese sich als Aufzaehlung lesen, die abgeschnitten wurde.
    const saetze = signalStandSaetze(
      kalibrierung({
        signalConfig: alleSieben().map((eintrag) => ({ ...eintrag, availableInPairs: 0 })),
      }),
    );

    expect(saetze[0]).toContain('Kein einziges der 7 Signale');
    expect(saetze[0]).toContain('549 gerechneten Paaren');
  });

  it('kommt ohne Signal-Uebersicht durch, ohne den Leerzustand zu verlieren', () => {
    // Ein aelterer Server liefert das Feld nicht. Faellt dann der ganze
    // Leerzustand aus, steht der Nutzer wieder vor der leeren Seite, gegen die
    // diese Ansicht gebaut ist.
    const ohne = { ...kalibrierung(), signalConfig: [] };

    expect(signalUebersicht(ohne)).toEqual([]);
    expect(signalStandSaetze(ohne)).toEqual([]);
    expect(leerzustandSaetze(ohne).length).toBeGreaterThan(0);
  });

  it('laedt nach einer Bestaetigung auch die Gruppen neu', async () => {
    // Eine Vormerkung nimmt den Charakter aus den Kandidaten; damit zerfaellt
    // womoeglich eine Gruppe, die daneben sonst weiter mit ihm dastuende.
    const c = build();
    c.ngOnInit();

    await c.verknuepfen(vorschlag());

    expect(characterService.getAltGroups).toHaveBeenCalledTimes(2);
  });
});
