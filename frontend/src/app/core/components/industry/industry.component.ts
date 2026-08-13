import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { of } from 'rxjs';
import {
  BlueprintCheck,
  BuildLocation,
  IndustryService,
  Job,
  Procurement,
  OrderDetail,
  OrderSummary,
  PlanPreview,
  ProductHit,
  Requirement,
} from '../../services/industry.service';
import { latestRequest } from '../../shared/latest-request.util';
import {
  Zustand,
  aktivitaetenLabel,
  stufenLabel,
  zustandLabel,
  zustandVon,
} from './industry-baum.util';

/** Eine Bedarfszeile samt ihrer Stellung in der Fertigung. */
export interface Knoten {
  zeile: Requirement;
  /** 0 ist Beschaffung, darüber wird gebaut. Nicht die Tiefe im Stücklistenbaum. */
  rang: number;
  zustand: Zustand;
  /** Der Job, der gerade daran arbeitet - sonst null. */
  job: Job | null;
}

/** Eine Fertigungsstufe: alles, was auf demselben Rang liegt. */
export interface Stufe {
  rang: number;
  label: string;
  /** Reaktion, Fertigung oder beides - damit niemand raten muss, wo es läuft. */
  aktivitaet: string;
  knoten: Knoten[];
  gedeckt: number;
  laeuft: number;
  offen: number;
  /** Wieviel davon sich sofort anschieben lässt. */
  startklar: number;
}

/**
 * Der Industrie-Assistent.
 *
 * <p>Die Gestaltung folgt einem Grundsatz: der Stücklistenbaum wird nicht
 * ausgebreitet, sondern verhandelt. Wer fünfzig Raven bauen will, sieht zuerst
 * drei Materialien und vier Kennzahlen - nicht 146 Zeilen. Tiefe entsteht nur
 * dort, wo jemand auf "Bauen" drückt.</p>
 */
@Component({
  selector: 'app-industry',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './industry.component.html',
  styleUrls: ['./industry.component.scss'],
})
export class IndustryComponent {
  private industry = inject(IndustryService);

  /** Ab wie vielen Zeichen gesucht wird - darunter ist jede Liste sinnlos lang. */
  private static readonly MIN_QUERY = 2;

  readonly query = signal('');
  readonly hits = signal<ProductHit[]>([]);
  readonly chosen = signal<ProductHit | null>(null);
  readonly quantity = signal(1);

  readonly preview = signal<PlanPreview | null>(null);
  readonly orders = signal<OrderSummary[]>([]);
  readonly openOrder = signal<OrderDetail | null>(null);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly locationQuery = signal('');
  readonly locations = signal<BuildLocation[]>([]);
  /** Der beim Anlegen gewählte Bauort - bestimmt Transportmittel und Frachtkosten. */
  readonly chosenLocation = signal<BuildLocation | null>(null);
  readonly procurement = signal<Procurement | null>(null);
  readonly blueprints = signal<BlueprintCheck[]>([]);

  /** Die Suche: nur die jüngste Antwort zählt, Fehler beenden den Strom nicht. */
  private readonly search = latestRequest<string, ProductHit[]>({
    run: (q) => (q.length < IndustryComponent.MIN_QUERY ? of([]) : this.industry.search(q)),
    next: (rows) => this.hits.set(rows),
    error: () => this.hits.set([]),
    debounceMs: 250,
    distinct: true,
  });

  private readonly planning = latestRequest<
    { typeId: number; qty: number; systemId: number | null },
    PlanPreview
  >({
    run: (input) => this.industry.preview(input.typeId, input.qty, 1, input.systemId),
    next: (plan) => {
      this.preview.set(plan);
      this.loading.set(false);
    },
    error: () => {
      this.error.set('Der Bauwunsch ließ sich nicht durchrechnen.');
      this.loading.set(false);
    },
  });

  /** Die Bauortsuche - eigener Auslöser, damit sie die Produktsuche nicht stört. */
  private readonly locationSearch = latestRequest<string, BuildLocation[]>({
    run: (q) =>
      q.length < IndustryComponent.MIN_QUERY ? of([]) : this.industry.locations(q),
    next: (rows) => this.locations.set(rows),
    error: () => this.locations.set([]),
    debounceMs: 250,
    distinct: true,
  });

  /**
   * Die Gegenwart, alle fünfzehn Sekunden neu.
   *
   * Ein einziges Intervall für die ganze Seite. Bei fünfundvierzig sichtbaren
   * Zeilen wären es sonst fünfundvierzig Timer, und weil die Anwendung nicht
   * zoneless läuft, stößt jeder davon einen vollen Durchlauf der
   * Änderungserkennung an. Die kleinste angezeigte Einheit ist die Minute -
   * fünfzehn Sekunden sind reichlich.
   */
  readonly now = signal(Date.now());

