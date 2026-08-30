import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AltSuggestionsComponent, begruendungSatz, chipText } from './alt-suggestions.component';
import {
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

  /** Ein Fehler, wie ihn der Interceptor durchreicht - mit und ohne Meldung. */
  const fehler = (message?: string) =>
    throwError(() => (message ? { error: { message } } : { error: null }));

  let characterService: {
    getAltSuggestions: ReturnType<typeof vi.fn>;
    confirmAltSuggestion: ReturnType<typeof vi.fn>;
  };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirm: { ask: ReturnType<typeof vi.fn> };

  function build(vorschlaege: AltSuggestionDto[] = [vorschlag()], bestaetigt = true) {
    characterService = {
      getAltSuggestions: vi.fn().mockReturnValue(of(vorschlaege)),
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
  });
});
