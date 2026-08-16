import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Ein Treffer der Produktsuche. */
export interface ProductHit {
  typeId: number;
  typeName: string;
  groupName: string;
  blueprintTypeId: number;
}

/**
 * Die vier Zahlen, die vor jeder Tiefenrechnung dastehen.
 *
 * `jobSeconds` ist Ofenzeit, nicht Wandzeit: fünf Jobs zu je zehn Stunden
 * ergeben fünfzig Job-Stunden, laufen aber parallel, wenn genug Slots frei sind.
 */
export interface PlanSummary {
  jobCount: number;
  runsPerJob: number;
  totalRuns: number;
  jobSeconds: number;
  materialCount: number;
  packagedVolume: number;
  materialEfficiency: number;
  timeEfficiency: number;
  blueprintFound: boolean;
  /**
   * Ob das Konto eine passende Blaupause besitzt.
   *
   * Bewusst ein eigenes Feld: eine unerforschte Blaupause hat ebenfalls ME 0,
   * und "nicht erforscht" ist etwas anderes als "gar nicht vorhanden".
   */
  blueprintOwned: boolean;
}

/** Eine Zeile der Bedarfstabelle. */
export interface Requirement {
  typeId: number;
  typeName: string;
  needed: number;
  /**
   * Wie viel davon **im Bausystem** liegt. Ohne gewähltes Bausystem der gesamte
   * Bestand - dann meint die Zahl wieder ganz EVE.
   */
  have: number;
  /**
   * Wie viel im übrigen EVE liegt.
   *
   * Bewusst mitgeführt statt verschwiegen: wer 32,9 Millionen Pyerite in Delve
   * hat und in Branch baut, soll sie nicht ein zweites Mal kaufen, sondern
   * zwischen Schleppen und Kaufen wählen können.
   */
  haveElsewhere: number;
  /** Was fehlt - gerechnet gegen `have`, nicht gegen die Summe aus beiden. */
  missing: number;
  /** MINERAL, PI, REACTION, BUILDABLE, GAS oder RAW. */
  sourceKind: string;
  /** Ob "Bauen" überhaupt angeboten werden darf - bei PI-Gütern nicht. */
  buildable: boolean;
  decision: string;
  /**
   * Was entfällt, weil das Bauteil darüber schon fertig ist.
   *
   * Strikt getrennt von `have`: `have` heißt „liegt greifbar im Hangar",
   * `alreadyBuilt` heißt „steckt schon im Bauteil". Wer beides zu einer Zahl
   * verrührt, kann nicht mehr sagen, ob er noch etwas holen muss.
   */
  alreadyBuilt: number;
  depth: number;
  /**
   * Die Fertigungsstufe aus dem Backend: 0 wird beschafft, darüber wird gebaut.
   *
   * Hierauf gehört die Reihenfolge der Anzeige — und **nicht** auf `depth` oder
   * `parentTypeId`. Beide taugen dafür nicht: `depth` ist der kürzeste Weg zum
   * Endprodukt und wächst von ihm weg, während die Stufe auf es zu wächst.
   * `parentTypeId` nennt nur *einen* Verbraucher, obwohl ein Material oft viele
   * hat — in einem gemessenen Phoenix-Auftrag hatte Reinforced Carbon Fiber
   * siebzehn. Aus einer solchen Liste lässt sich die Ordnung nicht wieder
   * herstellen, deshalb rechnet sie das Backend über den ganzen Graphen.
   */
  buildLevel: number;
  parentTypeId: number | null;
  unitPrice: number | null;
  priceMissing: boolean;
  packagedVolume: number;
  onCharacters: number;
}

export interface PlanPreview {
  productTypeId: number;
  productName: string;
  quantity: number;
  summary: PlanSummary;
  requirements: Requirement[];
}

export interface Progress {
  target: number;
  delivered: number;
  inProgress: number;
  percent: number;
  /** Für wie viele Endprodukte das Material reicht - eine Bestandsaussage, kein Fortschritt. */
  coveredUnits: number;
  openJobs: number;
}

export interface OrderSummary {
  id: number;
  productTypeId: number;
  productName: string;
  targetQuantity: number;
  status: string;
  buildLocationName: string | null;
  progress: Progress;
  createdAt: string | null;
}

/**
 * Ein Industriejob, wie ihn die Oberfläche zeigt.
 *
 * `assignedToOrder` unterscheidet zwei sehr verschiedene Dinge: ein gebuchter
 * Job zählt in den Fortschritt, ein loser Treffer ist nur eine Vermutung über
 * den Typ — er läuft auf etwas, das dieser Auftrag auch braucht, könnte aber
 * einem anderen gelten. Beides gleich darzustellen wäre eine Behauptung.
 */
export interface Job {
  jobId: number;
  activityLabel: string;
  productTypeId: number | null;
  productName: string;
  runs: number;
  status: string;
  /** ISO-Zeitstempel des Job-Endes. */
  endDate: string | null;
  assignedToOrder: boolean;
}

