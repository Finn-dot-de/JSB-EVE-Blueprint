import { Component, inject, OnInit, OnDestroy, ChangeDetectorRef, NgZone } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  BuybotService,
  ParsedItemDto,
  BuybackLocation,
  PublicConfig,
  BotTexts,
  InjectorPrice
} from '../../services/buybot.service';
import { DecimalPipe, NgClass } from '@angular/common';
import { ADMIN_ROLES, AuthService } from '../../services/auth.service';
import { I18nService } from '../../services/i18n.service';
import { BuybotAdminComponent } from '../buybot-admin/buybot-admin.component';

type TutorialTab = 'flow' | 'price' | 'status' | 'contract';

@Component({
  selector: 'app-buybot',
  standalone: true,
  imports: [FormsModule, DecimalPipe, NgClass, BuybotAdminComponent],
  templateUrl: './buybot.component.html',
  styleUrls: ['./buybot.component.scss']
})
export class BuybotComponent implements OnInit, OnDestroy {
  private buybotService = inject(BuybotService);
  private ngZone = inject(NgZone);
  private cdr = inject(ChangeDetectorRef);
  private authService = inject(AuthService);
  readonly i18n = inject(I18nService);

  rawInput: string = '';

  // Locations dynamisch laden
  locations: BuybackLocation[] = [];
  locationId: number | null = null;

  items: ParsedItemDto[] = [];
  totalPrice: number | null = null;
  totalVolume: number = 0;
  isCalculating: boolean = false;
  copiedField: 'price' | 'recipient' | 'contractPrice' | null = null;

  // Öffentliche Konfiguration (Wartung, Sprüche, Schwellen, Vertragsdaten)
  config: PublicConfig | null = null;

  // Jita-Preis eines Large Skill Injectors für das Badge in der Kopfzeile
  injector: InjectorPrice | null = null;

  // State für Uhr und Modals
  currentTime: string = '';
  showTutorial: boolean = false;
  tutorialTab: TutorialTab = 'flow';
  showAdmin: boolean = false;
  mobileGateDismissed: boolean = false;
  isNarrowScreen: boolean = false;
  private clockInterval: any;
  private destroyed = false;

  // Bot Animation State
  botFace: string = '&middot;_&middot;';
  botSpeech: string = '';
  private typingToken: number = 0;

  ngOnInit() {
    this.evaluateScreen();

    this.say('idle', this.botText('idle'));

    this.updateClock();
    // Außerhalb der Zone ausführen, um globale Change Detection zu verhindern
    this.ngZone.runOutsideAngular(() => {
      this.clockInterval = setInterval(() => {
        if (this.destroyed) return;
        this.updateClock();
        this.cdr.detectChanges(); // UI-Update nur für diese Komponente
      }, 1000);
    });

    // Öffentliche Konfiguration laden (Wartungsmodus, Bot-Sprüche, Vertragsdaten)
    this.buybotService.getPublicConfig().subscribe({
      next: (cfg) => {
        this.config = cfg;
        this.say(cfg.botEnabled ? 'idle' : 'warn',
          cfg.botEnabled ? this.botText('idle') : this.i18n.t('bot.maintenance'));
      },
      error: () => {
        // Ohne Config läuft der Bot mit den eingebauten Texten weiter
        this.config = null;
      }
    });

    this.loadInjectorPrice();

    // Lade die Abgabeorte dynamisch aus der Datenbank
    this.buybotService.getLocations().subscribe({
      next: (locs) => {
        if (locs && locs.length > 0) {
          this.locations = locs;
          this.locationId = locs[0].id;
        } else {
          // Fallback, falls die Datenbanktabelle leer ist
          this.locations = [{ id: 1, name: 'Jita IV (Fallback - DB Leer)', transportFee: 0, securityFee: 0, stationId: 60003760 }];
          this.locationId = 1;
        }
      },
      error: () => {
        this.say('error', this.i18n.t('bot.locationsError'));
        this.locations = [{ id: 1, name: 'Fehler beim Laden (Jita)', transportFee: 0, securityFee: 0, stationId: 60003760 }];
        this.locationId = 1;
      }
    });
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.narrowQuery?.removeEventListener('change', this.onNarrowChange);
    // Intervall sauber beenden, wenn die Komponente zerstört wird
    if (this.clockInterval) {
      clearInterval(this.clockInterval);
    }
  }

