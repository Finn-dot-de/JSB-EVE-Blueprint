import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import {
  FleetPingService,
  PingRequestDto,
  PingResponseDto,
  PingRolleDto,
  PingStatusDto,
} from '../../services/fleet-ping.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { ReadinessService } from '../../services/readiness.service';
import {
  PingErwaehnung,
  enthaeltErwaehnung,
  eveZeit,
  pingNachricht,
} from '../../shared/fleet-ping-nachricht.util';

/**
 * Die gängigen Flottenarten als Knopf statt als Freitext.
 *
 * <p>Der Maßstab dieses Reiters ist die Minute: eine Flotte pingt man, wenn es
 * schnell gehen muss. Die Flottenart ist die einzige Pflichtangabe, die
 * praktisch immer aus derselben Handvoll Wörter besteht - sie zu tippen kostet
 * Sekunden, sie zu klicken kostet keine. Der Freitext daneben bleibt trotzdem,
 * weil kein FC sich von einer Liste vorschreiben lässt, was er fliegt.</p>
 */
export const FLOTTENARTEN = [
  'Roam',
  'Home Defense',
  'Strat Op',
  'CTA',
  'Mining Op',
  'Ratting Fleet',
  'Structure Bash',
  'Training Fleet',
];

/** Was eine Erwähnung tatsächlich anrichtet - in Worten, für Formular und Rückfrage. */
export interface ErwaehnungsWahl {
  wert: PingErwaehnung;
  /** Wie es im Kanal aussieht. Bei STILL steht dort nichts. */
  marke: string;
  titel: string;
  kurz: string;
  /**
   * Der Satz, der in der Rückfrage steht.
   *
   * Er nennt die Folge und nicht die Handlung: "willst du wirklich" ist eine
   * Formalität, "das weckt jeden, der online ist" ist eine Auskunft.
   */
  folge: string;
}

export const ERWAEHNUNGEN: ErwaehnungsWahl[] = [
  {
    wert: 'STILL',
    marke: '',
    titel: 'Still',
    kurz: 'kein Ton',
    folge:
      'Die Ankündigung steht im Kanal, aber niemand wird benachrichtigt - '
      + 'gesehen wird sie nur von dem, der ohnehin hineinschaut.',
  },
  {
    wert: 'HIER',
    marke: '@here',
    titel: '@here',
    kurz: 'alle, die gerade online sind',
    folge:
      '@here benachrichtigt JEDEN, der gerade online ist, mitten in dem, was er tut. '
      + 'Das lässt sich nicht zurücknehmen.',
  },
  {
    wert: 'JEDER',
    marke: '@everyone',
    titel: '@everyone',
    // Der Unterschied zu @here, und der einzige, auf den es ankommt: hier
    // klingelt es auch bei denen, die gerade nicht am Rechner sitzen.
    kurz: 'jeden - auch, wer gerade offline ist',
    folge:
      '@everyone benachrichtigt JEDEN!!!!!! auch leute die Offline sind!!!',
  },
  {
    wert: 'ROLLE',
    marke: '@Ping-Rolle',
    titel: 'Ping-Rolle',
    kurz: 'alle, die die gewählte Rolle tragen',
    folge:
      'Alle, die die gewählte Rolle tragen, bekommen eine Benachrichtigung - '
      + 'auch die, die gerade nicht online sind.',
  },
];

type SrpWahl = 'JA' | 'NEIN' | 'OFFEN';

