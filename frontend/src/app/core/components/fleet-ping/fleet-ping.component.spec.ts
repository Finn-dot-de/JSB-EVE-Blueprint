import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ERWAEHNUNGEN, FleetPingComponent } from './fleet-ping.component';
import {
  FleetPingService,
  PingResponseDto,
  PingRolleDto,
} from '../../services/fleet-ping.service';
import { ReadinessService } from '../../services/readiness.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { enthaeltErwaehnung, entschaerfe } from '../../shared/fleet-ping-nachricht.util';

/**
 * Der Flotten-Ping-Reiter.
 *
 * <p>Geprüft wird der Zustand und nicht das Aussehen: kein Fixture, keine
 * DOM-Abfrage. Die eine Ausnahme wäre auch keine - der Vorschautext ist kein
 * Aussehen, sondern der Inhalt, der gleich tausend Leute erreicht. Er wird
 * deshalb wörtlich verglichen und nicht auf "enthält irgendwo Roam" geprüft.</p>
 *
 * <p>Nicht geprüft wird, ob die Oberfläche Rechte durchsetzt - das tut
 * ausschließlich der `FleetPingService` des Servers. Geprüft wird, dass sie
 * nichts anbietet, was der Server ohnehin ablehnen würde, und dass zwischen
 * Klick und Absenden die Rückfrage steht.</p>
 */
