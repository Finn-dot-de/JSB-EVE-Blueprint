import { Component, OnInit, inject, signal, computed, ViewChild, ElementRef, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { map } from 'rxjs/operators';
import { FormsModule } from '@angular/forms';
import {
  AssetRowDto, AssetStackDto, PageDto,
  MemberAssetDetailDto, TypeSuggestionDto
} from '../../services/asset.service';
import { AssetPlacementDto, MyAssetService, MyFilterOptionsDto, MyAssetSearchParams } from '../../services/my-asset.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { barWidth, formatIsk, formatIskFull, formatNumber, maxValue } from '../../shared/eve-format.util';
import { handleTypeImageError, typeIcon } from '../../shared/eve-image.util';
import { latestRequest } from '../../shared/latest-request.util';

type Tab = 'OVERVIEW' | 'SEARCH';

/** Ein Suchergebnis samt der Darstellung, für die es angefordert wurde. */
type SearchOutcome =
  | { readonly grouped: true; readonly page: PageDto<AssetStackDto> }
  | { readonly grouped: false; readonly page: PageDto<AssetRowDto> };

/**
 * "Meine Assets" - die Selbstauskunft fuer Mitglieder.
 *
 * <p>Bewusst schlanker als das Asset-Audit der Direktoren: kein "Wer hat das?",
 * keine Corp-Kennzahlen, keine Account-Auswahl. Der Umfang ist der eigene
 * Account, also der Main samt aller Alts - erzwungen wird das serverseitig,
 * dieses Frontend schickt gar keine Account-Parameter mit.</p>
 */
@Component({
  selector: 'app-my-assets',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './my-assets.component.html',
  styleUrls: ['./my-assets.component.scss']
})
export class MyAssetsComponent implements OnInit {
  private myAssetService = inject(MyAssetService);
  public authService = inject(AuthService);
  private toastService = inject(ToastService);

  // Formatierung und Bildadressen kommen aus den gemeinsamen Utilities -
  // hier werden sie nur noch fuer das Template sichtbar gemacht.
  protected readonly formatIsk = formatIsk;
  protected readonly formatIskFull = formatIskFull;
  protected readonly formatNumber = formatNumber;
  protected readonly barWidth = barWidth;
  protected readonly maxValue = maxValue;
  protected readonly typeIcon = typeIcon;
  protected readonly onImgError = handleTypeImageError;

  activeTab = signal<Tab>('OVERVIEW');
  showAdvancedFilters = signal(false);

  // --- Uebersicht ---
  summary = signal<MemberAssetDetailDto | null>(null);
  loadingSummary = signal(false);

  // --- Suche ---
  filters = signal<MyFilterOptionsDto | null>(null);
  flatResult = signal<PageDto<AssetRowDto> | null>(null);
  groupedResult = signal<PageDto<AssetStackDto> | null>(null);

  /**
   * Der aufgeklappte Bestand und seine Orte.
   *
   * Die gruppierte Sicht nennt nur die Zahl der Orte - erst hier steht, wo die
   * Gegenstände tatsächlich liegen. Auf Abruf geladen, weil die Aufschlüsselung
   * für jede Zeile der Seite den Aufwand nicht wert wäre.
   */
  expandedTypeId = signal<number | null>(null);
  placements = signal<AssetPlacementDto[]>([]);
  loadingPlacements = signal(false);
  loadingSearch = signal(false);
  grouped = signal(true);

  f: MyAssetSearchParams = {
    q: '', typeId: null, groupId: null, categoryId: null,
    characterId: null, locationId: null, regionName: null, locationFlag: null,
    minQuantity: null, minValue: null, shipsOnly: false,
    sort: 'value', direction: 'desc', page: 0, size: 50
  };

  // --- Typeahead ---
  suggestions = signal<TypeSuggestionDto[]>([]);
  showSuggestions = signal(false);

  /**
   * Typeahead: wartet den Tippfluss ab und wertet immer nur die jüngste
   * Eingabe aus. Ein Fehlschlag leert die Liste, lässt den Auslöser aber
   * benutzbar - sonst bliebe das Feld für den Rest der Sitzung stumm.
   */
  private readonly requestSuggestions = latestRequest<string, TypeSuggestionDto[]>({
    debounceMs: 250,
    distinct: true,
    run: (term) => this.myAssetService.suggestTypes(term),
    next: (found) => {
      this.suggestions.set(found);
      this.showSuggestions.set(found.length > 0);
    },
    error: () => this.suggestions.set([]),
  });

  @ViewChild('typeaheadWrapper') typeaheadWrapper?: ElementRef;

  @HostListener('document:click', ['$event'])
  onClickOutside(event: Event) {
    if (this.showSuggestions() && this.typeaheadWrapper
      && !this.typeaheadWrapper.nativeElement.contains(event.target)) {
      this.showSuggestions.set(false);
    }
  }

  currentTotal = computed(() =>
    this.grouped() ? this.groupedResult()?.totalElements ?? 0 : this.flatResult()?.totalElements ?? 0);
  currentPages = computed(() =>
    this.grouped() ? this.groupedResult()?.totalPages ?? 0 : this.flatResult()?.totalPages ?? 0);
  grandTotal = computed(() =>
    this.grouped() ? this.groupedResult()?.grandTotalValue ?? 0 : this.flatResult()?.grandTotalValue ?? 0);

  /** Grösster Kategoriewert - Bezugsgrösse für die Balkenbreite. */
  maxCategoryValue = computed(() => this.maxValue(this.summary()?.byCategory));
  maxLocationValue = computed(() => this.maxValue(this.summary()?.byLocation));

  ngOnInit() {
    this.loadSummary();
    this.loadFilters();
  }

  setTab(tab: Tab) {
    this.activeTab.set(tab);
    if (tab === 'OVERVIEW' && !this.summary()) this.loadSummary();
    if (tab === 'SEARCH' && !this.flatResult() && !this.groupedResult()) this.runSearch();
  }

  // ================= Uebersicht =================
  loadSummary() {
    this.loadingSummary.set(true);
    this.myAssetService.summary().subscribe({
      next: (data) => { this.summary.set(data); this.loadingSummary.set(false); },
      error: () => {
        this.loadingSummary.set(false);
        this.toastService.error('Deine Asset-Übersicht konnte nicht geladen werden.');
      }
    });
  }

  // ================= Suche =================
  loadFilters() {
    this.myAssetService.filters(this.f.categoryId).subscribe({
      next: (data) => this.filters.set(data),
      error: () => this.toastService.error('Filter-Optionen konnten nicht geladen werden.')
    });
  }

  onCategoryChange() {
    this.f.groupId = null;
    this.loadFilters();
    this.runSearch();
  }

  onTypeAhead(term: string) {
    this.f.q = term;
    if (term && term.length >= 2) this.requestSuggestions(term);
    else this.showSuggestions.set(false);
  }

  onSearchFocus() {
    if (this.f.q && this.f.q.length >= 2 && this.suggestions().length > 0) {
      this.showSuggestions.set(true);
    }
  }

  pickSuggestion(s: TypeSuggestionDto) {
    this.f.q = s.typeName;
    this.f.typeId = s.typeId;
    this.showSuggestions.set(false);
    this.runSearch();
  }

  /** Klappt einen Bestand auf und holt seine Orte. */
  togglePlacements(typeId: number) {
    if (this.expandedTypeId() === typeId) {
      this.expandedTypeId.set(null);
      return;
    }

    this.expandedTypeId.set(typeId);
    this.placements.set([]);
    this.loadingPlacements.set(true);
    // Dieselben Filter wie die Suche, damit die Aufschlüsselung zu dem passt,
    // was gerade auf dem Schirm steht.
    this.myAssetService.placements({ ...this.f, typeId }).subscribe({
      next: (rows) => {
        this.placements.set(rows);
        this.loadingPlacements.set(false);
      },
      error: () => {
        this.loadingPlacements.set(false);
        this.toastService.error('Die Standorte konnten nicht geladen werden.');
      },
    });
  }

  /**
   * Der Ort in einer Zeile: Station, davor das Fach, davor der Behälter.
   *
   * Von innen nach außen gelesen - so, wie man ingame danach sucht.
   */
  placementPath(row: AssetPlacementDto): string {
    const parts: string[] = [];
    if (row.containerName || row.containerTypeName) {
      parts.push(row.containerName
        ? `${row.containerName} (${row.containerTypeName ?? 'Behälter'})`
        : row.containerTypeName!);
    }
    if (row.locationFlag) parts.push(this.flagLabel(row.locationFlag));
    return parts.join(' in ');
  }

  /** Die ESI-Fachbezeichnungen sind englische Kürzel - die gängigen übersetzt. */
  flagLabel(flag: string): string {
    const labels: Record<string, string> = {
      Hangar: 'Hangar',
      ShipHangar: 'Schiffshangar',
      Deliveries: 'Lieferungen',
      AssetSafety: 'Asset Safety',
      Cargo: 'Frachtraum',
      DroneBay: 'Drohnenbucht',
      FleetHangar: 'Flottenhangar',
      Unlocked: 'Nicht verankert',
      Locked: 'Verankert',
    };
    return labels[flag] ?? flag;
  }

  clearTypeFilter() {
    this.f.typeId = null;
    this.f.q = '';
    this.showSuggestions.set(false);
    this.runSearch();
  }

  /**
   * Stösst die Suche an - eine noch laufende wird dabei abgebrochen.
   *
   * <p>Nötig, weil eine ältere, langsamere Antwort sonst eine neuere
   * überschreibt: genau das passierte beim Sprung aus der Standort-Liste in
   * die Suche, wo die ungefilterte Anfrage länger brauchte als die gefilterte.</p>
   *
   * <p>Welche Darstellung gemeint war, wandert im Ergebnis mit. Sonst könnte
   * zwischen Absenden und Antwort umgeschaltet worden sein und das Ergebnis
   * landete im falschen Signal.</p>
   */
  private readonly requestSearch = latestRequest<boolean, SearchOutcome>({
    run: (grouped) =>
      grouped
        ? this.myAssetService
            .searchGrouped(this.f)
            .pipe(map((page): SearchOutcome => ({ grouped: true, page })))
        : this.myAssetService
            .search(this.f)
            .pipe(map((page): SearchOutcome => ({ grouped: false, page }))),
    next: (outcome) => {
      this.groupedResult.set(outcome.grouped ? outcome.page : null);
      this.flatResult.set(outcome.grouped ? null : outcome.page);
      this.loadingSearch.set(false);
    },
    error: () => {
      this.loadingSearch.set(false);
      this.toastService.error('Suche fehlgeschlagen.');
    },
  });

  runSearch(resetPage: boolean = true) {
    if (resetPage) this.f.page = 0;
    this.loadingSearch.set(true);
    this.showSuggestions.set(false);
    // Die gewünschte Darstellung wandert mit: zwischen Absenden und Antwort
    // kann umgeschaltet worden sein.
    this.requestSearch(this.grouped());
  }

  toggleGrouped() {
    this.grouped.update(v => !v);
    this.runSearch();
  }

  sortBy(column: string) {
    if (this.f.sort === column) this.f.direction = this.f.direction === 'desc' ? 'asc' : 'desc';
    else { this.f.sort = column; this.f.direction = 'desc'; }
    this.runSearch();
  }

  sortIcon(column: string): string {
    if (this.f.sort !== column) return 'fa-solid fa-sort';
    return this.f.direction === 'desc' ? 'fa-solid fa-sort-down' : 'fa-solid fa-sort-up';
  }

  nextPage() {
    if ((this.f.page ?? 0) + 1 < this.currentPages()) {
      this.f.page = (this.f.page ?? 0) + 1;
      this.runSearch(false);
    }
  }

  prevPage() {
    if ((this.f.page ?? 0) > 0) {
      this.f.page = (this.f.page ?? 0) - 1;
      this.runSearch(false);
    }
  }

  /** Die Ausgangswerte der Suche - bewusst ohne Seiteneffekte. */
  private defaultFilters(): MyAssetSearchParams {
    return {
      q: '', typeId: null, groupId: null, categoryId: null,
      characterId: null, locationId: null, regionName: null, locationFlag: null,
      minQuantity: null, minValue: null, shipsOnly: false,
      sort: 'value', direction: 'desc', page: 0, size: 50
    };
  }

  resetFilters() {
    this.f = this.defaultFilters();
    this.loadFilters();
    this.runSearch();
  }

  exportCsv() {
    this.myAssetService.exportCsv({ ...this.f, grouped: this.grouped() }).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `meine-assets-${new Date().toISOString().slice(0, 10)}.csv`;
        link.click();
        window.URL.revokeObjectURL(url);
        this.toastService.success('Export erstellt (max. 500 Zeilen).');
      },
      error: () => this.toastService.error('Export fehlgeschlagen.')
    });
  }

  /**
   * Springt aus der Übersicht heraus gefiltert in die Suche.
   *
   * <p>Setzt die Filter direkt zurück statt über {@link resetFilters} - das würde
   * sonst eine zusätzliche, ungefilterte Suche auslösen, die mit der gefilterten
   * um das Ergebnis konkurriert.</p>
   */
  drillIntoLocation(locationId: number) {
    this.f = { ...this.defaultFilters(), locationId };
    this.activeTab.set('SEARCH');
    // Filterpanel aufklappen, damit sichtbar ist, dass ein Ort gesetzt wurde.
    this.showAdvancedFilters.set(true);
    this.runSearch();
  }

}
