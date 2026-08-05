import { Component, OnInit, inject, signal, computed, ViewChild, ElementRef, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import {
  AssetRowDto, AssetStackDto, PageDto,
  MemberAssetDetailDto, TypeSuggestionDto
} from '../../services/asset.service';
import { MyAssetService, MyFilterOptionsDto, MyAssetSearchParams } from '../../services/my-asset.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';

type Tab = 'OVERVIEW' | 'SEARCH';

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

  activeTab = signal<Tab>('OVERVIEW');
  showAdvancedFilters = signal(false);

  // --- Uebersicht ---
  summary = signal<MemberAssetDetailDto | null>(null);
  loadingSummary = signal(false);

  // --- Suche ---
  filters = signal<MyFilterOptionsDto | null>(null);
  flatResult = signal<PageDto<AssetRowDto> | null>(null);
  groupedResult = signal<PageDto<AssetStackDto> | null>(null);
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
  private searchTerms = new Subject<string>();

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

    this.searchTerms.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(term => this.myAssetService.suggestTypes(term))
    ).subscribe({
      next: (res) => { this.suggestions.set(res); this.showSuggestions.set(res.length > 0); },
      error: () => this.suggestions.set([])
    });
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
    if (term && term.length >= 2) this.searchTerms.next(term);
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

  clearTypeFilter() {
    this.f.typeId = null;
    this.f.q = '';
    this.showSuggestions.set(false);
    this.runSearch();
  }

  /**
   * Laufende Nummer der jüngsten Suchanfrage.
   *
   * <p>Ohne diese Absicherung kann eine ältere, langsamere Antwort eine neuere
   * überschreiben - genau das passierte beim Sprung aus der Standort-Liste in
   * die Suche: die ungefilterte Anfrage brauchte länger als die gefilterte und
   * landete zuletzt im Signal.</p>
   */
  private searchSeq = 0;

  runSearch(resetPage: boolean = true) {
    if (resetPage) this.f.page = 0;
    this.loadingSearch.set(true);
    this.showSuggestions.set(false);

    const seq = ++this.searchSeq;
    const isCurrent = () => seq === this.searchSeq;

    if (this.grouped()) {
      this.myAssetService.searchGrouped(this.f).subscribe({
        next: (res) => {
          if (!isCurrent()) return;
          this.groupedResult.set(res); this.flatResult.set(null); this.loadingSearch.set(false);
        },
        error: () => {
          if (!isCurrent()) return;
          this.loadingSearch.set(false); this.toastService.error('Suche fehlgeschlagen.');
        }
      });
    } else {
      this.myAssetService.search(this.f).subscribe({
        next: (res) => {
          if (!isCurrent()) return;
          this.flatResult.set(res); this.groupedResult.set(null); this.loadingSearch.set(false);
        },
        error: () => {
          if (!isCurrent()) return;
          this.loadingSearch.set(false); this.toastService.error('Suche fehlgeschlagen.');
        }
      });
    }
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

  // ================= Formatierung =================
  formatIsk(value: number | null | undefined): string {
    if (value === null || value === undefined || isNaN(value)) return '0 ISK';
    const abs = Math.abs(value);
    if (abs >= 1_000_000_000_000) return (value / 1_000_000_000_000).toFixed(2) + ' T ISK';
    if (abs >= 1_000_000_000) return (value / 1_000_000_000).toFixed(2) + ' B ISK';
    if (abs >= 1_000_000) return (value / 1_000_000).toFixed(2) + ' M ISK';
    if (abs >= 1_000) return (value / 1_000).toFixed(1) + ' k ISK';
    return value.toFixed(0) + ' ISK';
  }

  formatIskFull(value: number | null | undefined): string {
    if (value === null || value === undefined || isNaN(value)) return '0 ISK';
    return value.toLocaleString('de-DE', { maximumFractionDigits: 0 }) + ' ISK';
  }

  formatNumber(value: number | null | undefined): string {
    if (value === null || value === undefined || isNaN(value)) return '0';
    return value.toLocaleString('de-DE');
  }

  barWidth(value: number, max: number): string {
    if (!max || max <= 0) return '0%';
    return Math.max(2, (value / max) * 100).toFixed(1) + '%';
  }

  maxValue(items: { value: number }[] | undefined): number {
    if (!items || items.length === 0) return 0;
    return Math.max(...items.map(i => i.value));
  }

  typeIcon(typeId: number): string {
    return `https://images.evetech.net/types/${typeId}/icon?size=64`;
  }

  onImgError(event: Event) {
    const target = event.target as HTMLImageElement;

    // Verhindert eine Endlosschleife, wenn selbst das Tritanium-Bild fehlen sollte
    if (target.src.includes('/34/icon')) return;

    if (target.src.includes('/icon?') || target.src.includes('/render?')) {
      const match = target.src.match(/\/types\/(\d+)\//);
      if (match) {
        target.src = `https://images.evetech.net/types/${match[1]}/bp?size=64`;
        return;
      }
    }
    target.src = 'https://images.evetech.net/types/34/icon?size=64';
  }
}