describe('FleetPingComponent', () => {
  const ICH = 100;

  const antwort = (over: Partial<PingResponseDto> = {}): PingResponseDto => ({
    id: 42,
    fcCharacterId: ICH,
    fcCharacterName: 'Bob FC',
    fleetType: 'Roam',
    doctrine: 'Armor',
    formupLocation: 'Jita IV - Moon 4',
    formupTime: null,
    comms: 'Discord',
    srpCovered: null,
    notes: null,
    erwaehnung: 'STILL',
    erwaehnungRolleId: null,
    zustand: 'GEPOSTET',
    discordMessageId: '999',
    createdAt: '2026-09-03T18:00:00Z',
    updatedAt: '2026-09-03T18:00:00Z',
    cancelledAt: null,
    cancelReason: null,
    ...over,
  });

  /** Ein Fehler, wie ihn der Interceptor durchreicht - mit und ohne Meldung. */
  const fehler = (message?: string) =>
    throwError(() => (message ? { error: { message } } : { error: null }));

  /** Die wählbaren Rollen, wie sie der Server liefert. */
  const ROLLEN: PingRolleDto[] = [
    { discordRoleId: '111', authRole: 'ROLE_CAP_AZUBI_PROGRAMM', name: 'Cap Azubi', vorbelegt: false },
    { discordRoleId: '222', authRole: 'ROLE_MARAUDERS_ASSOCIATED', name: 'Marauders', vorbelegt: true },
  ];

  let ping: {
    status: ReturnType<typeof vi.fn>;
    rollen: ReturnType<typeof vi.fn>;
    letzte: ReturnType<typeof vi.fn>;
    senden: ReturnType<typeof vi.fn>;
    bearbeiten: ReturnType<typeof vi.fn>;
    absagen: ReturnType<typeof vi.fn>;
  };
  let readiness: { doctrines: ReturnType<typeof vi.fn> };
  let toast: {
    success: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
    info: ReturnType<typeof vi.fn>;
  };
  let confirm: { ask: ReturnType<typeof vi.fn> };

  function build(options: {
    verfuegbar?: boolean;
    rolleKonfiguriert?: boolean;
    liste?: PingResponseDto[];
    rollen?: PingRolleDto[];
    rollenFehler?: boolean;
  } = {}) {
    ping = {
      status: vi.fn().mockReturnValue(of({
        verfuegbar: options.verfuegbar ?? true,
        rolleKonfiguriert: options.rolleKonfiguriert ?? true,
        hinweis: options.verfuegbar === false ? 'Kein Kanal hinterlegt.' : null,
      })),
      rollen: vi.fn().mockReturnValue(
        options.rollenFehler ? fehler('Discord antwortet nicht.') : of(options.rollen ?? ROLLEN)),
      letzte: vi.fn().mockReturnValue(of(options.liste ?? [])),
      senden: vi.fn().mockReturnValue(of(antwort())),
      bearbeiten: vi.fn().mockReturnValue(of(antwort({ zustand: 'GEAENDERT' }))),
      absagen: vi.fn().mockReturnValue(of(antwort({ zustand: 'ABGESAGT' }))),
    };
    readiness = { doctrines: vi.fn().mockReturnValue(of(['Armor', 'Shield'])) };
    toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    confirm = { ask: vi.fn().mockResolvedValue(true) };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: FleetPingService, useValue: ping },
        { provide: ReadinessService, useValue: readiness },
        {
          provide: AuthService,
          useValue: { currentUser: () => ({ characterId: ICH, characterName: 'Bob FC' }) },
        },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    const komponente = TestBed.runInInjectionContext(() => new FleetPingComponent());
    komponente.ngOnInit();
    return komponente;
  }

  beforeEach(() => vi.clearAllMocks());

  // ================= Die Vorschau =================

  describe('Vorschau', () => {
    it('zeigt wörtlich den Text, der im Kanal landet', () => {
      // Der Kern des Features. Ein Rundruf, den man vorher nicht lesen kann,
      // ist einer, den man bereut - und "enthält Roam" wäre keine Vorschau.
      const c = build();
      c.fleetType.set('Home Defense');
      c.formupLocation.set('Home');
      c.doctrine.set('Armor');
      c.comms.set('Discord');

      expect(c.vorschauGanz()).toBe(
        '**FLOTTEN-PING - Home Defense**\n'
        + '**Doktrin:** Armor\n'
        + '**Treffpunkt:** Home\n'
        + '**Formup:** **JETZT**\n'
        + '**Comms:** Discord\n'
        + '**SRP:** nicht angegeben\n'
        + '*FC: Bob FC*');
    });

    it('setzt @here vor den Text und lässt bei "still" nichts davor stehen', () => {
      // "still" und "@here" dürfen nicht gleich aussehen - hier ist der
      // Unterschied im Text selbst, nicht nur in einer Farbe daneben.
      const c = build();
      c.formupLocation.set('Home');

      expect(c.vorschauGanz().startsWith('**FLOTTEN-PING')).toBe(true);

      c.waehleErwaehnung('HIER');
      expect(c.vorschauGanz().startsWith('@here **FLOTTEN-PING')).toBe(true);
    });

    it('schreibt die Formup-Zeit als EVE-Zeit, ohne sie in die Browserzone zu drehen', () => {
      // EVE-Zeit IST UTC. Läse der Browser "19:00" als seine eigene Zeit,
      // stünde im Kanal je nach Standort des FC eine andere Uhrzeit - und die
      // Flotte formte sich zwei Stunden daneben.
      const c = build();
      c.formupLocation.set('Home');
      c.sofort.set(false);
      c.formupDatum.set('2026-09-03');
      c.formupUhrzeit.set('19:00');

      const epoch = Math.floor(Date.UTC(2026, 8, 3, 19, 0) / 1000);
      expect(c.formupIso()).toBe('2026-09-03T19:00:00.000Z');
      expect(c.vorschauGanz()).toContain(`**Formup:** 2026-09-03 19:00 EVE (<t:${epoch}:R>)`);
    });

    it('nimmt "jetzt" als eigene Aussage und nicht als fehlende Uhrzeit', () => {
      const c = build();
      c.formupLocation.set('Home');

      expect(c.formupIso()).toBeNull();
      expect(c.vorschauGanz()).toContain('**Formup:** **JETZT**');
    });

    it('lässt "nicht angegeben" bei SRP stehen, statt daraus ein Nein zu machen', () => {
      // Daran hängt, ob jemand den teuren Rumpf mitbringt.
      const c = build();
      c.formupLocation.set('Home');
      expect(c.vorschauGanz()).toContain('**SRP:** nicht angegeben');

      c.srpWahl.set('NEIN');
      expect(c.vorschauGanz()).toContain('**SRP:** nein');
    });

    it('entschärft ein @everyone im Freitext und sagt, dass es entschärft wurde', () => {
      // Bei der Auswahl "@here" muss Discord die Gattung "everyone" erlauben -
      // sie deckt @everyone mit ab. Ohne die Entschärfung wäre "@here" nur eine
      // Untergrenze, von der aus man sich über das Notizfeld hocharbeitet.
      const c = build();
      c.formupLocation.set('Home');
      c.notes.set('kein @everyone Spam bitte');

      // Der Zero-Width-Space wird über den Codepunkt gebildet und nicht in die
      // Quelldatei getippt: ein unsichtbares Zeichen überlebt kein falsch
      // geratenes Encoding, und sein Verschwinden fiele hier niemandem auf -
      // der Test wäre grün, weil er dasselbe kaputte Zeichen erwartet.
      const unsichtbar = String.fromCharCode(0x200b);
      expect(c.entschaerftEtwas()).toBe(true);
      expect(c.vorschauGanz()).not.toContain('@everyone');
      expect(c.vorschauGanz()).toContain('@' + unsichtbar + 'everyone');
    });
  });

  // ================= Der Text selbst =================

  describe('Nachrichtenbau', () => {
    it('macht aus einem fehlenden Text ein "-" und aus null keinen Absturz', () => {
      // Der Zwilling im Server behandelt null und Leerstring gleich. Liefe das
      // hier auseinander, stünde in der Vorschau ein anderer Ping als im Kanal.
      expect(entschaerfe(null)).toBe('');
      expect(entschaerfe(undefined)).toBe('');
      expect(enthaeltErwaehnung(null, undefined)).toBe(false);
      expect(enthaeltErwaehnung(null, 'ruf mal <@123> an')).toBe(true);
    });

    it('schreibt SRP als "ja", wenn es gedeckt ist', () => {
      const c = build();
      c.formupLocation.set('Home');
      c.srpWahl.set('JA');

      expect(c.vorschauGanz()).toContain('**SRP:** ja');
    });

    it('lässt leere Felder als Strich stehen, statt die Zeile wegzulassen', () => {
      // Eine fehlende Zeile läse sich als "danach hat niemand gefragt". Der
      // Strich sagt: gefragt wurde, gesagt wurde nichts.
      const c = build();
      c.formupLocation.set('Home');
      c.comms.set('');
      c.doctrine.set('   ');

      expect(c.vorschauGanz()).toContain('**Doktrin:** -');
      expect(c.vorschauGanz()).toContain('**Comms:** -');
    });
  });

  // ================= Absenden =================

  describe('Absenden', () => {
    function gefuellt() {
      const c = build();
      c.formularOeffnen();
      c.fleetType.set('Roam');
      c.formupLocation.set('Jita IV - Moon 4');
      return c;
    }

    it('sendet nicht, wenn der Treffpunkt fehlt - und fragt gar nicht erst', async () => {
      // Eine Rückfrage über einen Ping, der ohnehin scheitert, gewöhnt einen FC
      // daran, sie wegzuklicken.
      const c = build();
      c.formularOeffnen();
      c.formupLocation.set('   ');

      await c.absenden();

      expect(confirm.ask).not.toHaveBeenCalled();
      expect(ping.senden).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalled();
    });

    it('lässt bei "zu einer Uhrzeit" nicht absenden, solange die Uhrzeit fehlt', async () => {
      // Ein Ping mit angekündigter Uhrzeit, aber ohne Uhrzeit, wäre ein "JETZT"
      // - also das Gegenteil dessen, was der FC gerade eingestellt hat.
      const c = gefuellt();
      c.sofort.set(false);
      c.formupUhrzeit.set('');

      expect(c.eingabenVollstaendig()).toBe(false);
      await c.absenden();
      expect(confirm.ask).not.toHaveBeenCalled();

      c.formupUhrzeit.set('19:00');
      expect(c.eingabenVollstaendig()).toBe(true);
    });

    it('nennt in der Rückfrage die geplante Uhrzeit als EVE-Zeit', async () => {
      // In der Rückfrage steht dieselbe Zeit wie im Kanal und nicht die des
      // Browsers - sonst bestätigt der FC etwas anderes, als er absendet.
      const c = gefuellt();
      c.sofort.set(false);
      c.formupDatum.set('2026-09-03');
      c.formupUhrzeit.set('19:00');

      await c.absenden();

      expect(confirm.ask.mock.calls[0][1]).toContain('2026-09-03 19:00 EVE');
    });

    it('nennt in der Rückfrage die Folge und nicht nur die Handlung', async () => {
      // Eine Rückfrage, die verschweigt, wen es weckt, ist eine Formalität.
      const c = gefuellt();
      c.waehleErwaehnung('HIER');

      await c.absenden();

      const [, text] = confirm.ask.mock.calls[0];
      expect(text).toContain('Jita IV - Moon 4');
      expect(text).toContain(ERWAEHNUNGEN.find((e) => e.wert === 'HIER')!.folge);
    });

    it('sendet nichts, wenn die Rückfrage abgebrochen wird', async () => {
      const c = gefuellt();
      confirm.ask.mockResolvedValue(false);

      await c.absenden();

      expect(ping.senden).not.toHaveBeenCalled();
      // Das Formular bleibt offen: Wer abbricht, will meistens noch etwas
      // ändern und nicht alles neu tippen.
      expect(c.formularOffen()).toBe(true);
    });

    it('sendet nach dem Bestätigen und lädt die Liste neu', async () => {
      const c = gefuellt();
      c.doctrine.set('Armor');
      c.srpWahl.set('JA');
      c.waehleErwaehnung('HIER');
      ping.letzte.mockClear();

      await c.absenden();

      expect(ping.senden).toHaveBeenCalledWith({
        fleetType: 'Roam',
        doctrine: 'Armor',
        formupLocation: 'Jita IV - Moon 4',
        formupTime: null,
        comms: 'Discord',
        srpCovered: true,
        notes: null,
        erwaehnung: 'HIER',
        // Ausdrücklich null und nicht weggelassen: Bei jeder Lautstärke außer
        // "Rolle" darf keine Rollenkennung mitgehen.
        rolleId: null,
      });
      // Neu laden und nicht die Antwort vorne anhängen: die Liste ist die
      // Rechenschaft, und die soll aus dem Server kommen.
      expect(ping.letzte).toHaveBeenCalledTimes(1);
      expect(c.formularOffen()).toBe(false);
      expect(toast.success).toHaveBeenCalled();
    });

    it('zeigt die Meldung des Servers und lässt das Formular ausgefüllt stehen', async () => {
      // 429 aus der Wartezeit, 503 aus dem fehlenden Kanal: Der Ping ist NICHT
      // hinausgegangen, und der zweite Versuch darf kein zweites Tippen sein.
      const c = gefuellt();
      ping.senden.mockReturnValue(fehler('Zu schnell hintereinander.'));

      await c.absenden();

      expect(toast.error).toHaveBeenCalledWith('Zu schnell hintereinander.');
      expect(c.formularOffen()).toBe(true);
      expect(c.formupLocation()).toBe('Jita IV - Moon 4');
      expect(c.sendet()).toBe(false);
    });

    it('fällt auf einen eigenen Satz zurück, wenn der Server keinen mitschickt', async () => {
      const c = gefuellt();
      ping.senden.mockReturnValue(fehler());

      await c.absenden();

      expect(toast.error).toHaveBeenCalledWith('Der Ping konnte nicht abgesetzt werden.');
    });
  });

  // ================= Bearbeiten =================

  describe('Bearbeiten', () => {
    it('füllt das Formular aus dem Ping - die Uhrzeit als EVE-Zeit', () => {
      const c = build();

      c.bearbeitenStarten(antwort({ formupTime: '2026-09-03T19:30:00Z', srpCovered: false }));

      expect(c.bearbeiteId()).toBe(42);
      expect(c.sofort()).toBe(false);
      expect(c.formupDatum()).toBe('2026-09-03');
      expect(c.formupUhrzeit()).toBe('19:30');
      expect(c.srpWahl()).toBe('NEIN');
      expect(c.formularOffen()).toBe(true);
    });

    it('macht aus fehlenden Angaben leere Felder und nicht das Wort "null"', () => {
      const c = build();

      c.bearbeitenStarten(antwort({
        doctrine: null, comms: null, notes: null, srpCovered: true, formupTime: null,
      }));

      expect(c.doctrine()).toBe('');
      expect(c.comms()).toBe('');
      expect(c.notes()).toBe('');
      expect(c.srpWahl()).toBe('JA');
      expect(c.sofort()).toBe(true);
    });

    it('schließt das Formular, ohne die Bearbeitung als nächsten Ping stehenzulassen', () => {
      // Ohne dieses Zurücksetzen wäre der nächste Klick auf "Flotte pingen" ein
      // PUT auf den zuletzt bearbeiteten Ping - eine fremde Ankündigung würde
      // überschrieben, statt eine neue zu entstehen.
      const c = build();
      c.bearbeitenStarten(antwort());

      c.formularSchliessen();

      expect(c.bearbeiteId()).toBeNull();
      expect(c.formularOffen()).toBe(false);
    });

    it('ändert mit PUT und sagt in der Rückfrage, dass niemand erneut geweckt wird', async () => {
      // Die Grenze des Verfahrens: ein PATCH benachrichtigt in Discord
      // niemanden. Wer das nicht weiß, hält die Korrektur für zugestellt.
      const c = build();
      c.bearbeitenStarten(antwort());

      await c.absenden();

      expect(confirm.ask.mock.calls[0][1]).toContain('benachrichtigt dabei niemanden erneut');
      expect(ping.bearbeiten).toHaveBeenCalledWith(42, expect.objectContaining({
        fleetType: 'Roam',
        formupLocation: 'Jita IV - Moon 4',
      }));
      expect(ping.senden).not.toHaveBeenCalled();
    });
  });

  // ================= Absagen =================

  describe('Absagen', () => {
    it('fragt zurück und nennt dabei, was mit der Nachricht im Kanal geschieht', async () => {
      const c = build();

      await c.absagen(antwort());

      const [titel, text] = confirm.ask.mock.calls[0];
      expect(titel).toBe('Flotte absagen?');
      expect(text).toContain('umgeschrieben');
      expect(text).toContain('durchgestrichen');
      expect(ping.absagen).toHaveBeenCalledWith(42, null);
    });

    it('sagt nichts ab, wenn die Rückfrage abgebrochen wird', async () => {
      const c = build();
      confirm.ask.mockResolvedValue(false);

      await c.absagen(antwort());

      expect(ping.absagen).not.toHaveBeenCalled();
    });

    it('nimmt den eingetippten Grund mit und räumt das Feld danach auf', async () => {
      const c = build();
      c.absageGrund.set('  Ziel ist weg  ');

      await c.absagen(antwort());

      expect(ping.absagen).toHaveBeenCalledWith(42, 'Ziel ist weg');
      expect(c.absageGrund()).toBe('');
    });

    it('zeigt die Meldung des Servers, wenn die Absage scheitert', async () => {
      const c = build();
      ping.absagen.mockReturnValue(fehler('Dieser Ping ist bereits abgesagt.'));

      await c.absagen(antwort());

      expect(toast.error).toHaveBeenCalledWith('Dieser Ping ist bereits abgesagt.');
    });
  });

  // ================= Liste und Einrichtung =================

  describe('Liste', () => {
    it('beschriftet Zustand und Formup in Worten und nicht mit dem Aufzählungsnamen', () => {
      // "GEAENDERT" in einer Rechenschaftsliste ist eine Datenbankspalte, keine
      // Auskunft. Und "sofort" ist die Aussage, die im Kanal stand - nicht die
      // Uhrzeit, zu der der Ping abgesetzt wurde.
      const c = build();

      expect(c.zustandsText('GEPOSTET')).toBe('im Kanal');
      expect(c.zustandsText('GEAENDERT')).toBe('geändert');
      expect(c.zustandsText('ABGESAGT')).toBe('abgesagt');
      expect(c.formupText(antwort())).toBe('sofort');
      expect(c.formupText(antwort({ formupTime: '2026-09-03T19:00:00Z' })))
        .toBe('2026-09-03 19:00 EVE');
      expect(c.erwaehnungsWahl('HIER').titel).toBe('@here');
    });

    it('hebt den eigenen noch stehenden Ping hervor, damit das Absagen auffindbar bleibt', () => {
      // Die dringlichste Handlung darf nicht am Ende einer Zeile in einer
      // Liste von fünfzig stehen.
      const c = build({
        liste: [
          antwort({ id: 9, fcCharacterId: 555, fcCharacterName: 'Fremd' }),
          antwort({ id: 8 }),
        ],
      });

      expect(c.eigenerOffenerPing()?.id).toBe(8);
    });

    it('hebt einen abgesagten Ping nicht mehr hervor', () => {
      const c = build({ liste: [antwort({ zustand: 'ABGESAGT' })] });

      expect(c.eigenerOffenerPing()).toBeNull();
    });

    it('bietet an fremden Pings keine Knöpfe an', () => {
      // Unter der Nachricht steht der Name des FC. Ein Kollege, der sie
      // umschreibt, ändert, was jemand anders gesagt hat.
      const c = build();

      expect(c.darfAendern(antwort())).toBe(true);
      expect(c.darfAendern(antwort({ fcCharacterId: 555 }))).toBe(false);
      expect(c.darfAendern(antwort({ zustand: 'ABGESAGT' }))).toBe(false);
    });

    it('hält ohne angemeldeten Charakter jeden Ping für fremd', () => {
      // Der Fall entsteht in der Lücke zwischen Seitenaufbau und `/auth/me`.
      // Er darf nicht in die freizügige Richtung ausfallen: lieber kurz kein
      // Knopf als einer, der an einer fremden Ankündigung hängt.
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          {
            provide: FleetPingService,
            useValue: {
              status: vi.fn().mockReturnValue(
                of({ verfuegbar: true, rolleKonfiguriert: true, hinweis: null })),
              letzte: vi.fn().mockReturnValue(of([antwort()])),
            },
          },
          { provide: ReadinessService, useValue: { doctrines: vi.fn().mockReturnValue(of([])) } },
          { provide: AuthService, useValue: { currentUser: () => null } },
          { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
          { provide: ConfirmService, useValue: { ask: vi.fn() } },
        ],
      });
      const c = TestBed.runInInjectionContext(() => new FleetPingComponent());
      c.ngOnInit();

      expect(c.fcName()).toBe('');
      expect(c.eigenerOffenerPing()).toBeNull();
      expect(c.darfAendern(antwort())).toBe(false);
    });

    it('kommt ohne die gespeicherten Doktrinen aus, wenn deren Abruf scheitert', () => {
      // Die Vorschlagsliste ist eine Bequemlichkeit. Sie darf den Ping nicht
      // aufhalten - das Feld ist ohnehin ein Freitextfeld.
      const c = build();
      readiness.doctrines.mockReturnValue(fehler('kaputt'));

      c.ngOnInit();

      expect(c.doktrinen()).toEqual([]);
      expect(toast.error).not.toHaveBeenCalled();
    });

    it('behandelt einen fehlgeschlagenen Statusabruf als "nicht eingerichtet"', () => {
      // Wieder die leise Richtung: Wer nicht weiß, ob ein Kanal da ist, bietet
      // keinen Knopf an, der eine Corporation wecken könnte.
      const c = build();
      ping.status.mockReturnValue(fehler());

      c.ngOnInit();

      expect(c.verfuegbar()).toBe(false);
      expect(c.rolleNutzbar()).toBe(false);
    });

    it('meldet einen fehlgeschlagenen Abruf, statt eine leere Liste zu zeigen', () => {
      // Eine leere Liste läse sich als "es wurde nichts gepingt" - das ist die
      // Falschaussage, die eine Rechenschaftsliste nicht machen darf.
      const c = build();
      ping.letzte.mockReturnValue(fehler('Serverfehler'));

      c.laden();

      expect(toast.error).toHaveBeenCalledWith('Serverfehler');
      expect(c.laedt()).toBe(false);
    });
  });

  describe('Einrichtung', () => {
    it('meldet die fehlende Einrichtung, statt ein Formular anzubieten, das scheitert', () => {
      const c = build({ verfuegbar: false });

      expect(c.verfuegbar()).toBe(false);
      expect(c.status()?.hinweis).toBe('Kein Kanal hinterlegt.');
    });

    it('lässt die Ping-Rolle nicht wählen, wenn keine hinterlegt ist', () => {
      // Eine Auswahl, die nachweislich nichts tut, ist schlimmer als keine: der
      // FC hielte den Ping für abgesetzt, und niemand käme. Ohne `rolleNutzbar`
      // fehlte der Oberfläche die Angabe, an der die Stufe sichtbar gesperrt
      // und der Grund daneben geschrieben wird - übrig bliebe ein Knopf, der
      // stumm nichts tut.
      const c = build({ rolleKonfiguriert: false });

      expect(c.rolleNutzbar()).toBe(false);

      c.waehleErwaehnung('ROLLE');

      expect(c.erwaehnung()).toBe('STILL');
      // Und die Meldung nennt die Stelle, an der es sich beheben lässt -
      // sonst sucht der FC unter Zeitdruck an der falschen.
      expect(toast.info).toHaveBeenCalledWith(expect.stringContaining('/admin/discord'));
      // Der gesperrte Zustand darf nichts abrufen: der Abruf kostet im Server
      // einen Aufruf zu Discord und hätte hier nichts zu liefern.
      expect(ping.rollen).not.toHaveBeenCalled();
    });

    it('erlaubt die Ping-Rolle, sobald eine hinterlegt ist', () => {
      const c = build({ rolleKonfiguriert: true });

      c.waehleErwaehnung('ROLLE');

      expect(c.erwaehnung()).toBe('ROLLE');
      expect(c.vorschauGanz().startsWith('@Marauders **FLOTTEN-PING')).toBe(true);
    });
  });

  // ================= Welche Rolle =================

  describe('Rollenauswahl', () => {
    it('holt die Rollen erst, wenn jemand sie wirklich braucht', () => {
      // Der Abruf kostet im Server einen Aufruf zu Discord. Ihn beim Öffnen des
      // Reiters zu machen hieße, ihn für jeden stillen Ping zu bezahlen.
      const c = build();
      expect(ping.rollen).not.toHaveBeenCalled();

      c.waehleErwaehnung('ROLLE');

      expect(ping.rollen).toHaveBeenCalledTimes(1);
      expect(c.rollen()).toHaveLength(2);

      // Und kein zweites Mal, wenn der FC zwischen den Stufen hin und her klickt.
      c.waehleErwaehnung('STILL');
      c.waehleErwaehnung('ROLLE');
      expect(ping.rollen).toHaveBeenCalledTimes(1);
    });

    it('belegt die im Server vorbelegte Rolle vor und nicht die erste beste', () => {
      // Ein leeres Auswahlfeld sieht aus wie eine getroffene Wahl und ist keine.
      const c = build();

      c.waehleErwaehnung('ROLLE');

      expect(c.rolleId()).toBe('222');
      expect(c.gewaehlteRolle()?.name).toBe('Marauders');
    });

    it('schickt die gewählte Rolle mit - und bei jeder anderen Lautstärke keine', async () => {
      const c = build();
      c.formupLocation.set('Home');
      c.waehleErwaehnung('ROLLE');
      c.waehleRolle('111');

      await c.absenden();

      expect(ping.senden).toHaveBeenCalledWith(expect.objectContaining({
        erwaehnung: 'ROLLE',
        rolleId: '111',
      }));

      // Bei "still" darf keine Kennung mitgehen: eine Angabe, die nichts
      // bedeutet, bedeutet beim nächsten Umbau plötzlich etwas.
      c.waehleErwaehnung('STILL');
      await c.absenden();
      expect(ping.senden).toHaveBeenLastCalledWith(expect.objectContaining({
        erwaehnung: 'STILL',
        rolleId: null,
      }));
    });

    it('sendet gar nicht erst, solange keine Rolle gewählt ist', async () => {
      // Der Server weist einen Rollen-Ping ohne bekannte Rolle ab, statt ihn
      // still leise zu machen. Das Formular kennt diesen Ausgang schon vorher -
      // und ein Knopf, der zuverlässig in eine Fehlermeldung führt, ist einer,
      // den man drückt, ohne hinzusehen.
      const c = build({ rollen: [] });
      c.formupLocation.set('Home');
      c.waehleErwaehnung('ROLLE');

      expect(c.rolleId()).toBeNull();
      expect(c.eingabenVollstaendig()).toBe(false);

      await c.absenden();

      expect(ping.senden).not.toHaveBeenCalled();
      expect(confirm.ask).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalled();
    });

    it('meldet einen fehlgeschlagenen Abruf, statt ein leeres Feld anzubieten', () => {
      const c = build({ rollenFehler: true });

      c.waehleErwaehnung('ROLLE');

      expect(c.rollen()).toHaveLength(0);
      expect(c.rollenLaden()).toBe(false);
      expect(toast.error).toHaveBeenCalledWith('Discord antwortet nicht.');
    });

    it('nennt in der Rückfrage den Namen der Rolle und nicht ihre Kennung', async () => {
      // Die Rückfrage ist die letzte Stelle, an der einem FC auffällt, dass er
      // die falsche Gruppe erwischt hat. "Die gewählte Rolle" fällt niemandem auf.
      const c = build();
      c.formupLocation.set('Home');
      c.waehleErwaehnung('ROLLE');
      c.waehleRolle('111');

      await c.absenden();

      expect(confirm.ask.mock.calls[0][1]).toContain('Cap Azubi');
    });

    it('schreibt den Namen der gewählten Rolle in die Vorschau', () => {
      // Der Kern dieser Aufgabe. Ohne diese Zeile stünde über dem Text weiter
      // "@Ping-Rolle" - ein Platzhalter, der stimmte, solange es genau eine
      // Rolle gab. Bei mehreren ist er die einzige Stelle der Vorschau, an der
      // nicht steht, wen es trifft, und ein FC bestätigt einen Rundruf an eine
      // Gruppe, die er nirgends gelesen hat.
      const c = build();
      c.formupLocation.set('Home');
      c.waehleErwaehnung('ROLLE');
      c.waehleRolle('111');

      expect(c.vorschauGanz().startsWith('@Cap Azubi **FLOTTEN-PING')).toBe(true);
      expect(c.vorschauGanz()).not.toContain('@Ping-Rolle');
      // Auch die Beschriftung über der Vorschau nennt die Gruppe und nicht die
      // Stufe: die Stufe hat der FC eben selbst angeklickt.
      expect(c.erwaehnungsChip()).toBe('Cap Azubi');

      // Und sie wandert mit, wenn er die Auswahl ändert - eine Vorschau, die
      // auf der ersten Wahl stehenbliebe, wäre schlimmer als gar keine.
      c.waehleRolle('222');
      expect(c.vorschauGanz().startsWith('@Marauders **FLOTTEN-PING')).toBe(true);
    });

    it('lässt den Platzhalter stehen, solange keine Rolle gewählt ist', () => {
      // Der Platzhalter ist hier kein Ersatz für einen Namen, sondern die
      // Aussage, dass noch keiner feststeht. Ihn durch eine leere Zeile zu
      // ersetzen sähe aus wie ein stiller Ping - und genau das ist es nicht.
      const c = build({ rollen: [] });
      c.formupLocation.set('Home');
      c.waehleErwaehnung('ROLLE');

      expect(c.gewaehlteRolle()).toBeNull();
      expect(c.vorschauGanz().startsWith('@Ping-Rolle **FLOTTEN-PING')).toBe(true);
      expect(c.erwaehnungsChip()).toBe('Ping-Rolle');
      // Und in diesem Zustand ist das Absenden gesperrt - der Platzhalter geht
      // nirgends hinaus.
      expect(c.eingabenVollstaendig()).toBe(false);
    });

    it('verwirft die Rolle beim Wechsel der Stufe, statt sie heimlich mitzuschicken', async () => {
      // Ohne dieses Wegräumen bliebe die Kennung im Formular stehen, während
      // "@here" gewählt ist: unsichtbar, weil die Auswahl dann gar nicht mehr
      // angezeigt wird. Sie hinge dann an einer einzigen Zeile in `alsDto` -
      // und der nächste Weg, der einen Befehl baut, hätte sie mitgeschickt.
      const c = build();
      c.formupLocation.set('Home');
      c.waehleErwaehnung('ROLLE');
      c.waehleRolle('111');
      expect(c.rolleId()).toBe('111');

      c.waehleErwaehnung('HIER');

      expect(c.rolleId()).toBeNull();
      expect(c.gewaehlteRolle()).toBeNull();
      // Und in der Vorschau steht wieder @here und nicht der Rollenname.
      expect(c.vorschauGanz().startsWith('@here **FLOTTEN-PING')).toBe(true);

      await c.absenden();
      expect(ping.senden).toHaveBeenCalledWith(expect.objectContaining({
        erwaehnung: 'HIER',
        rolleId: null,
      }));

      // Zurück auf "Rolle" belegt wieder vor, statt den FC vor ein leeres Feld
      // zu setzen - und holt die Liste dafür kein zweites Mal.
      c.waehleErwaehnung('ROLLE');
      expect(c.rolleId()).toBe('222');
      expect(ping.rollen).toHaveBeenCalledTimes(1);
    });

    it('übernimmt beim Bearbeiten die Rolle des bestehenden Pings', () => {
      // Ohne diese Übernahme schriebe eine bloße Ortskorrektur den Ping
      // stillschweigend auf eine andere Gruppe um.
      const c = build();

      c.bearbeitenStarten(antwort({ erwaehnung: 'ROLLE', erwaehnungRolleId: '111' }));

      expect(c.rolleId()).toBe('111');
      expect(ping.rollen).toHaveBeenCalled();
    });
  });
});