/** Eine Materialkante: `materialTypeId` geht in `productTypeId` ein. */
export interface MaterialEdge {
  productTypeId: number;
  materialTypeId: number;
}

export interface OrderDetail {
  order: OrderSummary;
  summary: PlanSummary;
  requirements: Requirement[];
  /**
   * Der vollständige Stücklistengraph des Auftrags.
   *
   * Getrennt von `requirements`, weil eine Zeile je Typ nur *eine*
   * Elternangabe tragen kann — Reinforced Carbon Fiber hat in einem
   * gemessenen Phoenix-Auftrag siebzehn Verbraucher. Ohne diese Liste
   * ließe sich nicht sagen, ob ein Bauteil wirklich startklar ist,
   * sondern nur, ob der eine bekannte Zweig es hergibt.
   */
  edges: MaterialEdge[];
  jobs: Job[];
}

/**
 * Ein möglicher Bauort.
 *
 * `servicesKnown` ist der ehrliche Teil: für fremde Strukturen verrät ESI die
 * Dienste nicht. Ist das Feld `false`, bedeuten die drei Flaggen darunter
 * "unbekannt" und nicht "nein" — die Oberfläche muss das auch so sagen.
 */
export interface BuildLocation {
  structureId: number;
  name: string | null;
  systemName: string | null;
  systemId: number | null;
  security: number | null;
  typeName: string | null;
  /** CORP, PUBLIC oder NPC. */
  source: string;
  servicesKnown: boolean;
  manufacturing: boolean;
  reprocessing: boolean;
  reactions: boolean;
  /** Was sich hier anfangen lässt — aus dem Strukturtyp abgeleitet. */
  hints: string[];
}

/** Eine Zeile der Einkaufsliste. */
export interface ProcurementLine {
  typeId: number;
  typeName: string;
  neededQuantity: number;
  /** DIRECT oder ORE. */
  source: string;
  buyTypeId: number | null;
  buyTypeName: string | null;
  buyQuantity: number;
  purchaseCost: number | null;
  volume: number;
  /** Ware plus Transport — danach wird verglichen, nicht nach dem Einkaufspreis. */
  totalCost: number | null;
  alternative: number | null;
  saving: number;
  note: string | null;
}

/** Die ganze Einkaufsliste samt Weg. */
export interface Procurement {
  jumpsFromJita: number | null;
  /** Ob überhaupt ein Bauort feststeht — "noch nicht gewählt" ist kein Problem. */
  locationChosen: boolean;
  transport: string;
  transportLabel: string;
  freightPerCubicMeter: number;
  loadCapacity: number;
  goodsCost: number;
  freightCost: number;
  totalCost: number;
  volume: number;
  loads: number;
  /** Zeilen ohne Marktpreis — muss sichtbar sein, sonst wirkt die Summe vollständig. */
  withoutPrice: number;
  /**
   * Warum kein Erz auf der Liste steht — oder warum doch.
   *
   * Ohne diesen Satz trifft der Assistent die Entscheidung unsichtbar, und wer
   * Erz erwartet, hält das Fehlen für einen Fehler statt für ein Ergebnis.
   */
  oreVerdict: string | null;
  /** Wie nah das beste Erz an die Rentabilitätsschwelle kommt. Unter 1,0 verliert es. */
  oreFactor: number | null;
  lines: ProcurementLine[];
}

/**
 * Die Lage zu einer Blaupause, die der Auftrag braucht.
 *
 * `availableRuns` ist −1 bei einem Original — das hat unbegrenzt Läufe.
 * Null heißt: gar keine Blaupause vorhanden.
 */
export interface BlueprintCheck {
  productTypeId: number;
  productName: string;
  blueprintTypeId: number;
  neededRuns: number;
  availableRuns: number;
  owned: boolean;
  sufficient: boolean;
  materialEfficiency: number;
  timeEfficiency: number;
  /** Ob dieser Teil tatsächlich gebaut werden soll - sonst nur eine Auskunft. */
  required: boolean;
  /** "Blaupause" oder "Reaktionsformel" - ingame heißen sie verschieden. */
  kind: string;
  note: string | null;
}

/** Was ein Blaupausen-Abruf ergeben hat. */
export interface BlueprintSync {
  written: number;
  withoutAccess: number;
  /** Namen der Charaktere ohne Zugriff - meist ein abgelaufener Refresh-Token. */
  characters: string[];
}

/** Zugriff auf den Industrie-Assistenten. */
@Injectable({ providedIn: 'root' })
export class IndustryService {
  private http = inject(HttpClient);

  /**
   * `environment.apiUrl` trägt das `/api` bereits - hier darf es nicht noch
   * einmal stehen, sonst entsteht `/api/api/industry`. Der Server antwortet
   * darauf mit 500, weil er den Pfad für eine statische Datei hält.
   */
  private readonly base = `${environment.apiUrl}/industry`;

  search(query: string): Observable<ProductHit[]> {
    return this.http.get<ProductHit[]>(`${this.base}/search`, { params: { q: query } });
  }