  constructor() {
    this.reloadOrders();
    const ticker = setInterval(() => this.now.set(Date.now()), 15_000);
    // Über DestroyRef statt ngOnDestroy: ein Intervall, das die Komponente
    // überlebt, tickt für immer weiter und hält sie im Speicher.
    inject(DestroyRef).onDestroy(() => clearInterval(ticker));
  }

  onLocationQuery(value: string) {
    this.locationQuery.set(value);
    this.chosenLocation.set(null);
    this.locationSearch(value.trim());
  }

  chooseLocation(loc: BuildLocation) {
    // Bei einem offenen Auftrag ist die Wahl keine Vormerkung, sondern eine
    // Änderung: an ihr hängen Transportkosten und die Frage, welches Material
    // als vorhanden gilt. Also gleich setzen und neu rechnen.
    if (this.openOrder()) {
      this.applyLocation(loc);
      return;
    }
    this.chosenLocation.set(loc);
    this.locations.set([]);
    this.locationQuery.set(loc.name || loc.systemName || '');
    // Die Vorschau zählt bis hierhin ganz EVE als vorhanden. Mit dem Ort wird
    // daraus eine Aussage über den Ort - noch bevor etwas angelegt ist.
    this.refreshPreview();
  }

  /**
   * Was an einem Ort geht, als ein Satz.
   *
   * Bei unbekannten Diensten wird ausdrücklich nichts behauptet - geraten wird
   * hier nicht.
   */
  locationServices(loc: BuildLocation): string {
    // Bei einem ganzen Sonnensystem gibt es keine Dienste, nach denen zu fragen
    // wäre. Was dort zählt, ist der Sicherheitsstatus: er entscheidet, ob ein
    // Frachter reicht oder ein Sprungfrachter nötig wird - und das ist der
    // Unterschied zwischen 120 und 460 ISK je Kubikmeter.
    if (loc.source === 'SYSTEM') {
      const zone = this.securityZone(loc.security);
      return loc.typeName ? `${zone} · ${loc.typeName}` : zone;
    }
    if (!loc.servicesKnown) return 'Dienste unbekannt';
    const kann: string[] = [];
    if (loc.manufacturing) kann.push('Fertigung');
    if (loc.reprocessing) kann.push('Wiederaufbereitung');
    if (loc.reactions) kann.push('Reaktionen');
    return kann.length ? kann.join(' · ') : 'Keine Industriedienste online';
  }

  /**
   * Die Sicherheitszone zu einem Statuswert.
   *
   * Gerundet wie im Spiel: 0,05 wird in der Anzeige zu 0,1 und zählt als
   * Highsec. Wer stattdessen auf `>= 0.5` prüft, erklärt ein 0,45-System zum
   * Lowsec, obwohl der Frachter dort fahren darf.
   */
  securityZone(security: number | null | undefined): string {
    if (security === null || security === undefined) return 'Sicherheit unbekannt';
    const gerundet = Math.round(security * 10) / 10;
    if (gerundet >= 0.5) return 'Highsec';
    if (gerundet > 0.0) return 'Lowsec';
    return 'Nullsec';
  }

  /**
   * Ob im Suchfeld nur der Name des bereits gesetzten Bauorts steht.
   *
   * Nach dem Setzen bleibt der Name im Feld stehen. Die Suche findet ihn nicht
   * wieder — Sonnensysteme kommen über den Namensanfang, ein gesetzter Ort ist
   * keine Sucheingabe —, und darunter erschien "Kein Bauort gefunden", während
   * drei Zeilen höher "Dieser Auftrag baut in AH-B84" stand. Zwei Aussagen, die
   * einander widersprechen; wer das liest, hält einen eingestellten Auftrag für
   * kaputt.
   */
  locationQueryIstGesetzterOrt(): boolean {
    const gesetzt = this.openOrder()?.order.buildLocationName;
    if (!gesetzt) return false;
    return this.locationQuery().trim().toLowerCase() === gesetzt.trim().toLowerCase();
  }

  /** Rückmeldung des Kopierknopfs - ohne sie weiß niemand, ob es geklappt hat. */
  readonly copyStatus = signal<'idle' | 'ok' | 'fehler'>('idle');