  // =========================================================
  // UHR - laut Protokoll EVE-Zeit (UTC), nicht deutsche Zeit
  // =========================================================
  private updateClock() {
    const now = new Date();
    const p = (n: number) => String(n).padStart(2, '0');
    this.currentTime = `${now.getUTCFullYear()}.${p(now.getUTCMonth() + 1)}.${p(now.getUTCDate())} `
      + `${p(now.getUTCHours())}:${p(now.getUTCMinutes())}:${p(now.getUTCSeconds())}`;
  }

  // =========================================================
  // SKILL-INJEKTOREN - Badge in der Kopfzeile
  // =========================================================
  private loadInjectorPrice() {
    this.buybotService.getInjectorPrice().subscribe({
      next: (p) => (this.injector = p.price > 0 ? p : null),
      error: () => (this.injector = null)
    });
  }

  /** Wie viele Large Skill Injectors der Ankaufspreis kauft. */
  get injectorCount(): number | null {
    if (!this.injector || this.injector.price <= 0 || this.totalPrice === null) {
      return null;
    }
    return this.totalPrice / this.injector.price;
  }

  get injectorTooltip(): string {
    if (!this.injector) {
      return this.i18n.t('header.injectorUnavailable');
    }
    const price = this.injector.price.toLocaleString(this.i18n.locale, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 0
    });
    return this.i18n.t('header.injectorTooltip', this.injector.name, price);
  }

  // =========================================================
  // WARTUNGSMODUS & HANDY-WEICHE
  // =========================================================
  private narrowQuery: MediaQueryList | null = null;

  private onNarrowChange = (event: MediaQueryListEvent) => {
    this.ngZone.run(() => {
      this.isNarrowScreen = event.matches;
      this.cdr.detectChanges();
    });
  };

  private evaluateScreen() {
    if (!this.narrowQuery) {
      this.narrowQuery = window.matchMedia('(max-width: 719px)');
      this.narrowQuery.addEventListener('change', this.onNarrowChange);
    }
    this.isNarrowScreen = this.narrowQuery.matches;
  }

  get isMaintenance(): boolean {
    return this.config !== null && this.config.botEnabled === false;
  }

  get maintenanceTitle(): string {
    return this.config?.maintenanceTitle?.trim() || this.i18n.t('maintenance.title');
  }

  get maintenanceMessage(): string {
    return this.config?.maintenanceMessage?.trim() || this.i18n.t('maintenance.message');
  }

  get showMobileGate(): boolean {
    return this.isNarrowScreen && !this.mobileGateDismissed;
  }

  dismissMobileGate() {
    this.mobileGateDismissed = true;
  }

  toggleLang() {
    this.i18n.toggle();
    // Die aktuelle Sprechblase in der neuen Sprache neu setzen
    this.say('idle', this.isMaintenance ? this.i18n.t('bot.maintenance') : this.botText('idle'));
  }

  // =========================================================
  // BERECHNUNG
  // =========================================================
  calculate() {
    if (this.isMaintenance) {
      this.say('warn', this.i18n.t('bot.maintenance'));
      return;
    }
    if (!this.rawInput || this.rawInput.trim() === '') {
      this.say('warn', this.i18n.t('bot.noInput'));
      return;
    }

    // Check, ob ein Abgabeort gewählt wurde
    if (this.locationId === null) {
      this.say('warn', this.i18n.t('bot.noLocation'));
      return;
    }

    this.isCalculating = true;
    this.say('thinking', this.botText('thinking'));

    this.buybotService.calculateBuyback({ rawInput: this.rawInput, locationId: Number(this.locationId) })
      .subscribe({
        next: (res: ParsedItemDto[]) => {
          this.items = res;
          this.totalPrice = res.reduce((acc, item) => acc + (item.totalPrice || 0), 0);
          this.totalVolume = res.reduce((acc, item) => acc + ((item.volumeEach || 0) * item.quantity), 0);
          this.isCalculating = false;
          this.reactToResult(res);
          // Injector-Preis auffrischen (das Backend cached 5 Minuten)
          this.loadInjectorPrice();
        },
        error: (err) => {
          this.isCalculating = false;
          if (err?.status === 503) {
            this.config = this.config ? { ...this.config, botEnabled: false } : null;
            this.say('warn', this.i18n.t('bot.maintenance'));
            return;
          }
          // Fehler-ID mitgeben: Spieler sind meist nicht angemeldet, damit ist sie
          // der einzige Weg, den Vorgang im Protokoll wiederzufinden.
          const errorId = err?.error?.requestId ?? err?.headers?.get?.('X-Request-Id');
          this.say('error', errorId
            ? this.botText('error') + ' ' + this.i18n.t('bot.errorId', errorId)
            : this.botText('error'));
        }
      });
  }

  /** Reaktion des Bots: erst Probleme, dann die Schwellenwerte aus dem Admin-Panel. */
  private reactToResult(res: ParsedItemDto[]) {
    const hasUnknown = res.some(i => i.statusCode === 'UNKNOWN');
    const hasRejected = res.some(i => i.statusCode === 'BLOCKED' || i.statusCode === 'NOT_LISTED');

    if (hasRejected) {
      this.say('warn', this.botText('warnRejected'));
      return;
    }
    if (hasUnknown) {
      this.say('warn', this.botText('warnMissing'));
      return;
    }

    const itemThreshold = this.config?.itemValueThreshold;
    if (itemThreshold && res.some(i => (i.totalPrice || 0) >= itemThreshold)) {
      this.say('happy', this.botText('expensiveItem'));
      return;
    }
    const valueThreshold = this.config?.valueThreshold;
    if (valueThreshold && (this.totalPrice || 0) >= valueThreshold) {
      this.say('happy', this.botText('highValue'));
      return;
    }
    const volumeThreshold = this.config?.volumeThreshold;
    if (volumeThreshold && this.totalVolume >= volumeThreshold) {
      this.say('happy', this.botText('highVolume'));
      return;
    }

    this.say('happy', this.botText('success'));
  }

  clear() {
    this.rawInput = '';
    this.items = [];
    this.totalPrice = null;
    this.totalVolume = 0;
    this.copiedField = null;
    this.say('idle', this.botText('idle'));
  }

  // =========================================================
  // ERGEBNIS-DARSTELLUNG
  // =========================================================
  statusLabel(item: ParsedItemDto): string {
    const code = item.statusCode || 'UNKNOWN';
    const translated = this.i18n.t('status.' + code);
    return translated === 'status.' + code ? (item.status || code) : translated;
  }

  statusClass(item: ParsedItemDto): Record<string, boolean> {
    return {
      'status-ok': item.statusCode === 'OK',
      'status-err': item.statusCode === 'UNKNOWN',
      'status-warn': item.statusCode === 'BLOCKED' || item.statusCode === 'NOT_LISTED'
    };
  }

  /** Erklärt in der Preisspalte, woraus der Preis entstanden ist. */
  priceTooltip(item: ParsedItemDto): string {
    const modifier = this.i18n.t('result.modifier') + ': ' + (item.appliedModifier || 0) + '%';
    return item.priceSource === 'REPROCESSED'
      ? modifier + ' | ' + this.i18n.t('result.reprocessedBasis')
      : modifier;
  }

  get hasResult(): boolean {
    return this.items.length > 0;
  }

  get hasRejectedItems(): boolean {
    return this.items.some(i => i.statusCode === 'BLOCKED' || i.statusCode === 'NOT_LISTED' || i.statusCode === 'UNKNOWN');
  }

  // =========================================================
  // VERTRAGS-PANEL (Schritt 2 - der Prozess endet nicht beim Preis)
  // =========================================================
  get contractRecipient(): string {
    return this.config?.contractRecipient?.trim() || '';
  }

  get contractLocationName(): string {
    return this.locations.find(l => Number(l.id) === Number(this.locationId))?.name || '-';
  }

  /** Auf volle ISK gerundet - liegt weit innerhalb der Prüf-Toleranz und ist leichter einzutippen. */
  get contractPrice(): number {
    return Math.round(this.totalPrice || 0);
  }

  get contractExpireDays(): number {
    return this.config?.contractExpireDays ?? 3;
  }

  get contractDaysToComplete(): number {
    return this.config?.contractDaysToComplete ?? 0;
  }

  copyToClipboard() {
    if (this.totalPrice !== null) {
      this.writeClipboard(String(this.contractPrice), 'price');
    }
  }

  copyContractPrice() {
    this.writeClipboard(String(this.contractPrice), 'contractPrice');
  }

  copyRecipient() {
    if (this.contractRecipient) {
      this.writeClipboard(this.contractRecipient, 'recipient');
    }
  }

  private writeClipboard(value: string, field: 'price' | 'recipient' | 'contractPrice') {
    navigator.clipboard.writeText(value).then(() => {
      this.copiedField = field;
      setTimeout(() => {
        this.copiedField = null;
        this.cdr.detectChanges();
      }, 1500);
    });
  }

  copyLabel(field: 'price' | 'recipient' | 'contractPrice', fallbackKey: string): string {
    return this.copiedField === field ? this.i18n.t('btn.copied') : this.i18n.t(fallbackKey);
  }

  // =========================================================
  // ADMIN / LOGIN / TUTORIAL
  // =========================================================
  get isAdmin(): boolean {
    return this.authService.hasAnyRole(ADMIN_ROLES);
  }

  openAdmin() {
    if (this.isAdmin) {
      this.showAdmin = true;
    } else {
      this.say('warn', this.i18n.t('bot.accessDenied'));
    }
  }

  get isLoggedIn(): boolean {
    return this.authService.currentUser() !== null;
  }

  login() {
    this.authService.login();
  }

  openTutorial(tab: TutorialTab = 'flow') {
    this.tutorialTab = tab;
    this.showTutorial = true;
  }

  closeModals() {
    this.showTutorial = false;
    this.showAdmin = false;
  }

  /** Wird nach dem Speichern im Admin-Panel aufgerufen, damit Wartung & Texte sofort greifen. */
  reloadConfig() {
    this.buybotService.getPublicConfig().subscribe({
      next: (cfg) => (this.config = cfg),
      error: () => { /* alte Config behalten */ }
    });
  }

  // =========================================================
  // BOT-SPRÜCHE
  // =========================================================
  /**
   * Die im Admin-Panel gepflegten Sprüche sind deutsch - in der englischen
   * Fassung greifen deshalb die eingebauten Texte.
   */
  private botText(key: keyof BotTexts): string {
    const fallback = this.i18n.t('bot.' + key);
    if (!this.i18n.isGerman()) {
      return fallback;
    }
    const configured = this.config?.botTexts?.[key];
    if (!configured) {
      return fallback;
    }
    const lines = configured.split('\n').map(l => l.trim()).filter(l => l.length > 0);
    if (lines.length === 0) {
      return fallback;
    }
    return lines[Math.floor(Math.random() * lines.length)];
  }

  setFace(mood: string) {
    const faces: Record<string, string> = {
      idle: '&middot;_&middot;',
      thinking: ' o_O ',
      happy: ' ^&#8255;^ ',
      warn: ' o.O ',
      error: ' x_x '
    };
    this.botFace = faces[mood] || faces['idle'];
  }

  async typeSpeech(text: string) {
    const myToken = ++this.typingToken;
    this.botSpeech = '';

    // Die Animation läuft außerhalb von Angular
    this.ngZone.runOutsideAngular(async () => {
      for (let i = 0; i < text.length; i++) {
        if (myToken !== this.typingToken || this.destroyed) return;
        this.botSpeech += text[i];

        // Zwingt Angular, nur diese Komponente neu zu zeichnen (für jeden Buchstaben)
        this.cdr.detectChanges();

        await new Promise(r => setTimeout(r, 14));
      }
    });
  }

  say(mood: string, text: string) {
    this.setFace(mood);
    this.typeSpeech(text);
  }
}
