import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AcademyComponent, WOCHENTAGE, ZEITFENSTER, toggleIn } from './academy.component';
import {
  AcademyService,
  InterestDto,
  TopicDetailDto,
  TopicDto,
} from '../../services/academy.service';
import { AuthRoleDto, GroupService } from '../../services/group.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';

/**
 * Die Academy-Seite.
 *
 * <p>Zu prüfen ist hier nicht, ob die Oberfläche Rechte durchsetzt - das tut
 * ausschließlich der `AcademyService` des Servers. Zu prüfen ist, dass sie
 * nichts anbietet, was der Server ohnehin ablehnen würde, und dass sie aus den
 * gelieferten Zahlen keine Aussage macht, die die Zahlen nicht hergeben.</p>
 *
 * <p>Geprüft wird durchweg der Zustand und nicht das Aussehen: kein Fixture,
 * keine DOM-Abfrage. Was auf der Karte steht, entscheidet
 * {@link AcademyComponent.nachfrageSatz} - die Balken daneben hängen an
 * denselben Zahlen und sind keine zweite Quelle.</p>
 */
describe('AcademyComponent', () => {
  const thema = (over: Partial<TopicDto> = {}): TopicDto => ({
    id: 1,
    title: 'EWar Grundlagen',
    summary: 'Dampener, Painter, Jammer',
    active: true,
    teacherRoleNames: ['ROLE_A38'],
    interestCount: 0,
    // Zwei getrennte Verteilungen und nicht eine: das Backend liefert sie
    // getrennt, und der Satz muss beide Fälle einzeln behandeln können.
    weekdayCounts: {},
    windowCounts: {},
    myWeekdays: [],
    myTimeWindows: [],
    myNote: null,
    hasMyInterest: false,
    canEdit: false,
    canViewInterest: false,
    ...over,
  });

  /** Eine vollständige Wochenverteilung - so, wie der Server sie ab zwei Bekundungen liefert. */
  const tage = (over: Record<string, number> = {}): Record<string, number> => {
    const verteilung: Record<string, number> = {};
    for (const tag of WOCHENTAGE) verteilung[tag.key] = over[tag.key] ?? 0;
    return verteilung;
  };

  const fenster = (over: Record<string, number> = {}): Record<string, number> => {
    const verteilung: Record<string, number> = {};
    for (const eintrag of ZEITFENSTER) verteilung[eintrag.key] = over[eintrag.key] ?? 0;
    return verteilung;
  };

  const detail = (description: string | null): TopicDetailDto => ({
    topic: thema(),
    description,
  });

  const person = (accountId: number, characterName: string): InterestDto => ({
    accountId,
    characterName,
    weekdays: ['TUESDAY'],
    timeWindows: ['EU_PRIME'],
    note: null,
    updatedAt: '2026-08-25T19:00:00Z',
  });

  const rolle = (name: string): AuthRoleDto => ({
    name,
    description: '',
    source: 'CUSTOM',
    special: false,
    grantingTitles: [],
  });

  /** Ein Fehler, wie ihn der Interceptor durchreicht - mit und ohne Meldung. */
  const fehler = (message?: string) =>
    throwError(() => (message ? { error: { message } } : { error: null }));

  let academy: {
    getTopics: ReturnType<typeof vi.fn>;
    getTopic: ReturnType<typeof vi.fn>;
    saveInterest: ReturnType<typeof vi.fn>;
    withdrawInterest: ReturnType<typeof vi.fn>;
    getInterested: ReturnType<typeof vi.fn>;
    getAdminTopics: ReturnType<typeof vi.fn>;
    saveTopic: ReturnType<typeof vi.fn>;
    deleteTopic: ReturnType<typeof vi.fn>;
  };
  let groupService: { getRoles: ReturnType<typeof vi.fn> };
  let auth: { hasAnyRole: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirm: { ask: ReturnType<typeof vi.fn> };

  function build(themen: TopicDto[] = [thema()], autor = false) {
    academy = {
      getTopics: vi.fn().mockReturnValue(of(themen)),
      getTopic: vi.fn().mockReturnValue(of(detail('## Inhalt'))),
      saveInterest: vi.fn().mockReturnValue(of(thema({ hasMyInterest: true, interestCount: 1 }))),
      withdrawInterest: vi.fn().mockReturnValue(of(void 0)),
      getInterested: vi.fn().mockReturnValue(of([person(42, 'Bob Painter')])),
      getAdminTopics: vi.fn().mockReturnValue(of(themen)),
      saveTopic: vi.fn().mockReturnValue(of(thema())),
      deleteTopic: vi.fn().mockReturnValue(of(void 0)),
    };
    groupService = { getRoles: vi.fn().mockReturnValue(of([rolle('ROLE_A38')])) };
    auth = { hasAnyRole: vi.fn().mockReturnValue(autor) };
    toast = { success: vi.fn(), error: vi.fn() };
    confirm = { ask: vi.fn().mockResolvedValue(true) };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: AcademyService, useValue: academy },
        { provide: GroupService, useValue: groupService },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: { ...toast, info: vi.fn() } },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    return TestBed.runInInjectionContext(() => new AcademyComponent());
  }

  beforeEach(() => vi.clearAllMocks());

  // ================= Der Klartextsatz =================

  it('lädt bei null Bekundungen ein, statt eine Null hinzuschreiben', () => {
    // Das größte Risiko dieses Features ist kein technisches: ein Board mit
    // "0 Interessierte" bleibt bei null, weil niemand gern der Erste ist.
    const c = build();
    c.ngOnInit();

    expect(c.nachfrageSatz(thema({ interestCount: 0 }))).toBe(
      'Noch niemand - sei die erste Stimme.',
    );
  });

  it('nennt bei genau einer Bekundung nur die Zahl und erfindet keine Verteilung', () => {
    // Das Backend liefert unterhalb von zwei Bekundungen absichtlich KEINE
    // Verteilung: "nur Mittwoch, USTZ" verriete in einer Corp, in der sich alle
    // kennen, faktisch den Namen. Wer hier aus myWeekdays eine Verteilung baut,
    // unterläuft genau diese Entscheidung.
    const c = build();
    const satz = c.nachfrageSatz(
      thema({ interestCount: 1, myWeekdays: ['WEDNESDAY'], weekdayCounts: {}, windowCounts: {} }),
    );

    expect(satz).toBe('1 Interessierter - ab der zweiten Stimme zeigt das Board, wann es passt.');
    expect(satz).not.toContain('Mi');
  });

  it('nennt den Spitzentag und das Spitzenfenster in einem Satz', () => {
    // Der Satz ist der eigentliche Ertrag der Seite: ein FC soll ohne einen
    // Klick sagen können, was sich lohnt und wann.
    const c = build();

    expect(
      c.nachfrageSatz(
        thema({
          interestCount: 7,
          weekdayCounts: tage({ TUESDAY: 5, THURSDAY: 3 }),
          windowCounts: fenster({ EU_PRIME: 5, USTZ: 2 }),
        }),
      ),
    ).toBe('7 Interessierte - am besten Di, EU Prime.');
  });

  it('nennt bei Gleichstand beide Tage und erfindet keinen Sieger', () => {
    // Ohne diese Zeile fiele die Wahl auf den, der zufällig zuerst in der Liste
    // steht - eine Genauigkeit, die niemand nachprüfen kann und die den FC am
    // falschen Tag ankündigen lässt.
    const c = build();

    expect(
      c.nachfrageSatz(
        thema({
          interestCount: 7,
          weekdayCounts: tage({ TUESDAY: 5, THURSDAY: 5 }),
          windowCounts: fenster({ EU_PRIME: 5 }),
        }),
      ),
    ).toBe('7 Interessierte - am besten Di oder Do, EU Prime.');
  });

  it('sagt bei drei gleichauf, dass es keinen klaren Tag gibt', () => {
    // "Mo oder Di oder Mi oder Do" ist kein Satz, den jemand liest - und keine
    // Aussage, auf die man einen Termin setzt.
    const c = build();

    expect(
      c.nachfrageSatz(
        thema({
          interestCount: 6,
          weekdayCounts: tage({ MONDAY: 2, TUESDAY: 2, WEDNESDAY: 2 }),
          windowCounts: fenster({ EU_PRIME: 4 }),
        }),
      ),
    ).toBe('6 Interessierte - kein klarer Tag, aber EU Prime.');
  });

  it('sagt ehrlich, dass keine Angabe vorliegt, wenn alle Werte null sind', () => {
    // Der Fall entsteht, sobald die Schwelle erreicht ist, aber niemand einen
    // Tag angehakt hat. "Am besten Montag" wäre hier schlicht gelogen.
    const c = build();

    expect(c.nachfrageSatz(thema({ interestCount: 4, weekdayCounts: tage(), windowCounts: fenster() }))).toBe(
      '4 Interessierte - bisher ohne Angabe, wann es passt.',
    );
  });

  it('lässt den halben Satz weg, wenn nur die Tage ein Bild ergeben', () => {
    const c = build();

    expect(
      c.nachfrageSatz(
        thema({
          interestCount: 5,
          weekdayCounts: tage({ FRIDAY: 4 }),
          windowCounts: fenster({ AUTZ: 1, EU_EARLY: 1, EU_PRIME: 1 }),
        }),
      ),
    ).toBe('5 Interessierte - am besten Fr; beim Zeitfenster kein klares Bild.');
  });

  it('sagt "kein klares Muster", wenn beide Seiten sich gleichmäßig verteilen', () => {
    const c = build();

    expect(
      c.nachfrageSatz(
        thema({
          interestCount: 3,
          weekdayCounts: tage({ MONDAY: 1, TUESDAY: 1, WEDNESDAY: 1 }),
          windowCounts: fenster({ AUTZ: 1, EU_EARLY: 1, USTZ: 1 }),
        }),
      ),
    ).toBe('3 Interessierte - kein klares Muster bei Tag und Zeit.');
  });

  // ================= Verteilung und Etiketten =================

  it('zeigt die Verteilung nur, wenn der Server eine geliefert hat', () => {
    // Das leere Objekt ist das Signal "keine Verteilung anzeigen" und keine
    // fehlende Antwort. Ohne diese Prüfung stünden sieben Nullbalken unter einer
    // Karte mit einer einzigen Bekundung - und behaupteten, niemand könne.
    const c = build();

    expect(c.hatVerteilung(thema({ interestCount: 1, weekdayCounts: {} }))).toBe(false);
    expect(c.hatVerteilung(thema({ interestCount: 2, weekdayCounts: tage({ MONDAY: 1 }) }))).toBe(true);
  });

  it('rechnet die Balkenhöhe gegen die Gesamtzahl und nicht gegen den größten Wert', () => {
    // Ein Streifen, dessen höchster Balken immer voll ausschlägt, sieht bei zwei
    // Nennungen aus wie bei zwanzig - und macht zwei Karten unvergleichbar.
    const c = build();
    const t = thema({ interestCount: 4, weekdayCounts: tage({ TUESDAY: 2 }) });

    expect(c.anteil(t, t.weekdayCounts, 'TUESDAY')).toBe(50);
    expect(c.anteil(t, t.weekdayCounts, 'MONDAY')).toBe(0);
    // Ohne die Nullprüfung stünde hier NaN im style-Attribut.
    expect(c.anteil(thema({ interestCount: 0 }), {}, 'MONDAY')).toBe(0);
  });

  it('gibt einen unbekannten Schlüssel im Klartext aus, statt ihn zu verschlucken', () => {
    // Käme ein sechstes Zeitfenster ins Backend, bevor diese Liste es kennt,
    // stünde sonst eine leere Zelle da - und niemand fände den Grund.
    const c = build();

    expect(c.label(WOCHENTAGE, 'TUESDAY')).toBe('Di');
    expect(c.label(ZEITFENSTER, 'MITTERNACHT')).toBe('MITTERNACHT');
    expect(c.meineTage(thema({ myWeekdays: ['MONDAY', 'SUNDAY'] }))).toEqual(['Mo', 'So']);
    expect(c.meineFenster(thema({ myTimeWindows: ['EU_PRIME'] }))).toEqual(['EU Prime']);
  });

  it('nennt genau einen Kartenzustand, auch wenn zwei Merkmale zutreffen', () => {
    // Ohne den einen Begriff würden Randfarbe und Kennzeichen getrennt gebildet -
    // und eine Karte leuchtete als "du bist dabei", während daneben "inaktiv"
    // steht.
    const c = build();

    expect(c.cardState(thema())).toBe('OPEN');
    expect(c.cardState(thema({ hasMyInterest: true }))).toBe('MINE');
    expect(c.cardState(thema({ active: false, hasMyInterest: true }))).toBe('INACTIVE');
  });

  // ================= Aufklappen =================

  it('holt den Lehrplan beim Aufklappen genau einmal, nicht bei jedem Klick', () => {
    // Ohne den Speicher fragt jedes Auf und Zu den Server erneut - und wer sich
    // durch zwölf Themen klickt, löst zwölf überflüssige Abrufe aus.
    const c = build();
    c.ngOnInit();
    const t = c.topics()[0];

    c.toggleTopic(t);
    c.toggleTopic(t);
    c.toggleTopic(t);

    expect(academy.getTopic).toHaveBeenCalledTimes(1);
    expect(c.lehrplan(t)).toBe('## Inhalt');
  });

  it('klappt bei einem Fehlschlag wieder zu und legt nichts ab', () => {
    // Ein leerer Eintrag im Speicher gälte hinterher als "geladen", stünde als
    // "kein Lehrplan geschrieben" da und bliebe den Rest der Sitzung so -
    // obwohl niemand weiß, was dort steht.
    const c = build();
    c.ngOnInit();
    academy.getTopic.mockReturnValue(fehler('Kaputt.'));
    const t = c.topics()[0];

    c.toggleTopic(t);

    expect(c.expandedTopicId()).toBeNull();
    expect(c.lehrplan(t)).toBeNull();
    expect(c.entwurf()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Kaputt.');

    // Und der nächste Versuch fragt wieder - der Fehlschlag hat nichts gesperrt.
    academy.getTopic.mockReturnValue(of(detail('## Inhalt')));
    c.toggleTopic(t);
    expect(academy.getTopic).toHaveBeenCalledTimes(2);
    expect(c.expandedTopicId()).toBe(t.id);
  });

  it('hält höchstens eine Karte offen', () => {
    // Bei zwölf offenen Lehrplänen scrollt niemand mehr.
    const c = build([thema(), thema({ id: 2 })]);
    c.ngOnInit();

    c.toggleTopic(c.topics()[0]);
    c.toggleTopic(c.topics()[1]);

    expect(c.expandedTopicId()).toBe(2);
  });

  it('holt die Namensliste nur für den Sichtkreis', () => {
    // Ein Aufruf, der zuverlässig in eine 403 läuft, gehört nicht angeboten -
    // die Fehlermeldung wäre die einzige Wirkung.
    const c = build([thema({ canViewInterest: false })]);
    c.ngOnInit();
    c.toggleTopic(c.topics()[0]);

    expect(academy.getInterested).not.toHaveBeenCalled();
    expect(c.interessenten(c.topics()[0])).toEqual([]);
  });

  it('lässt die Karte offen, wenn nur die Namensliste scheitert', () => {
    // Anders als beim Lehrplan: der ist der Inhalt der Karte, die Namensliste
    // ist eine Beigabe. Wer sie nicht bekommt, soll trotzdem lesen können,
    // worum es geht.
    const c = build([thema({ canViewInterest: true })]);
    c.ngOnInit();
    academy.getInterested.mockReturnValue(fehler());

    c.toggleTopic(c.topics()[0]);

    expect(c.expandedTopicId()).toBe(1);
    expect(c.lehrplan(c.topics()[0])).toBe('## Inhalt');
    expect(toast.error).toHaveBeenCalledWith('Die Interessenten konnten nicht geladen werden.');
  });

  it('holt auch die Namensliste beim zweiten Aufklappen nicht erneut', () => {
    const c = build([thema({ canViewInterest: true })]);
    c.ngOnInit();

    c.toggleTopic(c.topics()[0]);
    c.toggleTopic(c.topics()[0]);
    c.toggleTopic(c.topics()[0]);

    expect(academy.getInterested).toHaveBeenCalledTimes(1);
    expect(c.interessenten(c.topics()[0])[0].characterName).toBe('Bob Painter');
  });

  // ================= Chips und Interesse =================

  it('baut beim Umschalten eine neue Liste statt in die alte zu schieben', () => {
    // Der Entwurf liegt in einem Signal: ein `push` ändert die Referenz nicht,
    // das Signal zündet nicht, und die Chips blieben stehen, wie sie waren.
    const vorher = ['MONDAY'];
    const nachher = toggleIn(vorher, 'TUESDAY');

    expect(nachher).toEqual(['MONDAY', 'TUESDAY']);
    expect(nachher).not.toBe(vorher);
    expect(vorher).toEqual(['MONDAY']);
    expect(toggleIn(nachher, 'MONDAY')).toEqual(['TUESDAY']);
  });

  it('setzt den Entwurf beim Aufklappen auf das Gespeicherte', () => {
    // Kopien und keine Verweise: ein Chip-Klick darf die Karte dahinter nicht
    // schon vor dem Speichern umschreiben.
    const t = thema({ hasMyInterest: true, myWeekdays: ['TUESDAY'], myTimeWindows: ['EU_PRIME'] });
    const c = build([t]);
    c.ngOnInit();

    c.toggleTopic(c.topics()[0]);
    c.toggleTag('THURSDAY');

    expect(c.entwurf()?.weekdays).toEqual(['TUESDAY', 'THURSDAY']);
    expect(c.topics()[0].myWeekdays).toEqual(['TUESDAY']);
    expect(c.istTagGewaehlt('THURSDAY')).toBe(true);
  });

  it('fängt eine Bekundung ohne Tag oder ohne Fenster ab, bevor der Aufruf rausgeht', () => {
    // Das Backend weist genau das ab. Ohne die Prüfung hier liefe der Nutzer in
    // eine Fehlermeldung des Servers, die er nicht vorhersehen konnte - und die
    // nicht sagen kann, WARUM beides gebraucht wird.
    const c = build();
    c.ngOnInit();
    c.toggleTopic(c.topics()[0]);

    c.saveInterest(c.topics()[0]);
    expect(academy.saveInterest).not.toHaveBeenCalled();

    c.toggleTag('TUESDAY');
    c.saveInterest(c.topics()[0]);
    expect(academy.saveInterest).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(
      'Wähle mindestens einen Tag und ein Zeitfenster - sonst weiß niemand, wann du kannst.',
    );

    c.toggleFenster('EU_PRIME');
    c.saveInterest(c.topics()[0]);
    expect(academy.saveInterest).toHaveBeenCalledWith(1, {
      weekdays: ['TUESDAY'],
      timeWindows: ['EU_PRIME'],
      note: null,
    });
  });

  it('schreibt nach dem Speichern die eine Zeile um, statt die Liste neu zu holen', () => {
    // Die Antwort trägt die frisch gerechneten Zähler bereits - ein zweiter
    // Aufruf brächte nichts Neues und ließe die Liste flackern.
    const c = build([thema(), thema({ id: 2, title: 'Logi' })]);
    c.ngOnInit();
    c.toggleTopic(c.topics()[0]);
    c.toggleTag('TUESDAY');
    c.toggleFenster('EU_PRIME');
    academy.getTopics.mockClear();

    c.saveInterest(c.topics()[0]);

    expect(academy.getTopics).not.toHaveBeenCalled();
    expect(c.topics()[0].hasMyInterest).toBe(true);
    expect(c.topics()[1].title).toBe('Logi');
    expect(c.meineAnzahl()).toBe(1);
  });

  it('meldet einen fehlgeschlagenen Speicherversuch und lässt die Zeile stehen', () => {
    const c = build();
    c.ngOnInit();
    c.toggleTopic(c.topics()[0]);
    c.toggleTag('TUESDAY');
    c.toggleFenster('EU_PRIME');
    academy.saveInterest.mockReturnValue(fehler());

    c.saveInterest(c.topics()[0]);

    expect(c.topics()[0].hasMyInterest).toBe(false);
    expect(c.savingInterest()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('Das Interesse konnte nicht gespeichert werden.');
  });

  it('zieht ein Interesse erst nach der Rückfrage zurück und lädt danach neu', () => {
    // Neu laden und nicht lokal herunterzählen: mit der Zahl fällt auch die
    // Verteilung, und unterhalb von zwei Bekundungen liefert der Server gar
    // keine mehr. Diese Schwelle im Browser nachzubauen hieße, eine Serverregel
    // zu kopieren, die niemand nachpflegt.
    const c = build([thema({ hasMyInterest: true })]);
    c.ngOnInit();
    confirm.ask.mockResolvedValue(false);

    return c
      .withdrawInterest(c.topics()[0])
      .then(() => {
        expect(academy.withdrawInterest).not.toHaveBeenCalled();
        confirm.ask.mockResolvedValue(true);
        academy.getTopics.mockClear();
        return c.withdrawInterest(c.topics()[0]);
      })
      .then(() => {
        expect(academy.withdrawInterest).toHaveBeenCalledWith(1);
        expect(academy.getTopics).toHaveBeenCalledTimes(1);
      });
  });

  it('bietet das Zurückziehen gar nicht erst an, wo es nichts zurückzuziehen gibt', async () => {
    const c = build([thema({ hasMyInterest: false })]);
    c.ngOnInit();

    await c.withdrawInterest(c.topics()[0]);

    expect(confirm.ask).not.toHaveBeenCalled();
    expect(academy.withdrawInterest).not.toHaveBeenCalled();
  });

  // ================= Filter und Reiter =================

  it('filtert auf die eigenen Themen, ohne eine zweite Liste zu bauen', () => {
    const c = build([thema(), thema({ id: 2, hasMyInterest: true })]);
    c.ngOnInit();

    expect(c.sichtbareThemen().length).toBe(2);
    c.nurMeine.set(true);
    expect(c.sichtbareThemen().map((t) => t.id)).toEqual([2]);
    expect(c.meineAnzahl()).toBe(1);
  });

  it('zeigt den Verwaltungs-Reiter nicht, wer nichts zu verwalten hat', () => {
    const c = build([thema({ canEdit: false })], false);
    c.ngOnInit();

    expect(c.canManage()).toBe(false);
  });

  it('zeigt ihn dem Autorenkreis auch dann, wenn es noch kein einziges Thema gibt', () => {
    // Ohne die Rollenliste bliebe der Reiter bei null Themen aus - und niemand
    // könnte das erste anlegen, weil canEdit nur an einem Thema steht.
    const c = build([], true);
    c.ngOnInit();

    expect(c.canManage()).toBe(true);
  });

  it('holt die Verwaltungsliste erst beim Wechsel auf den Reiter', () => {
    // Die enthält auch die abgeschalteten Themen und verlangt Autorenrechte;
    // beim Seitenaufbau liefe sie für jeden anderen in eine 403.
    const c = build([thema({ canEdit: true })], true);
    c.ngOnInit();
    expect(academy.getAdminTopics).not.toHaveBeenCalled();

    c.setTab('MANAGE');

    expect(academy.getAdminTopics).toHaveBeenCalledTimes(1);
    expect(c.adminTopics().length).toBe(1);
  });

  it('meldet einen Fehlschlag beim Laden, statt still eine leere Seite zu zeigen', () => {
    const c = build();
    academy.getTopics.mockReturnValue(fehler());
    c.ngOnInit();

    expect(c.loading()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('Die Themen konnten nicht geladen werden.');

    academy.getAdminTopics.mockReturnValue(fehler('Nichts da.'));
    c.loadAdminTopics();
    expect(c.loadingAdmin()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('Nichts da.');
  });

  // ================= Der Editor =================

  it('öffnet den Editor für ein neues Thema mit leeren Feldern und aktivem Zustand', () => {
    const c = build([], true);
    c.ngOnInit();

    c.newTopic();

    expect(c.editingTopic()).toEqual({
      id: null,
      title: '',
      summary: '',
      description: '',
      active: true,
      teacherRoleNames: [],
    });
    expect(c.editorMode()).toBe('EDIT');
  });

  it('holt den Lehrplan beim Bearbeiten nach und nimmt ihn danach aus dem Speicher', () => {
    // Die Liste trägt den Lehrplan nicht - ohne den Nachholer stünde der Editor
    // leer da und würde den vorhandenen Text beim Speichern überschreiben.
    const c = build([thema({ canEdit: true })], true);
    c.ngOnInit();

    c.editTopic(c.topics()[0]);
    expect(academy.getTopic).toHaveBeenCalledTimes(1);
    expect(c.editingTopic()?.description).toBe('## Inhalt');

    c.closeModal();
    c.editTopic(c.topics()[0]);
    expect(academy.getTopic).toHaveBeenCalledTimes(1);
  });

  it('öffnet den Editor gar nicht, wenn der Lehrplan nicht zu holen ist', () => {
    // Ein Editor mit leerem Feld würde den vorhandenen Lehrplan beim ersten
    // Speichern löschen, ohne dass jemand ihn je gesehen hat.
    const c = build([thema({ canEdit: true })], true);
    c.ngOnInit();
    academy.getTopic.mockReturnValue(fehler());

    c.editTopic(c.topics()[0]);

    expect(c.editingTopic()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('Der Lehrplan konnte nicht geladen werden.');
  });

  it('benutzt für die Ausbilderrollen dieselbe Umschaltung wie für die Wochentage', () => {
    // Dreimal ausgeschrieben gingen die drei Mehrfachauswahlen beim ersten Umbau
    // auseinander - und die dritte Fassung wäre die, die niemand mehr anfasst.
    const c = build([], true);
    c.ngOnInit();
    c.newTopic();

    c.toggleAusbilderrolle('ROLE_A38');
    expect(c.istAusbilderrolle('ROLE_A38')).toBe(true);
    c.toggleAusbilderrolle('ROLE_A38');
    expect(c.editingTopic()?.teacherRoleNames).toEqual([]);
  });

  it('hält eine eingetragene Rolle in der Auswahl, auch wenn der Katalog sie nicht kennt', () => {
    // Sonst fiele sie beim nächsten Speichern still heraus, und das Thema
    // verlöre seinen Sichtkreis, ohne dass jemand etwas abgewählt hätte.
    const c = build([thema({ canEdit: true, teacherRoleNames: ['ROLE_LEHRER'] })], true);
    c.ngOnInit();

    c.editTopic(c.topics()[0]);

    expect(c.teacherRoleChoices()).toEqual(['ROLE_A38', 'ROLE_LEHRER']);
  });

  it('kommt ohne Rollenkatalog aus, wenn der Server ihn dem Ausbilder verweigert', () => {
    // GET /api/groups/roles verlangt Direktor, CEO oder IT - der Autorenkreis
    // der Academy umfasst zusätzlich A38 und 69. Ein Toast wäre hier ein Fehler,
    // den der Ausbilder nicht beheben könnte.
    const c = build([], true);
    groupService.getRoles.mockReturnValue(fehler());
    c.ngOnInit();

    c.newTopic();

    expect(c.roles()).toEqual([]);
    expect(c.teacherRoleChoices()).toEqual([]);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('holt den Rollenkatalog nur einmal, solange er ankommt', () => {
    const c = build([], true);
    c.ngOnInit();

    c.newTopic();
    c.closeModal();
    c.newTopic();

    expect(groupService.getRoles).toHaveBeenCalledTimes(1);
  });

  it('lässt einen leeren Titel oder eine leere Kurzzeile nicht hinausgehen', () => {
    // Die Kurzzeile ist alles, was auf der eingeklappten Karte steht - ohne sie
    // wäre die Karte bis auf den Titel leer.
    const c = build([], true);
    c.ngOnInit();
    c.newTopic();

    c.saveTopic();
    expect(academy.saveTopic).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('Das Thema braucht einen Titel.');

    c.updateTopic({ title: 'EWar' });
    c.saveTopic();
    expect(academy.saveTopic).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(
      'Die Kurzzeile ist Pflicht - sie ist alles, was auf der Karte steht.',
    );

    c.updateTopic({ summary: 'Dampener und Painter' });
    c.saveTopic();
    expect(academy.saveTopic).toHaveBeenCalledTimes(1);
    expect(c.editingTopic()).toBeNull();
  });

  it('wirft die gespeicherten Lehrpläne weg, wenn ein Thema gespeichert wurde', () => {
    // Sonst gälte der alte Text beim nächsten Aufklappen als "geladen" - und die
    // gerade gespeicherte Änderung wäre unsichtbar.
    const c = build([thema({ canEdit: true })], true);
    c.ngOnInit();
    c.toggleTopic(c.topics()[0]);
    expect(c.lehrplan(c.topics()[0])).toBe('## Inhalt');

    c.newTopic();
    c.updateTopic({ title: 'Logi', summary: 'Reparieren' });
    c.saveTopic();

    expect(c.lehrplan(c.topics()[0])).toBeNull();
  });

  it('meldet einen fehlgeschlagenen Speicherversuch und lässt das Modal offen', () => {
    // Ein zugeklapptes Modal wäre der sichere Weg, die Eingabe zu verlieren.
    const c = build([], true);
    c.ngOnInit();
    c.newTopic();
    c.updateTopic({ title: 'EWar', summary: 'Kurz' });
    academy.saveTopic.mockReturnValue(fehler('Bildquelle nicht erlaubt: boeser-host.example.'));

    c.saveTopic();

    expect(c.editingTopic()).not.toBeNull();
    expect(c.saving()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('Bildquelle nicht erlaubt: boeser-host.example.');
  });

  it('löscht ein Thema erst nach der Rückfrage', () => {
    const c = build([thema({ canEdit: true })], true);
    c.ngOnInit();
    confirm.ask.mockResolvedValue(false);

    return c
      .deleteTopic(c.topics()[0])
      .then(() => {
        expect(academy.deleteTopic).not.toHaveBeenCalled();
        confirm.ask.mockResolvedValue(true);
        return c.deleteTopic(c.topics()[0]);
      })
      .then(() => {
        expect(academy.deleteTopic).toHaveBeenCalledWith(1);
        expect(toast.success).toHaveBeenCalledWith('Thema gelöscht.');
      });
  });

  it('meldet einen fehlgeschlagenen Löschversuch', () => {
    const c = build([thema({ canEdit: true })], true);
    c.ngOnInit();
    academy.deleteTopic.mockReturnValue(fehler());

    return c.deleteTopic(c.topics()[0]).then(() => {
      expect(toast.error).toHaveBeenCalledWith('Das Thema konnte nicht gelöscht werden.');
    });
  });

  it('warnt beim Zeichenzähler kurz vor der Grenze und nicht erst darüber', () => {
    // Darüber schneidet das Feld ohnehin schon ab - eine Warnung käme dann zu
    // spät, um noch etwas zu kürzen.
    const c = build([], true);

    expect(c.istKnapp(179, 200)).toBe(false);
    expect(c.istKnapp(180, 200)).toBe(true);

    c.newTopic();
    expect(c.lehrplanLaenge()).toBe(0);
    c.updateTopic({ description: '## Inhalt' });
    expect(c.lehrplanLaenge()).toBe(9);
  });
});