  /**
   * Rechnet einen Bauwunsch durch, ohne etwas anzulegen.
   *
   * `depth` bleibt bewusst bei 1: der Bildschirm zeigt zuerst nur die
   * unmittelbaren Materialien. Ein Titan brächte sonst über hundert Zeilen mit.
   */
  preview(
    productTypeId: number,
    quantity: number,
    depth = 1,
    buildSystemId: number | null = null,
  ): Observable<PlanPreview> {
    // Ohne Bausystem zählt der Bestand aus ganz EVE - eine Zahl, die beim Bauen
    // an einem bestimmten Ort zu hoch ist. Deshalb geht der Ort mit, sobald er
    // gewählt wurde, und nicht erst beim Anlegen.
    return this.http.get<PlanPreview>(`${this.base}/preview`, {
      params: buildSystemId
        ? { productTypeId, quantity, depth, buildSystemId }
        : { productTypeId, quantity, depth },
    });
  }

  /** Bauorte nach Name, Strukturtyp oder System. */
  locations(query: string): Observable<BuildLocation[]> {
    return this.http.get<BuildLocation[]>(`${this.base}/locations`, { params: { q: query } });
  }

  orders(): Observable<OrderSummary[]> {
    return this.http.get<OrderSummary[]>(`${this.base}/orders`);
  }

  order(id: number): Observable<OrderDetail> {
    return this.http.get<OrderDetail>(`${this.base}/orders/${id}`);
  }

  create(
    productTypeId: number,
    quantity: number,
    buildSystemId?: number | null,
    buildLocationName?: string | null,
  ): Observable<OrderDetail> {
    return this.http.post<OrderDetail>(`${this.base}/orders`, {
      productTypeId,
      quantity,
      buildSystemId: buildSystemId ?? null,
      buildLocationName: buildLocationName ?? null,
    });
  }

  /**
   * Setzt alle Kaufen/Bauen-Entscheidungen nach einer Voreinstellung.
   *
   * Danach lässt sich weiterhin jede Zeile einzeln umstellen — die
   * Voreinstellung ist ein Startpunkt, kein Zwang.
   */
  applyStrategy(
    orderId: number,
    strategy: 'BUY_ALL' | 'COST_EFFICIENT' | 'BUILD_ALL',
  ): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${this.base}/orders/${orderId}/strategy`, null, {
      params: { strategy },
    });
  }

  /**
   * Liest die Blaupausen sofort neu ein, statt auf den Zeitplan zu warten.
   *
   * Der Plan holt sie alle sechs Stunden. Wer gerade eine Blaupause gekauft hat
   * — oder den Verdacht, dass der Abruf gar nicht läuft — braucht eine Antwort
   * und keinen Zeitplan.
   */
  syncBlueprints(): Observable<BlueprintSync> {
    return this.http.post<BlueprintSync>(`${this.base}/blueprints/sync`, null);
  }

  /** Reichen die vorhandenen Blaupausen samt ihrer Läufe? */
  blueprints(orderId: number): Observable<BlueprintCheck[]> {
    return this.http.get<BlueprintCheck[]>(`${this.base}/orders/${orderId}/blueprints`);
  }

  /** Die Einkaufsliste: was kaufen, wo, und was kostet der Weg. */
  procurement(orderId: number): Observable<Procurement> {
    return this.http.get<Procurement>(`${this.base}/orders/${orderId}/procurement`);
  }

  /** Stellt eine Zeile von Kaufen auf Bauen um - und löst dabei eine Ebene tiefer auf. */
  decide(orderId: number, typeId: number, decision: 'BUY' | 'BUILD'): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${this.base}/orders/${orderId}/decision`, {
      typeId,
      decision,
    });
  }

  /**
   * Setzt oder ändert das Bausystem eines bestehenden Auftrags.
   *
   * Ohne Bauort muss der Assistent beim Transport den teuersten Fall annehmen
   * und beim Bestand ganz EVE zusammenzählen. Beim Anlegen ist der Ort
   * freiwillig - hier lässt er sich nachtragen. Danach wird neu gerechnet.
   */
  setBuildLocation(
    orderId: number,
    buildSystemId: number | null,
    buildLocationId: number | null,
    buildLocationName: string | null,
  ): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${this.base}/orders/${orderId}/location`, {
      buildSystemId,
      buildLocationId,
      buildLocationName,
    });
  }

  /**
   * Rechnet einen bestehenden Auftrag von Grund auf neu.
   *
   * Die Bedarfstabelle ist eingefroren, damit der Fortschrittsbalken nicht bei
   * jedem Neuladen springt. Der Preis dafür: erforschte Blaupausen und geänderte
   * Marktpreise erreichen einen einmal angelegten Auftrag nie. Das hier ist der
   * Weg zurück; die Kaufen/Bauen-Entscheidungen bleiben erhalten.
   */
  recalculate(orderId: number): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${this.base}/orders/${orderId}/recalculate`, null);
  }

  cancel(orderId: number): Observable<void> {
    return this.http.put<void>(`${this.base}/orders/${orderId}/cancel`, {});
  }

  remove(orderId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/orders/${orderId}`);
  }
}
