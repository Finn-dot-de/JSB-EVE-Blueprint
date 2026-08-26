import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AcademyService,
  InterestDto,
  SaveInterestDto,
  SaveTopicDto,
  TopicDetailDto,
  TopicDto,
} from '../../services/academy.service';
import { AuthRoleDto, GroupService } from '../../services/group.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { ToastService } from '../../services/toast.service';
import { MarkdownViewComponent } from '../markdown/markdown-view.component';
import { ERLAUBTE_BILD_HOSTS } from '../markdown/markdown';

type Tab = 'TOPICS' | 'MANAGE';

/** Der Umschalter im Editor - dieselbe Mechanik wie die Reiter, ein Muster zweimal benutzt. */
export type EditorMode = 'EDIT' | 'PREVIEW';

/**
 * Was eine Themenkarte gerade ist - genau ein Zustand je Thema.
 *
 * <p>Der Zustand steht als eigener Begriff da und wird nicht an drei Stellen
 * aus `active` und `hasMyInterest` neu zusammengesetzt: Randfarbe, Kennzeichen
 * und Test müssen dieselbe Antwort bekommen, sonst leuchtet eine Karte als
 * "du bist dabei", während daneben "inaktiv" steht.</p>
 */
export type TopicCardState = 'MINE' | 'OPEN' | 'INACTIVE';

/**
 * Was aus einer Häufigkeitsverteilung als Aussage übrigbleibt.
 *
 * <p>Drei Fälle und nicht zwei: "niemand hat etwas angegeben" ist eine andere
 * Auskunft als "es verteilt sich zu gleichmäßig". Wer beide zu `null`
 * zusammenzieht, muss den Unterschied im Satzbau raten - und rät ihn beim
 * nächsten Umbau falsch.</p>
 */
export type Spitze =
  | { art: 'KEINE_ANGABE' }
  | { art: 'UNKLAR' }
  | { art: 'KLAR'; label: string };

/** Ein Eintrag der drei Mehrfachauswahlen: gespeicherter Schlüssel, kurze und lange Beschriftung. */
export interface Auswahl {
  key: string;
  kurz: string;
  lang: string;
}

/**
 * Die sieben Wochentage in Mo-So-Ordnung.
 *
 * <p>Die Schlüssel sind die Namen von `java.time.DayOfWeek` - genau das, was das
 * Backend speichert und in `weekdayCounts` zurückgibt. Die Reihenfolge ist die
 * der Woche und nicht die des Alphabets: `FRIDAY, MONDAY, SATURDAY...` wäre für
 * das Auge wertlos, und der Streifen unter der Karte lebt davon, dass er über
 * alle Karten hinweg an derselben Stelle denselben Tag zeigt.</p>
 */
export const WOCHENTAGE: readonly Auswahl[] = [
  { key: 'MONDAY', kurz: 'Mo', lang: 'Montag' },
  { key: 'TUESDAY', kurz: 'Di', lang: 'Dienstag' },
  { key: 'WEDNESDAY', kurz: 'Mi', lang: 'Mittwoch' },
  { key: 'THURSDAY', kurz: 'Do', lang: 'Donnerstag' },
  { key: 'FRIDAY', kurz: 'Fr', lang: 'Freitag' },
  { key: 'SATURDAY', kurz: 'Sa', lang: 'Samstag' },
  { key: 'SUNDAY', kurz: 'So', lang: 'Sonntag' },
];

/**
 * Die fünf Zeitfenster in Tagesreihenfolge, wortgleich zu
 * `AcademyInterest.TIME_WINDOWS`.
 *
 * <p>Alle Uhrzeiten sind UTC und damit EVE-Zeit. Die Uhrzeiten stehen nur hier
 * in der Beschriftung: gespeichert wird `EU_PRIME`, nicht `19:00`. Wer die
 * Grenzen verschiebt, ändert diese Zeile und wandert keine Daten.</p>
 */
export const ZEITFENSTER: readonly Auswahl[] = [
  { key: 'AUTZ', kurz: 'AUTZ', lang: 'AUTZ 08-13' },
  { key: 'EU_EARLY', kurz: 'EU früh', lang: 'EU früh 16-19' },
  { key: 'EU_PRIME', kurz: 'EU Prime', lang: 'EU Prime 19-22' },
  { key: 'USTZ', kurz: 'USTZ', lang: 'USTZ 22-03' },
  { key: 'WEEKEND_DAY', kurz: 'WE tags', lang: 'Wochenende tagsüber' },
];

/**
 * Der Autorenkreis, wortgleich zu `AccessRules.ACADEMY_AUTHORS` im Backend.
 *
 * <p>Die Liste blendet hier nur ein; abgewiesen wird ein Aufruf ohnehin erst im
 * `AcademyService` des Servers. Laufen die beiden Stellen auseinander, sieht ein
 * Nutzer höchstens einen Reiter zu viel oder zu wenig - anfangen kann er damit
 * nichts.</p>
 *
 * <p>Gebraucht wird sie nur für einen Fall, den die Daten nicht hergeben: bei
 * <b>null</b> Themen ist die Liste leer, und aus einer leeren Liste lässt sich
 * `canEdit` nicht ablesen. Genau dann muss der Verwaltungsreiter aber da sein -
 * sonst kann niemand das erste Thema anlegen.</p>
 */