  /**
   * Die Einkaufsliste im Multibuy-Format von EVE: ein Posten je Zeile,
   * Name und Menge durch einen Tabulator getrennt.
   *
   * Genommen wird, was man **tatsächlich kauft** — bei einer Erz-Empfehlung
   * also das komprimierte Erz und dessen Menge, nicht das Mineral. Wer die
   * Mineralien einfügt, kauft am Rat der Liste vorbei.
   *
   * Zeilen ohne Marktpreis bleiben drin. Ein fehlender Preis heißt nicht, dass
   * das Material nicht gebraucht wird — es stillschweigend wegzulassen wäre
   * eine unvollständige Liste, die vollständig aussieht.
   */
  multibuyText(): string {
    const p = this.procurement();
    if (!p) return '';
    return p.lines
      .map((z) => {
        const name = z.buyTypeName ?? z.typeName;
        const menge = z.buyTypeName ? z.buyQuantity : z.neededQuantity;
        return `${name}\t${Math.max(1, Math.ceil(menge))}`;
      })
      .join('\n');
  }

  /**
   * Legt die Liste in die Zwischenablage.
   *
   * Der Umweg über ein Textfeld ist kein Zierrat: `navigator.clipboard` gibt es
   * nur in einem sicheren Kontext. Über HTTPS und auf localhost geht es, beim
   * Aufruf über eine nackte LAN-Adresse nicht — und dort schlüge der Knopf
   * sonst wortlos fehl.
   */
  async copyMultibuy(): Promise<void> {
    const text = this.multibuyText();
    if (!text) return;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const feld = document.createElement('textarea');
        feld.value = text;
        feld.style.position = 'fixed';
        feld.style.opacity = '0';
        document.body.appendChild(feld);
        feld.select();
        const geklappt = document.execCommand('copy');
        document.body.removeChild(feld);
        if (!geklappt) throw new Error('Kopieren abgelehnt');
      }
      this.copyStatus.set('ok');
    } catch {
      this.copyStatus.set('fehler');
    }
    setTimeout(() => this.copyStatus.set('idle'), 2500);
  }

  /** Woher der Ort stammt - entscheidet, wie sicher die Auskunft ist. */
  sourceLabel(source: string): string {
    switch (source) {
      case 'CORP':
        return 'Eigene Corp';
      case 'NPC':
        return 'NPC-Station';
      case 'SYSTEM':
        return 'Sonnensystem';
      default:
        return 'Andockrecht';
    }
  }

  trackByLocation = (_: number, row: BuildLocation) => row.structureId;

  // ===========================================================
  //  Auswahl
  // ===========================================================

  onQuery(value: string) {
    this.query.set(value);
    this.chosen.set(null);
    this.preview.set(null);
    this.search(value.trim());
  }

  choose(hit: ProductHit) {
    this.chosen.set(hit);
    this.hits.set([]);
    this.query.set(hit.typeName);
    this.refreshPreview();
  }

  onQuantity(value: string) {
    const parsed = Number(value);
    this.quantity.set(Number.isFinite(parsed) && parsed > 0 ? Math.floor(parsed) : 1);
    this.refreshPreview();
  }

  /** Rechnet die Vorschau auf dem Suchbildschirm neu - noch ohne Auftrag. */
  private refreshPreview() {
    const hit = this.chosen();
    if (!hit) return;
    this.loading.set(true);
    this.error.set(null);
    this.planning({
      typeId: hit.typeId,
      qty: this.quantity(),
      systemId: this.chosenLocation()?.systemId ?? null,
    });
  }

  // ===========================================================
  //  Aufträge
  // ===========================================================

  createOrder() {
    const hit = this.chosen();
    if (!hit) return;
    this.loading.set(true);
    const ort = this.chosenLocation();
    this.industry
      .create(hit.typeId, this.quantity(), ort?.systemId ?? null, ort?.name ?? null)
      .subscribe({
      next: (detail) => {
        this.openOrder.set(detail);
        this.preview.set(null);
        this.chosen.set(null);
        this.query.set('');
        this.loading.set(false);
        this.reloadOrders();
        this.loadProcurement(detail.order.id);
      },
      error: () => {
        this.error.set('Der Auftrag ließ sich nicht anlegen.');
        this.loading.set(false);
      },
    });
  }

  /** Holt die Einkaufsliste zu einem Auftrag. */
  private loadProcurement(orderId: number) {
    this.industry.procurement(orderId).subscribe({
      next: (plan) => this.procurement.set(plan),
      error: () => this.procurement.set(null),
    });
    this.industry.blueprints(orderId).subscribe({
      next: (rows) => this.blueprints.set(rows),
      error: () => this.blueprints.set([]),
    });
  }

  /**
   * Wie viele Läufe zur Verfügung stehen, als Text.
   *
   * Ein Original hat keine Laufzahl - das als "-1" anzuzeigen wäre sinnlos.
   */
  runsLabel(check: BlueprintCheck): string {
    if (!check.owned) return 'keine';
    if (check.availableRuns < 0) return 'Original';
    return `${this.amount(check.availableRuns)} von ${this.amount(check.neededRuns)}`;
  }

  trackByCheck = (_: number, row: BlueprintCheck) => row.productTypeId;

  /** Wie eine Zeile beschafft werden soll - für die Anzeige. */
  sourceLabelFor(line: { source: string; buyTypeName: string | null }): string {
    return line.source === 'ORE' ? `Erz: ${line.buyTypeName}` : 'Direkt kaufen';
  }

  /** ISK lesbar, mit Einheit statt sechzehn Nullen. */
  isk(value: number | null): string {
    if (value === null || value === undefined) return '—';
    if (Math.abs(value) >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)} Mrd`;
    if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(1)} Mio`;
    if (Math.abs(value) >= 1_000) return `${(value / 1_000).toFixed(0)} Tsd`;
    return value.toFixed(0);
  }

  trackByLine = (_: number, row: { typeId: number }) => row.typeId;

  open(order: OrderSummary) {
    this.industry.order(order.id).subscribe({
      next: (detail) => {
        this.openOrder.set(detail);
        this.loadProcurement(order.id);
      },
      error: () => this.error.set('Der Auftrag ließ sich nicht laden.'),
    });
  }

  closeOrder() {
    this.openOrder.set(null);
    this.procurement.set(null);
    this.blueprints.set([]);
  }

  /** Die drei Voreinstellungen, in der Reihenfolge zunehmenden Aufwands. */
  readonly strategies = [
    { key: 'BUY_ALL' as const, label: 'Alles kaufen',
      hint: 'Kein Job, keine Blaupause, keine Wartezeit.' },
    { key: 'COST_EFFICIENT' as const, label: 'Möglichst günstig',
      hint: 'Rechnet je Bauteil Material samt Jobgebühr gegen den Fertigpreis.' },
    { key: 'BUILD_ALL' as const, label: 'Alles selbst bauen',
      hint: 'Kosten spielen keine Rolle. PI und Mineralien bleiben ausgenommen.' },
  ];

  readonly strategyRunning = signal(false);

  /** Setzt alle Entscheidungen auf einmal. */
  applyStrategy(key: 'BUY_ALL' | 'COST_EFFICIENT' | 'BUILD_ALL') {
    const detail = this.openOrder();
    if (!detail) return;
    this.strategyRunning.set(true);
    this.industry.applyStrategy(detail.order.id, key).subscribe({
      next: (updated) => {
        this.openOrder.set(updated);
        this.loadProcurement(detail.order.id);
        this.strategyRunning.set(false);
      },
      error: () => {
        this.error.set('Die Voreinstellung ließ sich nicht anwenden.');
        this.strategyRunning.set(false);
      },
    });
  }

  /**
   * Worauf sich die Spalte "Vorhanden" bezieht.
   *
   * Ohne diesen Zusatz wechselt die Zahl stillschweigend ihre Bedeutung, je
   * nachdem ob der Auftrag einen Bauort hat. Eine Zahl, die etwas anderes meint
   * als gestern, ohne es zu sagen, ist schlimmer als eine falsche - die fällt
   * wenigstens auf.
   */
  readonly bestandsOrt = computed(() => {
    const ort = this.openOrder()?.order.buildLocationName ?? this.chosenLocation()?.name;
    return ort ? `in ${ort}` : 'in ganz EVE';
  });

  readonly recalcRunning = signal(false);

  /**
   * Rechnet den offenen Auftrag von Grund auf neu.
   *
   * Der Bedarf ist eingefroren, damit der Fortschrittsbalken nicht bei jedem
   * Neuladen springt. Der Preis dafür: eine inzwischen erforschte Blaupause und
   * geänderte Marktpreise erreichen den Auftrag nie. Das hier ist der Weg
   * zurück - die eigenen Kaufen/Bauen-Entscheidungen bleiben stehen.
   */
  recalculate() {
    const detail = this.openOrder();
    if (!detail) return;
    this.recalcRunning.set(true);
    this.industry.recalculate(detail.order.id).subscribe({
      next: (updated) => {
        this.openOrder.set(updated);
        this.loadProcurement(detail.order.id);
        this.recalcRunning.set(false);
      },
      error: () => {
        this.error.set('Der Auftrag ließ sich nicht neu berechnen.');
        this.recalcRunning.set(false);
      },
    });
  }

  /**
   * Trägt den Bauort an einem bestehenden Auftrag nach.
   *
   * Beim Anlegen ist der Ort freiwillig, und ohne eingelesene Corp-Strukturen
   * war er lange gar nicht wählbar. Ohne diesen Weg bliebe ein Auftrag ohne
   * Bausystem für immer einer - außer man löscht ihn und verliert dabei die
   * Nullmessung.
   */
  applyLocation(loc: BuildLocation) {
    const detail = this.openOrder();
    if (!detail) return;
    this.recalcRunning.set(true);
    this.industry
      .setBuildLocation(detail.order.id, loc.systemId, null, loc.name ?? loc.systemName)
      .subscribe({
        next: (updated) => {
          this.openOrder.set(updated);
          this.locations.set([]);
          this.locationQuery.set('');
          this.loadProcurement(detail.order.id);
          this.recalcRunning.set(false);
        },
        error: () => {
          this.error.set('Der Bauort ließ sich nicht setzen.');
          this.recalcRunning.set(false);
        },
      });
  }

  /** Stellt eine Zeile um. Bei "Bauen" wächst der Baum um genau eine Ebene. */
  decide(row: Requirement, decision: 'BUY' | 'BUILD') {
    const detail = this.openOrder();
    if (!detail) return;
    this.industry.decide(detail.order.id, row.typeId, decision).subscribe({
      next: (updated) => {
        this.openOrder.set(updated);
        // Einkaufsliste und Blaupausen-Prüfung hängen beide am Bedarf, und der
        // ändert sich genau durch diesen Klick. Ohne dieses Nachladen zeigen
        // beide weiter den Stand von vorher - ohne dass man es ihnen ansieht.
        this.loadProcurement(detail.order.id);
      },
      error: () =>
        this.error.set(`${row.typeName} lässt sich nicht per Industriejob herstellen.`),
    });
  }

  /**
   * Löscht einen Auftrag samt Bedarfstabelle, Nullmessung und Job-Zuordnung.
   *
   * Bewusst mit Rückfrage: die Nullmessung lässt sich nicht wiederherstellen,
   * ein neu angelegter Auftrag würde bei Null anfangen und alles bereits
   * Gebaute vergessen.
   */
  deleteOrder(order: OrderSummary) {
    if (!confirm(`${order.productName}-Auftrag wirklich löschen? Der Fortschritt geht verloren.`)) {
      return;
    }
    this.industry.remove(order.id).subscribe({
      next: () => {
        if (this.openOrder()?.order.id === order.id) this.openOrder.set(null);
        this.reloadOrders();
      },
      error: () => this.error.set('Der Auftrag ließ sich nicht löschen.'),
    });
  }

  cancelOrder(order: OrderSummary) {
    this.industry.cancel(order.id).subscribe({
      next: () => this.reloadOrders(),
      error: () => this.error.set('Der Auftrag ließ sich nicht abbrechen.'),
    });
  }

  private reloadOrders() {
    this.industry.orders().subscribe({
      next: (rows) => this.orders.set(rows),
      error: () => this.orders.set([]),
    });
  }

  // ===========================================================
  //  Anzeige
  // ===========================================================

  /** Nur die oberste Ebene - tiefere Zeilen erscheinen erst nach einem Klick auf "Bauen". */
  readonly topLevel = computed(() =>
    (this.openOrder()?.requirements ?? this.preview()?.requirements ?? []).filter(
      (r) => r.depth === 1,
    ),
  );

  /** Die Zeilen, die durch eine Bauen-Entscheidung dazugekommen sind. */
  readonly deeper = computed(() =>
    (this.openOrder()?.requirements ?? []).filter((r) => r.depth > 1),
  );

  readonly summary = computed(() => this.openOrder()?.summary ?? this.preview()?.summary ?? null);

  // ===========================================================
  //  Der Fertigungsbaum
  // ===========================================================

  /** Alle Bedarfszeilen - aus dem Auftrag, sonst aus der Vorschau. */
  private readonly zeilen = computed(
    () => this.openOrder()?.requirements ?? this.preview()?.requirements ?? [],
  );

  /**
   * Die laufenden Jobs, nach dem Typ dessen was sie herstellen.
   *
   * Gelieferte Jobs zählen nicht: sie stehen nicht mehr im Ofen, und eine
   * Restzeit von "vor drei Monaten fertig" hilft niemandem.
   */
  private readonly jobsNachTyp = computed(() => {
    const je = new Map<number, Job>();
    for (const job of this.openOrder()?.jobs ?? []) {
      if (job.productTypeId == null || job.status?.toLowerCase() === 'delivered') continue;
      const bisher = je.get(job.productTypeId);
      // Bei mehreren Jobs auf denselben Typ zählt der, der zuerst fertig wird.
      if (!bisher || (job.endDate ?? '') < (bisher.endDate ?? '')) {
        je.set(job.productTypeId, job);
      }
    }
    return je;
  });

  /**
   * Die Zutaten je Bauteil — aus dem vollständigen Graphen, nicht aus der
   * Elternangabe der Zeile.
   *
   * Der Unterschied ist keine Feinheit: Eine Zeile trägt genau einen
   * Elternteil, ein Material hat aber oft viele Verbraucher. Wer daraus die
   * Zutaten ableitet, sieht bei einem gemessenen Phoenix-Auftrag zwei Drittel
   * davon nicht — und ein Bauteil behauptet „startklar", während ihm etwas
   * fehlt. Genau das ist die gefährliche Richtung: Eine Zeile eine Stufe zu
   * tief ist ein Schönheitsfehler, eine falsche Startfreigabe ist eine
   * Falschaussage.
   */
  private readonly kinderNachEltern = computed(() => {
    const nachTyp = new Map(this.zeilen().map((r) => [r.typeId, r]));
    const je = new Map<number, Requirement[]>();
    for (const kante of this.openOrder()?.edges ?? []) {
      const material = nachTyp.get(kante.materialTypeId);
      if (!material) continue;
      const liste = je.get(kante.productTypeId);
      if (liste) liste.push(material);
      else je.set(kante.productTypeId, [material]);
    }
    return je;
  });

  /** Jede Bedarfszeile samt Rang, Zustand und laufendem Job. */
  readonly knoten = computed<Knoten[]>(() => {
    const zeilen = this.zeilen();
    const kinder = this.kinderNachEltern();
    const jobs = this.jobsNachTyp();

    return zeilen.map((zeile) => {
      const job = jobs.get(zeile.typeId) ?? null;
      return {
        zeile,
        // Aus dem Backend, nicht hier gerechnet: Die Ordnung braucht den
        // ganzen Stücklistengraphen, und der steht in dieser Liste nicht.
        rang: zeile.buildLevel ?? (zeile.decision === 'BUILD' ? 1 : 0),
        zustand: zustandVon(zeile, kinder.get(zeile.typeId) ?? [], job !== null),
        job,
      };
    });
  });

  /**
   * Die Fertigungsstufen, von oben nach unten sortiert.
   *
   * Absteigend nach Rang, weil das die Kernaussage der Ansicht ist: unten steht,
   * was man kauft, darüber was daraus wird, ganz oben das Schiff. Material
   * fließt auf dem Bildschirm nach oben, so wie es auch gebaut wird.
   */
  readonly stufen = computed<Stufe[]>(() => {
    const jeRang = new Map<number, Knoten[]>();
    for (const k of this.knoten()) {
      const liste = jeRang.get(k.rang);
      if (liste) liste.push(k);
      else jeRang.set(k.rang, [k]);
    }
    const hoechster = Math.max(0, ...jeRang.keys());

    return [...jeRang.entries()]
      .sort((a, b) => b[0] - a[0])
      .map(([rang, knoten]) => ({
        rang,
        label: stufenLabel(rang, hoechster),
        aktivitaet: aktivitaetenLabel(knoten.map((k) => k.zeile)),
        knoten,
        // Jede Zeile zählt in genau einen Topf. Vorher zählten die drei
        // Filter unabhängig voneinander dieselbe Zeile mehrfach - daher
        // stand über einer einzigen Position "1 Position · 1 im Ofen · 1 offen".
        gedeckt: knoten.filter((k) => k.zustand === 'GEDECKT').length,
        laeuft: knoten.filter((k) => k.zustand === 'LAEUFT').length,
        offen: knoten.filter(
          (k) => k.zustand === 'STARTKLAR' || k.zustand === 'FEHLT' || k.zustand === 'WARTET',
        ).length,
        startklar: knoten.filter((k) => k.zustand === 'STARTKLAR').length,
      }));
  });

  /**
   * Was sich jetzt sofort anschieben lässt — über alle Stufen hinweg.
   *
   * Die eigentliche Antwort auf „was mache ich als Nächstes". Ohne diesen
   * Abschnitt muss man sich die startklaren Zeilen aus jeder Stufe einzeln
   * zusammensuchen, und genau das war die Beschwerde.
   *
   * Zugesichert ist das erst, seit die Zutaten aus dem vollständigen Graphen
   * kommen: Auf der lückenhaften Elternangabe hätte „startklar" bedeutet
   * „soweit der eine bekannte Zweig reicht".
   */
  /**
   * Ob das Backend die Fertigungsstufen überhaupt mitgeschickt hat.
   *
   * Sagt der Anzeige, dass sie es nicht weiß, statt eine Zahl einzusetzen.
   * Genau daran ist es schon einmal gescheitert: Ein Backend ohne dieses Feld
   * ließ jede Zeile auf „Schritt 0" fallen, alles landete in einer einzigen
   * Gruppe mit 168 Positionen — und das sah aus wie ein Ergebnis, nicht wie
   * ein Fehler.
   */
  readonly stufenFehlen = computed(() => {
    const zeilen = this.zeilen();
    return zeilen.length > 0 && zeilen.every((r) => r.buildLevel === undefined);
  });

  readonly jetztMachbar = computed<Knoten[]>(() =>
    this.knoten()
      .filter((k) => k.zustand === 'STARTKLAR')
      // Von unten nach oben: Was am tiefsten liegt, blockiert am meisten.
      .sort((a, b) => a.rang - b.rang),
  );

  /**
   * Welche Stufe aufgeklappt ist.
   *
   * Vorbelegt mit der untersten, an der noch etwas offen ist - der Arbeitsfront.
   * Der Baum löst sich von unten nach oben auf, also gehört der Blick dorthin,
   * wo gerade etwas zu tun ist, und nicht ganz nach oben zum fertigen Schiff.
   */
  readonly offenerRang = signal<number | null>(null);

  /** Die Stufe, an der die Arbeit gerade steht. */
  readonly arbeitsfront = computed(() => {
    const mitOffenem = this.stufen().filter((s) => s.offen > 0 || s.laeuft > 0);
    return mitOffenem.length ? mitOffenem[mitOffenem.length - 1].rang : null;
  });

  /** Ob eine Stufe aufgeklappt ist - ohne eigene Wahl die Arbeitsfront. */
  istOffen(rang: number): boolean {
    const gewaehlt = this.offenerRang();
    return gewaehlt === null ? rang === this.arbeitsfront() : gewaehlt === rang;
  }

  /** Klappt eine Stufe auf; ein zweiter Klick schließt sie. */
  stufeUmschalten(rang: number) {
    this.offenerRang.set(this.istOffen(rang) ? -1 : rang);
  }

  /** Nur was in der offenen Stufe noch offen ist - sonst sind es 45 Zeilen. */
  readonly stufenFilter = signal<'OFFEN' | 'ALLE'>('OFFEN');

  zeilenDerStufe(stufe: Stufe): Knoten[] {
    return this.stufenFilter() === 'ALLE'
      ? stufe.knoten
      : stufe.knoten.filter((k) => k.zeile.missing > 0 || k.zustand === 'LAEUFT');
  }

  /**
   * Was gerade im Ofen steht.
   *
   * Fertige, aber nicht abgeholte Jobs zuerst: sie blockieren einen Slot, und
   * das ist die einzige Zeile hier, bei der jemand sofort etwas tun kann.
   */
  readonly ofen = computed(() => {
    const jetzt = this.now();
    return (this.openOrder()?.jobs ?? [])
      .filter((j) => j.status?.toLowerCase() !== 'delivered' && j.endDate)
      .sort((a, b) => (a.endDate ?? '').localeCompare(b.endDate ?? ''))
      .map((job) => ({ job, text: this.zeitText(job, jetzt) }));
  });

  /**
   * Die Restzeit eines Jobs als Satzstück.
   *
   * Der Fall "fertig – abholen" ist unter den offenen Jobs der häufigste: der
   * Ofen ist durch, aber niemand hat abgeholt, und der Slot bleibt belegt. Die
   * naive Umsetzung zeigt dort "noch −14 h".
   */
  zeitText(job: Job, now: number = Date.now()): string | null {
    if (!job.endDate || job.status?.toLowerCase() === 'delivered') return null;
    const rest = this.remaining(job.endDate, now);
    if (rest === null) return null;
    return rest === 'fertig' ? 'fertig – abholen' : `fertig in ${rest}`;
  }

  zustandLabel = zustandLabel;

  readonly bpSyncRunning = signal(false);
  readonly bpSyncNote = signal<string | null>(null);

  /**
   * Liest die Blaupausen sofort neu ein.
   *
   * Die Antwort sagt ausdrücklich, bei wie vielen Charakteren der Zugriff
   * scheiterte. Ohne diese Zahl ist "keine Blaupausen gefunden" nicht von
   * "der Abruf läuft gar nicht" zu unterscheiden - genau das war das Problem.
   */
  syncBlueprints() {
    this.bpSyncRunning.set(true);
    this.bpSyncNote.set(null);
    this.industry.syncBlueprints().subscribe({
      next: (r) => {
        this.bpSyncNote.set(
          r.withoutAccess > 0
            ? `${r.written} Blaupausen eingelesen. Bei ${r.withoutAccess} Charakter(en) ` +
              `scheiterte der Zugriff: ${r.characters.join(', ')} - meist ein abgelaufener ` +
              `Refresh-Token, hilft eine neue Anmeldung.`
            : `${r.written} Blaupausen eingelesen.`,
        );
        this.bpSyncRunning.set(false);
        const detail = this.openOrder();
        if (detail) this.loadProcurement(detail.order.id);
      },
      error: () => {
        this.bpSyncNote.set('Der Abruf ließ sich nicht anstoßen.');
        this.bpSyncRunning.set(false);
      },
    });
  }

  // ===========================================================
  //  Ein- und ausklappbare Tafeln
  // ===========================================================

  /**
   * Welche Tafeln zugeklappt sind.
   *
   * Gespeichert wird das Zugeklappte, nicht das Aufgeklappte: so ist der
   * Ausgangszustand "alles offen", und eine neu hinzukommende Tafel ist
   * sichtbar, statt sich stillschweigend zu verstecken.
   *
   * Bewusst nur die Nachschlagewerke - Bauort, Blaupausen, Einkaufsliste. Die
   * Fertigung klappt über ihre Stufen, und die Kostenkacheln des Einkaufs
   * bleiben immer stehen: sie sind die Antwort, die Liste darunter ist die
   * Begründung.
   */
  private readonly zugeklappt = signal<ReadonlySet<string>>(new Set());

  panelOffen(name: string): boolean {
    return !this.zugeklappt().has(name);
  }

  panelUmschalten(name: string) {
    const naechste = new Set(this.zugeklappt());
    if (!naechste.delete(name)) {
      naechste.add(name);
    }
    this.zugeklappt.set(naechste);
  }

  /**
   * Stunden statt Sekunden.
   *
   * <p>Ausdrücklich als Job-Stunden bezeichnet und nicht als Dauer: fünf Jobs zu
   * je zehn Stunden sind fünfzig Job-Stunden, laufen aber gleichzeitig, wenn
   * genug Slots frei sind. Wer das als Wandzeit liest, plant fünfmal zu lang.</p>
   */
  hours(seconds: number): string {
    if (!seconds) return '0 h';
    const h = seconds / 3600;
    return h < 1 ? `${Math.round(seconds / 60)} min` : `${h.toFixed(1)} h`;
  }

  /**
   * Wie lange ein laufender Job noch braucht.
   *
   * Bewusst grob und kurz: unter dem Namen eines Bauteils ist Platz für „2 T 5 h",
   * nicht für einen Zeitstempel. Wer es genau wissen will, schaut ins Spiel; hier
   * zählt die Größenordnung.
   *
   * Die Gegenwart kommt als Parameter herein statt aus `Date.now()`. Sonst hängt
   * das Ergebnis an der Uhr des Testrechners, und ein Test, der um Mitternacht
   * anders ausgeht als um zwölf, wird beim ersten Mal gelöscht.
   *
   * @param endDate ISO-Zeitstempel des Job-Endes, oder null
   * @returns kurzer Text, oder null wenn es nichts zu sagen gibt
   */
  remaining(endDate: string | null | undefined, now: number = Date.now()): string | null {
    if (!endDate) return null;
    const ende = Date.parse(endDate);
    if (Number.isNaN(ende)) return null;

    const sekunden = Math.round((ende - now) / 1000);
    if (sekunden <= 0) return 'fertig';
    if (sekunden < 60) return 'gleich fertig';

    const minuten = Math.floor(sekunden / 60);
    if (minuten < 60) return `${minuten} min`;

    const stunden = Math.floor(minuten / 60);
    if (stunden < 24) {
      const restMinuten = minuten % 60;
      return restMinuten ? `${stunden} h ${restMinuten} min` : `${stunden} h`;
    }

    const tage = Math.floor(stunden / 24);
    const restStunden = stunden % 24;
    return restStunden ? `${tage} T ${restStunden} h` : `${tage} T`;
  }

  /** Große Zahlen mit Tausenderpunkten - sonst zählt niemand die Nullen. */
  amount(value: number): string {
    return (value ?? 0).toLocaleString('de-DE');
  }

  volume(cubicMeters: number): string {
    if (!cubicMeters) return '—';
    return `${this.amount(Math.round(cubicMeters))} m³`;
  }

  /** Ein Etikett, das sagt, woher ein Material kommt. */
  kindLabel(kind: string): string {
    switch (kind) {
      case 'MINERAL':
        return 'Mineral';
      case 'REACTION':
        return 'Reaktion';
      case 'BUILDABLE':
        // "Fertigung", nicht "Baubar": Das eine benennt die Tätigkeit, das
        // andere nur eine Möglichkeit - und stand im Widerspruch zum Etikett
        // "Fertigung · 25 Läufe" des laufenden Jobs in derselben Zeile.
        return 'Fertigung';
      case 'PI':
        return 'PI – nicht per Industriejob baubar';
      case 'GAS':
        return 'Gas';
      default:
        return 'Rohstoff';
    }
  }

  /** Wie weit ein Material den Bedarf deckt, für den Balken in der Zeile. */
  coverage(row: Requirement): number {
    if (!row.needed) return 100;
    return Math.min(100, Math.round((row.have / row.needed) * 100));
  }

  trackByType = (_: number, row: Requirement) => row.typeId;
  trackByOrder = (_: number, row: OrderSummary) => row.id;
  trackByHit = (_: number, row: ProductHit) => row.typeId;
}