/**
 * Flotten-Pings: absetzen, korrigieren, absagen.
 *
 * <h2>Warum die Vorschau kein Extra ist</h2>
 * <p>Ein Rundruf ist die einzige Handlung dieses Werkzeugs, die tausend Leute
 * gleichzeitig erreicht und sich nicht zurücknehmen lässt. Was im Kanal steht,
 * steht deshalb hier wörtlich, bevor der Knopf überhaupt anklickbar wird - und
 * die Rückfrage danach nennt nicht die Handlung, sondern die Folge.</p>
 *
 * <p>Der Text der Vorschau entsteht in `fleet-ping-nachricht.util.ts`, dem
 * Zwilling von `FleetPingNachricht.java`. Dort steht ausgeschrieben, warum es
 * diese Doppelung gibt und was passiert, wenn sie auseinanderläuft.</p>
 *
 * <h2>Was hier NICHT durchgesetzt wird</h2>
 * <p>Rechte. Der Reiter erscheint nur für die Flottenführung, aber das ist
 * Bequemlichkeit: Wer darf, entscheidet ausschließlich `FleetPingService` im
 * Server - im Dienst und nicht nur am Endpunkt.</p>
 */
@Component({
  selector: 'app-fleet-ping',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './fleet-ping.component.html',
  styleUrls: ['./fleet-ping.component.scss'],
})
export class FleetPingComponent implements OnInit {
  private pingService = inject(FleetPingService);
  private readinessService = inject(ReadinessService);
  private authService = inject(AuthService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  protected readonly FLOTTENARTEN = FLOTTENARTEN;
  protected readonly ERWAEHNUNGEN = ERWAEHNUNGEN;

  status = signal<PingStatusDto | null>(null);
  pings = signal<PingResponseDto[]>([]);
  doktrinen = signal<string[]>([]);
  laedt = signal(false);
  sendet = signal(false);

  /** Ob das Formular offen ist. Zu, solange niemand pingen will. */
  formularOffen = signal(false);

  /** Gesetzt, solange ein bestehender Ping bearbeitet wird - sonst `null`. */
  bearbeiteId = signal<number | null>(null);

  // --- Die Felder. Einzelne Signale statt eines Objekts, weil die Vorschau
  //     an jedem einzelnen hängt und bei jedem Tastendruck stimmen muss. ---
  fleetType = signal('Roam');
  doctrine = signal('');
  formupLocation = signal('');
  comms = signal('Discord');
  notes = signal('');
  srpWahl = signal<SrpWahl>('OFFEN');
  erwaehnung = signal<PingErwaehnung>('STILL');

  /**
   * Die wählbaren Rollen - erst geladen, wenn jemand sie wirklich braucht.
   *
   * <p>Sie kosten im Server einen Aufruf zu Discord, und die meisten Pings sind
   * keine Rollen-Pings.</p>
   */
  rollen = signal<PingRolleDto[]>([]);
  rollenLaden = signal(false);

  /**
   * Die gewählte Rolle, als Discord-Rollenkennung.
   *
   * <p>`null` heißt "noch keine gewählt" und ist kein absendbarer Zustand: Der
   * Server weist eine unbekannte Kennung ab, statt still auf eine Vorgabe
   * auszuweichen - und ein Formular, das ins Leere abschickt, wäre die
   * schlechtere Hälfte dieser Regel.</p>
   */
  rolleId = signal<string | null>(null);

  /**
   * "Form up now" als eigener Schalter und nicht als leeres Zeitfeld.
   *
   * Die häufigste Ansage überhaupt, und sie ist eine andere Aussage als eine
   * ausgeschriebene Uhrzeit: eine Minute später stünde dort Vergangenheit.
   * Vorbelegt, weil der eilige Fall der Normalfall ist.
   */
  sofort = signal(true);
  formupDatum = signal(FleetPingComponent.heuteUtc());
  formupUhrzeit = signal('');

  /** Optionaler Grund einer Absage - siehe `absagen`. */
  absageGrund = signal('');

  /** Der angemeldete Charakter. Sein Name steht unter dem Ping. */
  fcName = computed(() => this.authService.currentUser()?.characterName ?? '');

  private fcId = computed(() => this.authService.currentUser()?.characterId ?? null);

  /** Ob die Auswahl "Ping-Rolle" überhaupt etwas bewirkt. */
  rolleNutzbar = computed(() => this.status()?.rolleKonfiguriert === true);

  /** Die gewählte Rolle - gebraucht wird ihr Name, nicht ihre Kennung. */
  gewaehlteRolle = computed<PingRolleDto | null>(() => {
    const id = this.rolleId();
    return id === null ? null : this.rollen().find((r) => r.discordRoleId === id) ?? null;
  });

  verfuegbar = computed(() => this.status()?.verfuegbar === true);

  gewaehlteErwaehnung = computed<ErwaehnungsWahl>(
    () => ERWAEHNUNGEN.find((e) => e.wert === this.erwaehnung()) ?? ERWAEHNUNGEN[0]);

  /**
   * Der Zeitpunkt des Formups als ISO-8601 mit Versatz - oder `null` für JETZT.
   *
   * <p>Zusammengesetzt aus Datum und Uhrzeit über `Date.UTC`, nicht über
   * `new Date('2026-09-03T19:00')`: die zweite Form liest der Browser als seine
   * eigene Zeit, und dann stünde im Kanal eine um die Zonendifferenz
   * verschobene Uhrzeit. EVE-Zeit ist UTC, also wird hier nichts umgerechnet.</p>
   */
  formupIso = computed<string | null>(() => {
    if (this.sofort()) return null;
    const datum = this.formupDatum();
    const uhrzeit = this.formupUhrzeit();
    if (!datum || !uhrzeit) return null;

    const [jahr, monat, tag] = datum.split('-').map(Number);
    const [stunde, minute] = uhrzeit.split(':').map(Number);
    if ([jahr, monat, tag, stunde, minute].some((z) => Number.isNaN(z))) return null;

    return new Date(Date.UTC(jahr, monat - 1, tag, stunde, minute)).toISOString();
  });

  /**
   * Dieselbe Zeit in der Zone des Browsers.
   *
   * <p>Steht als Gegenprobe unter dem Feld. Eine Flottenankündigung mit
   * zweideutiger Uhrzeit ist schlimmer als keine, und der häufigste Fehler ist
   * der, bei dem jemand seine Ortszeit einträgt - der fällt hier sofort auf,
   * weil dann die falsche Ortszeit darunter steht.</p>
   */
  ortszeit = computed(() => {
    const iso = this.formupIso();
    if (!iso) return null;
    return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });
  });

  /** Der Nachrichtentext, wörtlich - ohne die Erwähnung, die davor steht. */
  vorschau = computed(() =>
    pingNachricht(
      {
        fleetType: this.fleetType().trim(),
        doctrine: this.doctrine(),
        formupLocation: this.formupLocation().trim(),
        formupTime: this.formupIso(),
        comms: this.comms(),
        srpCovered: this.srpCovered(),
        notes: this.notes(),
        fcCharacterName: this.fcName(),
      },
      this.bearbeiteId() !== null));

  /**
   * Die Erwähnung, wie sie über dem Text steht - bei einer Rolle mit ihrem Namen.
   *
   * <p>`@Ping-Rolle` war eine brauchbare Beschriftung, solange es genau eine
   * Rolle gab: der Platzhalter und die Sache waren dasselbe. Sobald mehrere zur
   * Auswahl stehen, ist er die einzige Stelle der Vorschau, an der nicht steht,
   * wen es trifft - und dass nichts unbelegt bleibt, ist der ganze Zweck dieser
   * Vorschau. Wer eine Gruppe weckt, muss vorher lesen können, welche.</p>
   *
   * <p>Ohne gewählte Rolle bleibt der Platzhalter stehen. Er ist dann kein
   * Ersatz für einen Namen, sondern die Aussage, dass noch keiner feststeht -
   * und in diesem Zustand ist das Absenden ohnehin gesperrt.</p>
   */
  erwaehnungsMarke = computed(() => {
    const wahl = this.gewaehlteErwaehnung();
    if (wahl.wert !== 'ROLLE') return wahl.marke;
    const rolle = this.gewaehlteRolle();
    return rolle === null ? wahl.marke : `@${rolle.name}`;
  });

  /**
   * Die Beschriftung über der Vorschau - bei einer Rolle deren Name.
   *
   * <p>"Ping-Rolle" ist die Stufe, und die kennt der FC schon, er hat sie eben
   * angeklickt. Was er dort nachlesen muss, ist die Gruppe.</p>
   */
  erwaehnungsChip = computed(() => {
    const wahl = this.gewaehlteErwaehnung();
    if (wahl.wert !== 'ROLLE') return wahl.titel;
    return this.gewaehlteRolle()?.name ?? wahl.titel;
  });

  /**
   * Die Vorschau, wie sie im Kanal steht - samt der gewählten Erwähnung.
   *
   * <p>Bei "Ping-Rolle" steht in der echten Nachricht die maschinenlesbare Form
   * `&lt;@&amp;123456&gt;`, die Discord erst beim Leser zum farbig hervorgehobenen
   * Rollennamen auflöst. Hier steht stattdessen `@` und der Name - das ist das,
   * was der Leser sieht, und nicht das, was über die Leitung geht. Die einzige
   * Stelle dieser Vorschau, die nicht wörtlich ist, und sie steht darunter
   * angeschrieben.</p>
   */
  vorschauGanz = computed(() => {
    const marke = this.erwaehnungsMarke();
    return marke === '' ? this.vorschau() : `${marke} ${this.vorschau()}`;
  });

  /**
   * Ob in einem der Felder etwas steht, das entschärft wird.
   *
   * <p>Der Zero-Width-Space, den der Server einsetzt, ist unsichtbar - die
   * Vorschau sähe ohne diesen Hinweis genauso aus wie ein Text, der wirklich
   * pingt. Ein FC, der "@everyone" tippt, soll erfahren, dass es keines mehr
   * ist, bevor er sich auf eine Wirkung verlässt, die nicht eintritt.</p>
   */
  entschaerftEtwas = computed(() =>
    enthaeltErwaehnung(this.fleetType(), this.doctrine(), this.formupLocation(),
      this.comms(), this.notes()));

  /**
   * Die Pflichtangaben - dieselben zwei wie im Server, plus die Rolle.
   *
   * <p>Die Rolle steht hier, weil der Server einen Rollen-Ping ohne wählbare
   * Rolle abweist, statt ihn still zu einem leisen zu machen. Ohne diese Zeile
   * liefe der FC in eine Fehlermeldung, die das Formular schon vorher kennt.</p>
   */
  eingabenVollstaendig = computed(() =>
    this.fleetType().trim() !== '' && this.formupLocation().trim() !== ''
    && (this.sofort() || this.formupIso() !== null)
    && (this.erwaehnung() !== 'ROLLE' || this.rolleId() !== null));

  /**
   * Der eigene Ping, der noch im Kanal steht.
   *
   * <p>Er bekommt einen eigenen Platz ganz oben, weil das Absagen die
   * dringlichste Handlung dieses Reiters ist: Wer eine tote Flotte absagen
   * will, hat keine Zeit, sie in einer Liste von fünfzig Zeilen zu suchen.</p>
   */
  eigenerOffenerPing = computed<PingResponseDto | null>(() => {
    const id = this.fcId();
    if (id === null) return null;
    return this.pings().find((p) => p.fcCharacterId === id && p.zustand !== 'ABGESAGT') ?? null;
  });

  ngOnInit(): void {
    this.ladeStatus();
    this.laden();
    // Die Doktrinen sind eine Bequemlichkeit und keine Bedingung: schlägt der
    // Abruf fehl, bleibt das Feld ein Freitextfeld und der Ping geht trotzdem.
    this.readinessService.doctrines().subscribe({
      next: (namen) => this.doktrinen.set(namen),
      error: () => this.doktrinen.set([]),
    });
  }

  private ladeStatus(): void {
    this.pingService.status().subscribe({
      next: (status) => {
        this.status.set(status);
        // Eine Auswahl anzubieten, die nachweislich nichts tut, wäre schlimmer
        // als sie wegzulassen - der FC hielte den Ping für abgesetzt.
        if (!status.rolleKonfiguriert && this.erwaehnung() === 'ROLLE') {
          this.erwaehnung.set('STILL');
        }
      },
      error: () => this.status.set({ verfuegbar: false, rolleKonfiguriert: false, hinweis: null }),
    });
  }

  laden(): void {
    this.laedt.set(true);
    this.pingService.letzte().subscribe({
      next: (liste) => {
        this.pings.set(liste);
        this.laedt.set(false);
      },
      error: (err) => {
        this.laedt.set(false);
        this.toastService.error(this.meldung(err, 'Die Ping-Liste konnte nicht geladen werden.'));
      },
    });
  }

  // ================= Formular =================

  formularOeffnen(): void {
    this.zuruecksetzen();
    this.formularOffen.set(true);
  }

  formularSchliessen(): void {
    this.formularOffen.set(false);
    this.bearbeiteId.set(null);
  }

  /**
   * Setzt eine Erwähnung - und weist die wirkungslose ab, statt sie zu nehmen.
   *
   * <p>Beim Wechsel weg von "Rolle" fällt die gewählte Rolle weg. Sie beim
   * Umschalten liegenzulassen und erst beim Bauen des Befehls zu unterdrücken
   * hätte gereicht, solange genau eine Stelle den Befehl baut - und wäre eine
   * Zusicherung, die davon abhängt, dass die nächste Stelle daran denkt. Hier
   * ist der Zustand selbst richtig: Was nicht gewählt ist, steht nicht da.</p>
   */
  waehleErwaehnung(wert: PingErwaehnung): void {
    if (wert === 'ROLLE' && !this.rolleNutzbar()) {
      this.toastService.info(
        'Im Auth ist keine Discord-Rolle verknüpft - unter /admin/discord lässt sich das '
        + 'nachholen.');
      return;
    }
    this.erwaehnung.set(wert);
    if (wert === 'ROLLE') {
      this.ladeRollen();
    } else {
      this.rolleId.set(null);
    }
  }

  /**
   * Holt die wählbaren Rollen und belegt die Auswahl vor.
   *
   * <p>Schlägt der Abruf fehl, bleibt die Liste leer und das Absenden gesperrt -
   * `eingabenVollstaendig` verlangt eine gewählte Rolle. Eine leere Auswahl bei
   * freigegebenem Knopf wäre die schlechtere Hälfte: Der FC drückte und
   * bekäme die Absage erst vom Server.</p>
   *
   * <p>Liegt die Liste schon vor, wird nur noch vorbelegt und nicht erneut
   * abgerufen. Das ist der Fall nach einem Hin und Her zwischen den Stufen: der
   * Abruf kostet im Server einen Aufruf zu Discord, die Vorbelegung nichts.</p>
   */
  ladeRollen(): void {
    if (this.rollenLaden()) return;
    if (this.rollen().length > 0) {
      this.vorbelegen();
      return;
    }

    this.rollenLaden.set(true);
    this.pingService.rollen().subscribe({
      next: (liste) => {
        this.rollen.set(liste);
        this.rollenLaden.set(false);
        this.vorbelegen();
      },
      error: (err) => {
        this.rollenLaden.set(false);
        this.toastService.error(
          this.meldung(err, 'Die wählbaren Ping-Rollen konnten nicht geladen werden.'));
      },
    });
  }

  /**
   * Belegt die Auswahl vor, wenn keine getroffen ist.
   *
   * <p>Die im Server vorbelegte Rolle, sonst die erste. Vorbelegen und nicht
   * leer lassen: Ein leeres Auswahlfeld sieht aus wie eine getroffene Wahl und
   * ist keine. Eine schon getroffene Wahl wird nicht überschrieben - sonst
   * spränge die Auswahl beim Bearbeiten eines fremden Rollen-Pings auf die
   * Vorbelegung zurück.</p>
   */
  private vorbelegen(): void {
    if (this.rolleId() !== null) return;
    const liste = this.rollen();
    const vorbelegt = liste.find((r) => r.vorbelegt) ?? liste[0] ?? null;
    this.rolleId.set(vorbelegt === null ? null : vorbelegt.discordRoleId);
  }

  /** Die Auswahl im Formular - aus dem `select`. */
  waehleRolle(id: string): void {
    this.rolleId.set(id === '' ? null : id);
  }

  srpCovered(): boolean | null {
    switch (this.srpWahl()) {
      case 'JA': return true;
      case 'NEIN': return false;
      default: return null;
    }
  }

  /**
   * Füllt das Formular aus einem bestehenden Ping.
   *
   * <p>Auch die Erwähnung wird übernommen. Sie hat beim Bearbeiten zwar keine
   * Wirkung - ein PATCH benachrichtigt in Discord niemanden -, steht aber im
   * Text und wäre sonst nach dem Speichern plötzlich eine andere.</p>
   */
  bearbeitenStarten(ping: PingResponseDto): void {
    this.bearbeiteId.set(ping.id);
    this.fleetType.set(ping.fleetType);
    this.doctrine.set(ping.doctrine ?? '');
    this.formupLocation.set(ping.formupLocation);
    this.comms.set(ping.comms ?? '');
    this.notes.set(ping.notes ?? '');
    this.srpWahl.set(ping.srpCovered === null || ping.srpCovered === undefined
      ? 'OFFEN' : (ping.srpCovered ? 'JA' : 'NEIN'));
    this.erwaehnung.set(ping.erwaehnung);
    // Die Rolle mit übernehmen, sonst schriebe eine Korrektur den Ping
    // stillschweigend auf eine andere Gruppe um.
    this.rolleId.set(ping.erwaehnungRolleId);
    if (ping.erwaehnung === 'ROLLE') {
      this.ladeRollen();
    }

    if (ping.formupTime) {
      this.sofort.set(false);
      const eve = eveZeit(ping.formupTime);
      this.formupDatum.set(eve.substring(0, 10));
      this.formupUhrzeit.set(eve.substring(11, 16));
    } else {
      this.sofort.set(true);
      this.formupUhrzeit.set('');
    }
    this.formularOffen.set(true);
  }

  private zuruecksetzen(): void {
    this.bearbeiteId.set(null);
    this.fleetType.set('Roam');
    this.doctrine.set('');
    this.formupLocation.set('');
    this.comms.set('Discord');
    this.notes.set('');
    this.srpWahl.set('OFFEN');
    this.erwaehnung.set('STILL');
    this.rolleId.set(null);
    this.sofort.set(true);
    this.formupDatum.set(FleetPingComponent.heuteUtc());
    this.formupUhrzeit.set('');
  }

  // ================= Absenden =================

  /**
   * Setzt den Ping ab - nach einer Rückfrage, die die Folge nennt.
   *
   * <p>Die Reihenfolge ist Absicht: erst die Eingaben prüfen, dann fragen. Eine
   * Rückfrage über einen Ping, der ohnehin am fehlenden Treffpunkt scheitert,
   * gewöhnt einen FC daran, sie wegzuklicken.</p>
   */
  async absenden(): Promise<void> {
    if (this.sendet()) return;

    if (!this.eingabenVollstaendig()) {
      this.toastService.error(this.erwaehnung() === 'ROLLE' && this.rolleId() === null
        ? 'Wähle die Rolle, die gerufen werden soll.'
        : 'Flottenart und Treffpunkt sind Pflicht - ohne sie kann niemand entscheiden, '
          + 'ob er andockt.');
      return;
    }

    const bearbeitet = this.bearbeiteId();
    const wahl = this.gewaehlteErwaehnung();

    // Der Text der Rückfrage nennt, wen es erreicht. Beim Bearbeiten nennt er
    // die Grenze des Verfahrens: Discord benachrichtigt bei einer Korrektur
    // niemanden erneut - wer den Kanal nicht noch einmal ansieht, erfährt sie nicht.
    const frage = bearbeitet === null
      ? {
        titel: 'Ping absenden?',
        text: `"${this.fleetType().trim()}", Treffpunkt ${this.formupLocation().trim()}, `
          + `Formup ${this.sofort() ? 'jetzt' : this.formupIso() ? eveZeit(this.formupIso()!) + ' EVE' : 'jetzt'}. `
          + this.folgeText(wahl),
        knopf: 'Jetzt pingen',
      }
      : {
        titel: 'Ping ändern?',
        text: 'Die bestehende Nachricht im Kanal wird überschrieben und als geändert '
          + 'gekennzeichnet. Discord benachrichtigt dabei niemanden erneut - wer sie schon '
          + 'gelesen hat, sieht die Änderung nur, wenn er noch einmal hineinschaut.',
        knopf: 'Änderung übernehmen',
      };

    const bestaetigt = await this.confirmService.ask(
      frage.titel, frage.text, frage.knopf, 'Abbrechen');
    if (!bestaetigt) return;

    const dto = this.alsDto();
    this.sendet.set(true);

    const anfrage = bearbeitet === null
      ? this.pingService.senden(dto)
      : this.pingService.bearbeiten(bearbeitet, dto);

    anfrage.subscribe({
      next: () => {
        this.sendet.set(false);
        this.formularSchliessen();
        this.toastService.success(bearbeitet === null
          ? 'Der Ping steht im Kanal.'
          : 'Die Nachricht im Kanal wurde geändert.');
        this.laden();
      },
      error: (err) => {
        this.sendet.set(false);
        // Kein Formular schließen und nichts zurücksetzen: Der Ping ist NICHT
        // hinausgegangen, und der FC soll den zweiten Versuch nicht neu tippen.
        this.toastService.error(this.meldung(err, 'Der Ping konnte nicht abgesetzt werden.'));
      },
    });
  }

  private alsDto(): PingRequestDto {
    return {
      fleetType: this.fleetType().trim(),
      doctrine: this.leerAlsNull(this.doctrine()),
      formupLocation: this.formupLocation().trim(),
      formupTime: this.formupIso(),
      comms: this.leerAlsNull(this.comms()),
      srpCovered: this.srpCovered(),
      notes: this.leerAlsNull(this.notes()),
      erwaehnung: this.erwaehnung(),
      // Nur bei der Auswahl "Rolle". Eine mitgeschickte Kennung bei jeder
      // anderen Lautstärke wäre eine Angabe, die nichts bedeutet - und beim
      // nächsten Umbau eine, die plötzlich etwas bedeutet. Der Wechsel der
      // Stufe räumt sie schon weg; diese Zeile ist die zweite Sperre, damit
      // kein künftiger Weg an `waehleErwaehnung` vorbei die erste umgeht.
      rolleId: this.erwaehnung() === 'ROLLE' ? this.rolleId() : null,
    };
  }

  // ================= Absagen =================

  /**
   * Sagt einen Ping ab.
   *
   * <p>Die Rückfrage sagt ausdrücklich, was im Kanal geschieht: Die Nachricht
   * wird umgeschrieben, die alten Angaben bleiben durchgestrichen stehen. Und
   * sie nennt die Grenze - eine Absage klingelt bei niemandem. Wer das nicht
   * weiß, hält die Sache für erledigt und lässt Leute zu einer Flotte fliegen,
   * die es nicht mehr gibt.</p>
   */
  async absagen(ping: PingResponseDto): Promise<void> {
    const bestaetigt = await this.confirmService.ask(
      'Flotte absagen?',
      `Die Nachricht im Kanal wird zur Absage umgeschrieben; die bisherigen Angaben zu `
      + `"${ping.fleetType}" bleiben durchgestrichen stehen. Discord benachrichtigt dabei `
      + `niemanden erneut - wer die Ankündigung schon gelesen hat, sieht die Absage nur, `
      + `wenn er noch einmal in den Kanal schaut.`,
      'Flotte absagen',
      'Doch nicht');
    if (!bestaetigt) return;

    const grund = this.leerAlsNull(this.absageGrund());
    this.pingService.absagen(ping.id, grund).subscribe({
      next: () => {
        this.absageGrund.set('');
        this.toastService.info('Die Flotte ist im Kanal als abgesagt gekennzeichnet.');
        this.laden();
      },
      error: (err) =>
        this.toastService.error(this.meldung(err, 'Die Absage konnte nicht gesetzt werden.')),
    });
  }

  // ================= Liste =================

  /**
   * Ob dieser Ping vom Betrachter geändert oder abgesagt werden darf.
   *
   * <p>Eine Anzeigeregel und keine Sicherung - durchgesetzt wird sie im Server.
   * Sie ist bewusst enger als dort: der Direktor darf laut Server auch fremde
   * Pings anfassen, hier steht dafür kein Knopf. Ein Knopf, den man nur im
   * Ausnahmefall braucht, an fünfzig Zeilen ist fünfzigmal eine Gelegenheit,
   * versehentlich fremde Ankündigungen umzuschreiben. Wer die Ausnahme braucht,
   * hat sie über die Schnittstelle.</p>
   */
  darfAendern(ping: PingResponseDto): boolean {
    return ping.zustand !== 'ABGESAGT' && ping.fcCharacterId === this.fcId();
  }

  /**
   * Der Satz der Rückfrage - bei einer Rolle mit ihrem Namen darin.
   *
   * <p>"Alle, die die gewählte Rolle tragen" ist keine Auskunft, solange nicht
   * dabeisteht, welche das ist. Der Name ist genau die Angabe, an der ein FC
   * merkt, dass er die falsche Gruppe erwischt hat - und die Rückfrage ist die
   * letzte Stelle, an der ihm das noch auffällt.</p>
   */
  folgeText(wahl: ErwaehnungsWahl): string {
    const rolle = this.gewaehlteRolle();
    if (wahl.wert !== 'ROLLE' || rolle === null) return wahl.folge;
    return `Alle mit der Rolle "${rolle.name}" bekommen eine Benachrichtigung - `
      + 'auch die, die gerade nicht online sind.';
  }

  erwaehnungsWahl(wert: PingErwaehnung): ErwaehnungsWahl {
    return ERWAEHNUNGEN.find((e) => e.wert === wert) ?? ERWAEHNUNGEN[0];
  }

  /** Die Formup-Zeit einer Zeile, in EVE-Zeit - oder das Wort für "sofort". */
  formupText(ping: PingResponseDto): string {
    return ping.formupTime ? `${eveZeit(ping.formupTime)} EVE` : 'sofort';
  }

  zustandsText(zustand: PingResponseDto['zustand']): string {
    switch (zustand) {
      case 'GEAENDERT': return 'geändert';
      case 'ABGESAGT': return 'abgesagt';
      default: return 'im Kanal';
    }
  }

  // ================= Kleinkram =================

  private leerAlsNull(wert: string): string | null {
    const t = wert.trim();
    return t === '' ? null : t;
  }

  /** Die Meldung des Servers, wenn es eine gibt - sie ist die brauchbarere. */
  private meldung(err: unknown, ersatz: string): string {
    const message = (err as { error?: { message?: string } } | null)?.error?.message;
    return message ?? ersatz;
  }

  /** Das heutige Datum in EVE-Zeit als `yyyy-MM-dd` - die Vorbelegung des Feldes. */
  static heuteUtc(): string {
    return new Date().toISOString().substring(0, 10);
  }
}