export const ACADEMY_AUTHOR_ROLES = [
  'ROLE_CEO',
  'ROLE_DIRECTOR',
  'ROLE_IT_ADMIN',
  'ROLE_A38',
  'ROLE_69',
];

/**
 * Die eingebauten Rollen, die als Ausbilderrolle nicht taugen - dieselbe Liste
 * wie `SystemRoles.builtIn()` im Backend.
 *
 * <p>`ROLE_USER`, `ROLE_MEMBER` und `ROLE_GUEST` trägt praktisch jeder: eine
 * davon am Thema genügte, damit jeder Angemeldete die Namensliste sähe. Das
 * Backend weist sie ab; hier stehen sie deshalb gar nicht erst zur Wahl.</p>
 */
const BUILT_IN_ROLES = [
  'ROLE_USER',
  'ROLE_MEMBER',
  'ROLE_GUEST',
  'ROLE_CEO',
  'ROLE_DIRECTOR',
  'ROLE_IT_ADMIN',
  'ROLE_MARAUDERS_ASSOCIATED',
];

/**
 * Die Vorlage im leeren Lehrplanfeld.
 *
 * <p>Als Platzhalter und nicht als vorbelegter Inhalt: ein vorbelegtes Feld
 * müsste jeder erst leerräumen, der es anders will, und ein versehentlich
 * gespeichertes Gerüst stünde danach als Lehrplan da. Der Platzhalter
 * verschwindet beim ersten Zeichen von selbst.</p>
 */
export const LEHRPLAN_VORLAGE = `## Inhalt
- Was drankommt

## Danach kannst du
- Konkretes Ergebnis`;

/** Ab hier färbt sich der Zeichenzähler - kurz vor der Grenze, nicht erst darüber. */
const WARNSCHWELLE = 0.9;

const MAX_TITEL = 120;
const MAX_KURZZEILE = 200;
const MAX_LEHRPLAN = 20_000;
const MAX_NOTIZ = 280;

/**
 * Hakt einen Wert einer Mehrfachauswahl an oder ab.
 *
 * <p><b>Eine</b> Hilfe für drei Auswahlen - Wochentage, Zeitfenster und
 * Ausbilderrollen sind dreimal dieselbe Mechanik. Dreimal ausgeschrieben gingen
 * sie beim ersten Umbau auseinander, und die dritte Fassung wäre die, die
 * niemand mehr anfasst.</p>
 *
 * <p>Die Liste wird <b>neu gebaut</b> und nicht mit `push` geändert: der Entwurf
 * liegt in einem Signal, und dieselbe Referenz zweimal gesetzt zündet es nicht -
 * die Chips blieben stehen, wie sie waren.</p>
 */
export function toggleIn(list: readonly string[], value: string): string[] {
  return list.includes(value) ? list.filter((eintrag) => eintrag !== value) : [...list, value];
}

/**
 * Die Academy: was gelehrt werden könnte, und wie viele es wann wollen.
 *
 * <p>Der Ertrag der Seite ist nicht die Zahl, sondern der Satz darunter. Ein
 * Balkenbild muss gelesen werden; ein Satz wird verstanden. Wer den Reiter
 * überfliegt, soll ohne einen einzigen Klick sagen können, was sich lohnt und
 * wann - siehe {@link nachfrageSatz}.</p>
 *
 * <p><b>Was diese Komponente ausblendet, ist Bequemlichkeit und kein Schutz.</b>
 * `canEdit` und `canViewInterest` kommen aus dem Datensatz und steuern nur, was
 * angeboten wird; durchgesetzt wird jede dieser Regeln im `AcademyService` des
 * Servers, und zwar unabhängig davon, was hier steht. Ein Aufruf, den die
 * Oberfläche nicht anbietet, wäre trotzdem abgewiesen worden - und einer, den
 * sie versehentlich anbietet, wird abgewiesen.</p>
 *
 * <p>Der Lehrplan läuft ausschließlich über {@link MarkdownViewComponent} -
 * dieselbe Komponente für die aufgeklappte Karte und für die Vorschau im
 * Editor. Das ist keine Bequemlichkeit: die Vorschau zeigt ungespeicherten
 * Text, der die Prüfung im Backend noch nicht gesehen hat. Ein zweiter
 * Anzeigepfad wäre genau der Pfad, auf dem die Prüfung fehlt.</p>
 */
@Component({
  selector: 'app-academy',
  standalone: true,
  imports: [CommonModule, FormsModule, MarkdownViewComponent],
  templateUrl: './academy.component.html',
  styleUrls: ['./academy.component.scss'],
})
export class AcademyComponent implements OnInit {
  private academyService = inject(AcademyService);
  private groupService = inject(GroupService);
  private authService = inject(AuthService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  /** Für die Vorlage sichtbar gemacht - die Reihenfolge ist Teil der Aussage. */
  protected readonly WOCHENTAGE = WOCHENTAGE;
  protected readonly ZEITFENSTER = ZEITFENSTER;
  protected readonly LEHRPLAN_VORLAGE = LEHRPLAN_VORLAGE;
  protected readonly MAX_TITEL = MAX_TITEL;
  protected readonly MAX_KURZZEILE = MAX_KURZZEILE;
  protected readonly MAX_LEHRPLAN = MAX_LEHRPLAN;
  protected readonly MAX_NOTIZ = MAX_NOTIZ;

  /**
   * Die erlaubten Bild-Hosts, wörtlich aus dem Renderer.
   *
   * <p>Aus derselben Konstante, die beim Rendern entscheidet, und nicht
   * abgeschrieben: eine zweite Liste im Hilfetext wäre die Liste, die nach der
   * ersten Änderung falsch ist - und dann behauptet der Editor etwas, das der
   * Renderer nicht einlöst.</p>
   */
  protected readonly bildHosts = ERLAUBTE_BILD_HOSTS.join(', ');

  activeTab = signal<Tab>('TOPICS');
  topics = signal<TopicDto[]>([]);
  adminTopics = signal<TopicDto[]>([]);
  loading = signal(false);
  loadingAdmin = signal(false);
  saving = signal(false);
  savingInterest = signal(false);

  /**
   * Der Filter "nur meine" - ein Chip und kein dritter Reiter.
   *
   * <p>Bei zehn bis fünfundzwanzig Themen wäre eine zweite Liste derselben
   * Karten Verschwendung. Ein Filter zeigt außerdem sofort, dass es dieselben
   * Karten sind.</p>
   */
  nurMeine = signal(false);

  /**
   * Welche Karte offen ist - höchstens eine.
   *
   * <p>Genau eine und nicht beliebig viele: bei zwölf offenen Lehrplänen scrollt
   * niemand mehr. Wer vergleichen will, klappt um - der einmal geholte Lehrplan
   * bleibt gespeichert, der Wechsel kostet also keinen neuen Abruf.</p>
   */
  expandedTopicId = signal<number | null>(null);

  /**
   * Die bereits geholten Lehrpläne, je Themen-Id.
   *
   * <p>Der Grund für den Speicher: geladen wird erst beim Aufklappen, und ein
   * zweites Aufklappen derselben Karte darf den Server nicht erneut fragen -
   * sonst löst jedes Auf und Zu einen Abruf aus.</p>
   */
  private details = signal<Map<number, TopicDetailDto>>(new Map());
  loadingDetailFor = signal<number | null>(null);

  /** Die Namenslisten, je Themen-Id - nur für den Sichtkreis überhaupt geholt. */
  private interested = signal<Map<number, InterestDto[]>>(new Map());
  loadingInterestedFor = signal<number | null>(null);

  /**
   * Die eigene Bekundung im Entwurf, solange eine Karte offen ist.
   *
   * <p>Ein einziger Schreibweg über {@link updateEntwurf}: sieben Chips, die
   * jeder für sich in das Signal schreiben, sind sieben Stellen, an denen jemand
   * das `...` vergisst.</p>
   */
  entwurf = signal<SaveInterestDto | null>(null);

  /** Der Editor des Autorenkreises - `null` heißt: kein Modal offen. */
  editingTopic = signal<SaveTopicDto | null>(null);
  editorMode = signal<EditorMode>('EDIT');

  /** Der Rollenkatalog für die Ausbilderrollen - erst beim Öffnen des Editors geholt. */
  roles = signal<AuthRoleDto[]>([]);
  private modalOptionsLoaded = signal(false);

  /**
   * Ob der Nutzer laut seinen Rollen zum Autorenkreis gehört.
   *
   * <p>Als Getter und nicht als Feld: `/api/auth/me` kann beim Aufbau der Seite
   * noch unterwegs sein, ein einmal berechneter Wert bliebe dann für immer
   * `false`. Der Zugriff läuft über das Signal `currentUser`, {@link canManage}
   * hängt also weiterhin an der Reaktivität.</p>
   */
  get isAuthorByRole(): boolean {
    return this.authService.hasAnyRole(ACADEMY_AUTHOR_ROLES);
  }

  /**
   * Ob der Reiter "Verwaltung" erscheint.
   *
   * <p>Zwei Quellen mit Absicht: `canEdit` aus dem Datensatz ist die genauere -
   * sie kommt vom Server, der die Rollen wirklich kennt. Sie fehlt aber, solange
   * es kein einziges Thema gibt, und genau dann braucht ein Autor den Reiter am
   * dringendsten. Die Rollenliste fängt diesen Fall ab.</p>
   */
  canManage = computed(
    () => this.isAuthorByRole || this.topics().some((topic) => topic.canEdit),
  );

  /** Wie viele Themen der Nutzer schon angehakt hat - die Zahl am Filter-Chip. */
  meineAnzahl = computed(() => this.topics().filter((topic) => topic.hasMyInterest).length);

  /** Was die Liste zeigt: alles oder nur das Eigene. */
  sichtbareThemen = computed(() =>
    this.nurMeine() ? this.topics().filter((topic) => topic.hasMyInterest) : this.topics(),
  );

  /**
   * Was in der Auswahl "Halten dürfen" zur Wahl steht.
   *
   * <p>Der Katalog plus die bereits eingetragenen Rollen: fällt eine Rolle aus
   * dem Katalog (etwa weil ihre Titel-Zuordnung weg ist), stünde sie sonst zwar
   * am Thema, aber ohne Chip - und fiele beim nächsten Speichern still heraus.
   * Das Thema verlöre seinen Sichtkreis, ohne dass jemand etwas abgewählt
   * hätte.</p>
   */
  teacherRoleChoices = computed(() => {
    const katalog = this.roles()
      .map((role) => role.name)
      .filter((name) => !BUILT_IN_ROLES.includes(name));
    const gewaehlt = this.editingTopic()?.teacherRoleNames ?? [];
    return [...katalog, ...gewaehlt.filter((name) => !katalog.includes(name))];
  });

  ngOnInit() {
    this.load();
  }

  // ================= Laden =================

  load() {
    this.loading.set(true);
    this.academyService.getTopics().subscribe({
      next: (topics) => {
        this.topics.set(topics);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.toastService.error(err.error?.message || 'Die Themen konnten nicht geladen werden.');
      },
    });
  }

  /**
   * Die Liste der Verwaltung - inklusive der abgeschalteten Themen.
   *
   * <p>Ein eigener Aufruf und nicht ein Filter über {@link topics}: die
   * öffentliche Liste enthält die inaktiven gar nicht, ein Filter darüber fände
   * also nie eines.</p>
   */
  loadAdminTopics() {
    this.loadingAdmin.set(true);
    this.academyService.getAdminTopics().subscribe({
      next: (topics) => {
        this.adminTopics.set(topics);
        this.loadingAdmin.set(false);
      },
      error: (err) => {
        this.loadingAdmin.set(false);
        this.toastService.error(err.error?.message || 'Die Verwaltung konnte nicht geladen werden.');
      },
    });
  }

  setTab(tab: Tab) {
    this.activeTab.set(tab);
    if (tab === 'MANAGE' && this.canManage() && !this.loadingAdmin()) this.loadAdminTopics();
  }

  // ================= Die Nachfrage in Worten =================

  /**
   * Der Zustand einer Karte - Grundlage für Randfarbe und Kennzeichen.
   *
   * <p>"Abgeschaltet" schlägt "du bist dabei": ein Thema, das gerade
   * umgeschrieben wird, findet nicht statt, und das ist die Auskunft, auf die es
   * ankommt. Die eigene Bekundung steht ohnehin daneben und geht nicht
   * verloren.</p>
   */
  cardState(topic: TopicDto): TopicCardState {
    if (!topic.active) return 'INACTIVE';
    if (topic.hasMyInterest) return 'MINE';
    return 'OPEN';
  }

  /**
   * Der Satz unter der Karte - der eigentliche Ertrag der ganzen Seite.
   *
   * <p>"7 Interessierte - am besten Di oder Do, EU Prime." Ein FC, der den
   * Reiter überfliegt, soll ohne einen Klick sagen können, was sich lohnt und
   * wann. Deshalb ein Satz und nicht nur die Zahl am Rand.</p>
   *
   * <p>Vier Fälle, jeder mit einem eigenen Grund:</p>
   * <ul>
   *   <li><b>Niemand:</b> eine einladende Beschriftung. Niemand ist gern der
   *       Erste, und ein totes Board bleibt tot - das ist das größte Risiko
   *       dieses Features und es ist kein technisches.</li>
   *   <li><b>Genau einer:</b> nur die Zahl. Das Backend liefert unterhalb von
   *       zwei Bekundungen keine Verteilung, weil "nur Mittwoch, USTZ" sonst
   *       faktisch den Namen verriete. Hier eine zu erfinden, hieße die
   *       Entscheidung des Servers zu unterlaufen.</li>
   *   <li><b>Gleichstand:</b> beide Tage nennen. Einen Sieger zu wählen, wo
   *       keiner ist, ist die Sorte Genauigkeit, die man später nicht mehr
   *       nachprüfen kann.</li>
   *   <li><b>Alles auf null:</b> ehrlich sagen, dass nichts angegeben wurde.
   *       "Am besten Montag" bei sieben Nullen wäre schlicht gelogen.</li>
   * </ul>
   */
  nachfrageSatz(topic: TopicDto): string {
    const anzahl = topic.interestCount;
    if (anzahl === 0) return 'Noch niemand - sei die erste Stimme.';
    if (anzahl === 1) {
      return '1 Interessierter - ab der zweiten Stimme zeigt das Board, wann es passt.';
    }

    const zahl = `${anzahl} Interessierte`;
    const tag = this.spitze(topic.weekdayCounts, WOCHENTAGE);
    const fenster = this.spitze(topic.windowCounts, ZEITFENSTER);

    if (tag.art === 'KEINE_ANGABE' && fenster.art === 'KEINE_ANGABE') {
      return `${zahl} - bisher ohne Angabe, wann es passt.`;
    }
    if (tag.art === 'KLAR' && fenster.art === 'KLAR') {
      return `${zahl} - am besten ${tag.label}, ${fenster.label}.`;
    }
    if (tag.art === 'KLAR') {
      return `${zahl} - am besten ${tag.label}; beim Zeitfenster kein klares Bild.`;
    }
    if (fenster.art === 'KLAR') {
      return `${zahl} - kein klarer Tag, aber ${fenster.label}.`;
    }
    return `${zahl} - kein klares Muster bei Tag und Zeit.`;
  }

  /**
   * Was aus einer Verteilung als Aussage übrigbleibt.
   *
   * <p>Bis zu zwei Spitzenreiter werden genannt ("Di oder Do"); ab drei
   * gleichauf gibt es kein Muster mehr, das ein Satz behaupten dürfte - dann
   * heißt es "kein klarer Tag". Die Reihenfolge kommt aus dem übergebenen
   * Katalog und nicht aus dem gelieferten Objekt: die Schlüsselreihenfolge eines
   * JSON-Objekts ist nichts, worauf man eine Anzeige stützt.</p>
   */
  private spitze(counts: Record<string, number>, katalog: readonly Auswahl[]): Spitze {
    let hoechster = 0;
    const fuehrend: string[] = [];
    for (const eintrag of katalog) {
      const wert = counts[eintrag.key] ?? 0;
      if (wert > hoechster) {
        hoechster = wert;
        fuehrend.length = 0;
        fuehrend.push(eintrag.kurz);
      } else if (wert === hoechster && wert > 0) {
        fuehrend.push(eintrag.kurz);
      }
    }

    if (hoechster === 0) return { art: 'KEINE_ANGABE' };
    if (fuehrend.length > 2) return { art: 'UNKLAR' };
    return { art: 'KLAR', label: fuehrend.join(' oder ') };
  }

  /** Ob überhaupt eine Verteilung vorliegt - unterhalb von zwei Bekundungen ist sie leer. */
  hatVerteilung(topic: TopicDto): boolean {
    return Object.keys(topic.weekdayCounts).length > 0;
  }

  /** Die absolute Zahl hinter einem Balken. */
  zaehler(counts: Record<string, number>, key: string): number {
    return counts[key] ?? 0;
  }

  /**
   * Die Höhe eines Balkens in Prozent.
   *
   * <p>Bezogen auf die Gesamtzahl und nicht auf den größten Wert: ein Streifen,
   * dessen höchster Balken immer voll ausschlägt, sieht bei zwei Nennungen
   * genauso aus wie bei zwanzig - und macht zwei Karten unvergleichbar.</p>
   */
  anteil(topic: TopicDto, counts: Record<string, number>, key: string): number {
    if (topic.interestCount === 0) return 0;
    return Math.round(((counts[key] ?? 0) / topic.interestCount) * 100);
  }

  /** Die lange Beschriftung zu einem gespeicherten Schlüssel - unbekannt bleibt lesbar. */
  label(katalog: readonly Auswahl[], key: string): string {
    return katalog.find((eintrag) => eintrag.key === key)?.kurz ?? key;
  }

  /** Die eigenen Tage als Kurzform, in Wochenordnung. */
  meineTage(topic: TopicDto): string[] {
    return topic.myWeekdays.map((key) => this.label(WOCHENTAGE, key));
  }

  /** Die eigenen Zeitfenster als Kurzform. */
  meineFenster(topic: TopicDto): string[] {
    return topic.myTimeWindows.map((key) => this.label(ZEITFENSTER, key));
  }

  // ================= Aufklappen =================

  isExpanded(topic: TopicDto): boolean {
    return this.expandedTopicId() === topic.id;
  }

  /** Der geholte Lehrplan - `null`, solange nichts geholt wurde. */
  lehrplan(topic: TopicDto): string | null {
    return this.details().get(topic.id)?.description ?? null;
  }

  /** Die geholte Namensliste - leer, solange nichts geholt wurde. */
  interessenten(topic: TopicDto): InterestDto[] {
    return this.interested().get(topic.id) ?? [];
  }

  /**
   * Klappt eine Karte auf oder wieder zu.
   *
   * <p>Der Lehrplan hängt am Aufklappen und nicht am Seitenaufbau: zwölf
   * Lehrpläne zu holen, von denen niemand einen liest, verzögert die Seite für
   * alle. Geholt wird nur, was noch nicht dasteht - wer dieselbe Karte zweimal
   * aufklappt, löst deshalb keinen zweiten Abruf aus.</p>
   *
   * <p>Beim Aufklappen entsteht auch der Entwurf der eigenen Bekundung, aus dem
   * Stand des Themas. Eine Karte, die man zuklappt und wieder öffnet, zeigt
   * damit wieder das Gespeicherte - unfertige Klicks stehen bewusst nicht über
   * das Zuklappen hinaus, sonst sähe man gewählte Tage, die der Server nie
   * gesehen hat.</p>
   */
  toggleTopic(topic: TopicDto) {
    if (this.isExpanded(topic)) {
      this.expandedTopicId.set(null);
      this.entwurf.set(null);
      return;
    }

    this.expandedTopicId.set(topic.id);
    this.beginneEntwurf(topic);
    if (!this.details().has(topic.id)) this.loadDetail(topic.id);
    if (topic.canViewInterest && !this.interested().has(topic.id)) this.loadInterested(topic.id);
  }

  /**
   * Holt den Lehrplan eines Themas.
   *
   * <p>Ein Fehlschlag klappt die Karte wieder zu und legt <b>nichts</b> ab: ein
   * leerer Eintrag im Speicher gälte hinterher als "geladen", stünde als "kein
   * Lehrplan geschrieben" da und bliebe den Rest der Sitzung so - obwohl
   * niemand weiß, was dort steht.</p>
   */
  private loadDetail(topicId: number) {
    this.loadingDetailFor.set(topicId);
    this.academyService.getTopic(topicId).subscribe({
      next: (detail) => {
        this.details.update((cache) => new Map(cache).set(topicId, detail));
        this.loadingDetailFor.set(null);
      },
      error: (err) => {
        this.loadingDetailFor.set(null);
        this.expandedTopicId.set(null);
        this.entwurf.set(null);
        this.toastService.error(err.error?.message || 'Der Lehrplan konnte nicht geladen werden.');
      },
    });
  }

  /**
   * Holt die Namen der Interessenten.
   *
   * <p>Ein Fehlschlag klappt die Karte <b>nicht</b> zu - anders als beim
   * Lehrplan. Der Lehrplan ist der Inhalt der aufgeklappten Karte; ohne ihn ist
   * sie leer. Die Namensliste ist eine Beigabe für den Sichtkreis, und wer sie
   * nicht bekommt, soll trotzdem lesen können, worum es geht.</p>
   */
  private loadInterested(topicId: number) {
    this.loadingInterestedFor.set(topicId);
    this.academyService.getInterested(topicId).subscribe({
      next: (liste) => {
        this.interested.update((cache) => new Map(cache).set(topicId, liste));
        this.loadingInterestedFor.set(null);
      },
      error: (err) => {
        this.loadingInterestedFor.set(null);
        this.toastService.error(
          err.error?.message || 'Die Interessenten konnten nicht geladen werden.',
        );
      },
    });
  }

  // ================= Interesse bekunden =================

  /** Der Entwurf beginnt beim Gespeicherten - Kopien, damit ein Chip-Klick die Karte nicht ändert. */
  private beginneEntwurf(topic: TopicDto) {
    this.entwurf.set({
      weekdays: [...topic.myWeekdays],
      timeWindows: [...topic.myTimeWindows],
      note: topic.myNote,
    });
  }

  /** Der einzige Schreibweg in den Entwurf. */
  updateEntwurf(patch: Partial<SaveInterestDto>) {
    const entwurf = this.entwurf();
    if (entwurf) this.entwurf.set({ ...entwurf, ...patch });
  }

  istTagGewaehlt(key: string): boolean {
    return this.entwurf()?.weekdays.includes(key) ?? false;
  }

  istFensterGewaehlt(key: string): boolean {
    return this.entwurf()?.timeWindows.includes(key) ?? false;
  }

  toggleTag(key: string) {
    const entwurf = this.entwurf();
    if (entwurf) this.updateEntwurf({ weekdays: toggleIn(entwurf.weekdays, key) });
  }

  toggleFenster(key: string) {
    const entwurf = this.entwurf();
    if (entwurf) this.updateEntwurf({ timeWindows: toggleIn(entwurf.timeWindows, key) });
  }

  /**
   * Schickt die Bekundung ab - erst hier, nicht bei jedem Chip-Klick.
   *
   * <p>Sieben Klicks wären sonst sieben `PUT`, und der Zwischenstand "Dienstag,
   * aber noch kein Zeitfenster" ginge jedes Mal als vollständige Bekundung
   * hinaus.</p>
   *
   * <p>Die Prüfung "mindestens ein Tag <b>und</b> mindestens ein Fenster" steht
   * hier, weil das Backend genau das abweist. Ohne sie liefe der Nutzer in eine
   * Fehlermeldung des Servers, die er nicht vorhersehen konnte - und der Satz
   * hier kann sagen, <em>warum</em> beides gebraucht wird.</p>
   *
   * <p>Danach wird die eine Zeile umgeschrieben statt die Liste neu geholt: die
   * Antwort trägt die frisch gerechneten Zähler bereits.</p>
   */
  saveInterest(topic: TopicDto) {
    const entwurf = this.entwurf();
    if (!entwurf) return;

    if (entwurf.weekdays.length === 0 || entwurf.timeWindows.length === 0) {
      this.toastService.error(
        'Wähle mindestens einen Tag und ein Zeitfenster - sonst weiß niemand, wann du kannst.',
      );
      return;
    }

    this.savingInterest.set(true);
    this.academyService.saveInterest(topic.id, entwurf).subscribe({
      next: (aktualisiert) => {
        this.savingInterest.set(false);
        this.ersetzeThema(aktualisiert);
        this.beginneEntwurf(aktualisiert);
        this.frischeInteressenten(aktualisiert);
        this.toastService.success('Interesse gespeichert.');
      },
      error: (err) => {
        this.savingInterest.set(false);
        this.toastService.error(err.error?.message || 'Das Interesse konnte nicht gespeichert werden.');
      },
    });
  }

  /**
   * Nimmt die eigene Bekundung zurück.
   *
   * <p>Die Rückfrage nennt die <b>Folge</b> und nicht die Frage - Hausregel.</p>
   *
   * <p><b>Abweichung vom Bauplan, mit Grund:</b> danach wird die ganze Liste neu
   * geholt statt die Zeile lokal herunterzuzählen. Beim Speichern liefert der
   * Server die neuen Zähler mit; beim Zurückziehen antwortet er mit 204. Lokal
   * zu rechnen hieße, nicht nur eine Zahl zu verringern, sondern auch die
   * Schwelle nachzubauen, unterhalb derer der Server gar keine Verteilung mehr
   * liefert - eine Serverregel im Browser, die niemand nachpflegt, wenn die
   * Schwelle sich ändert. Dasselbe Argument, mit dem `groups-board.leave()` neu
   * lädt, statt die Mitgliederzahl zu verringern.</p>
   */
  async withdrawInterest(topic: TopicDto) {
    if (!topic.hasMyInterest) return;

    const bestaetigt = await this.confirmService.ask(
      'Interesse zurückziehen?',
      `Dein Interesse an "${topic.title}" verschwindet aus der Nachfrage. Die Ausbilder sehen dich dann nicht mehr in der Liste.`,
      'Zurückziehen',
    );
    if (!bestaetigt) return;

    this.academyService.withdrawInterest(topic.id).subscribe({
      next: () => {
        this.toastService.success('Interesse zurückgezogen.');
        this.frischeInteressenten(topic);
        this.load();
      },
      error: (err) =>
        this.toastService.error(err.error?.message || 'Das Interesse konnte nicht zurückgezogen werden.'),
    });
  }

  /** Ersetzt genau eine Zeile - in beiden Listen, damit Verwaltung und Themen nicht auseinanderlaufen. */
  private ersetzeThema(topic: TopicDto) {
    const ersetzen = (liste: TopicDto[]) =>
      liste.map((eintrag) => (eintrag.id === topic.id ? topic : eintrag));
    this.topics.update(ersetzen);
    this.adminTopics.update(ersetzen);
  }

  /**
   * Wirft die gespeicherte Namensliste weg und holt sie, wenn die Karte offen
   * ist, sofort neu.
   *
   * <p>Ohne das stünde die eigene Zeile nach dem Speichern noch mit den alten
   * Tagen in der Liste der Ausbilder - oder nach dem Zurückziehen überhaupt
   * noch da.</p>
   */
  private frischeInteressenten(topic: TopicDto) {
    this.interested.update((cache) => {
      const kopie = new Map(cache);
      kopie.delete(topic.id);
      return kopie;
    });
    if (this.expandedTopicId() === topic.id && topic.canViewInterest) {
      this.loadInterested(topic.id);
    }
  }

  // ================= Der Editor =================

  newTopic() {
    this.loadModalOptions();
    this.editorMode.set('EDIT');
    this.editingTopic.set({
      id: null,
      title: '',
      summary: '',
      description: '',
      active: true,
      teacherRoleNames: [],
    });
  }

  /**
   * Öffnet den Editor für ein bestehendes Thema.
   *
   * <p>Der Lehrplan muss geholt werden: die Liste trägt ihn nicht. Steht er
   * schon im Speicher, wird er von dort genommen - der Editor ist kein Grund für
   * einen zweiten Abruf desselben Textes.</p>
   */
  editTopic(topic: TopicDto) {
    this.loadModalOptions();
    this.editorMode.set('EDIT');

    const bekannt = this.details().get(topic.id);
    if (bekannt) {
      this.oeffneEditor(topic, bekannt.description);
      return;
    }

    this.academyService.getTopic(topic.id).subscribe({
      next: (detail) => {
        this.details.update((cache) => new Map(cache).set(topic.id, detail));
        this.oeffneEditor(topic, detail.description);
      },
      error: (err) =>
        this.toastService.error(err.error?.message || 'Der Lehrplan konnte nicht geladen werden.'),
    });
  }

  private oeffneEditor(topic: TopicDto, description: string | null) {
    this.editingTopic.set({
      id: topic.id,
      title: topic.title,
      summary: topic.summary,
      description: description ?? '',
      active: topic.active,
      // Eine Kopie: die Auswahl im Modal darf die Karte dahinter nicht schon
      // vor dem Speichern umschreiben.
      teacherRoleNames: [...topic.teacherRoleNames],
    });
  }

  closeModal() {
    this.editingTopic.set(null);
  }

  /** Der einzige Schreibweg in den Themen-Entwurf. */
  updateTopic(patch: Partial<SaveTopicDto>) {
    const topic = this.editingTopic();
    if (topic) this.editingTopic.set({ ...topic, ...patch });
  }

  istAusbilderrolle(roleName: string): boolean {
    return this.editingTopic()?.teacherRoleNames.includes(roleName) ?? false;
  }

  toggleAusbilderrolle(roleName: string) {
    const topic = this.editingTopic();
    if (topic) this.updateTopic({ teacherRoleNames: toggleIn(topic.teacherRoleNames, roleName) });
  }

  /** Die Länge des Lehrplans - für den Zeichenzähler. */
  lehrplanLaenge(): number {
    return this.editingTopic()?.description?.length ?? 0;
  }

  /** Ob ein Zeichenzähler warnen soll: kurz vor der Grenze, nicht erst darüber. */
  istKnapp(laenge: number, grenze: number): boolean {
    return laenge >= grenze * WARNSCHWELLE;
  }

  saveTopic() {
    const topic = this.editingTopic();
    if (!topic) return;

    // Prüfung von Hand, wie überall im Projekt - es gibt keine Validatoren.
    // Der Server prüft dieselben Grenzen noch einmal; hier steht sie, damit
    // niemand für einen leeren Titel erst einen Aufruf braucht.
    if (!topic.title.trim()) {
      this.toastService.error('Das Thema braucht einen Titel.');
      return;
    }
    if (!topic.summary.trim()) {
      this.toastService.error('Die Kurzzeile ist Pflicht - sie ist alles, was auf der Karte steht.');
      return;
    }

    this.saving.set(true);
    this.academyService.saveTopic(topic).subscribe({
      next: () => {
        this.saving.set(false);
        this.editingTopic.set(null);
        this.toastService.success('Thema gespeichert.');
        // Der Lehrplan im Speicher ist jetzt der alte - er würde beim nächsten
        // Aufklappen als "geladen" gelten und die Änderung verschlucken.
        this.details.set(new Map());
        this.load();
        this.loadAdminTopics();
      },
      error: (err) => {
        this.saving.set(false);
        this.toastService.error(err.error?.message || 'Das Thema konnte nicht gespeichert werden.');
      },
    });
  }

  async deleteTopic(topic: TopicDto) {
    const bestaetigt = await this.confirmService.ask(
      'Thema löschen?',
      `"${topic.title}" verschwindet samt Lehrplan und allen ${topic.interestCount} Interessensbekundungen. Wer Interesse bekundet hatte, erfährt davon nichts.`,
      'Löschen',
    );
    if (!bestaetigt) return;

    this.academyService.deleteTopic(topic.id).subscribe({
      next: () => {
        this.toastService.success('Thema gelöscht.');
        this.details.set(new Map());
        this.load();
        this.loadAdminTopics();
      },
      error: (err) =>
        this.toastService.error(err.error?.message || 'Das Thema konnte nicht gelöscht werden.'),
    });
  }

  /**
   * Der Rollenkatalog für die Ausbilderrollen.
   *
   * <p>Erst beim Öffnen des Editors und nur einmal. Ein Fehlschlag setzt die
   * Sperre zurück, damit der nächste Versuch es wieder probiert.</p>
   *
   * <p>Der Fehlschlag ist hier <b>der Normalfall für einen Teil des
   * Autorenkreises</b>: `GET /api/groups/roles` verlangt Direktor, CEO oder
   * IT-Admin, der Autorenkreis der Academy umfasst zusätzlich A38 und 69. Ein
   * Ausbilder bekommt dort also 403. Das ist kein Fehler, den er beheben könnte,
   * und deshalb steht hier kein Toast - die Oberfläche sagt stattdessen unter
   * der leeren Auswahl, was los ist, und die bereits eingetragenen Rollen
   * bleiben über {@link teacherRoleChoices} sichtbar und wählbar.</p>
   */
  private loadModalOptions() {
    if (this.modalOptionsLoaded()) return;
    this.modalOptionsLoaded.set(true);

    this.groupService.getRoles().subscribe({
      next: (roles) => this.roles.set(roles),
      error: () => {
        this.roles.set([]);
        this.modalOptionsLoaded.set(false);
      },
    });
  }
}
