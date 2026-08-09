import { Component, inject, OnInit, OnDestroy, ChangeDetectorRef, NgZone } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BuybotService, ParsedItemDto, BuybackLocation } from '../../services/buybot.service';
import { DecimalPipe, NgClass } from '@angular/common';
import {AuthService} from '../../services/auth.service';
import {BuybotAdminComponent} from '../buybot-admin/buybot-admin.component';

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

  rawInput: string = '';

  // Locations dynamisch laden
  locations: BuybackLocation[] = [];
  locationId: number | null = null;

  items: ParsedItemDto[] = [];
  totalPrice: number | null = null;
  totalVolume: number = 0;
  isCalculating: boolean = false;
  copyButtonText: string = '[ ⎘ KOPIEREN ]';

  // State für Uhr und Modals
  currentTime: string = '';
  showTutorial: boolean = false;
  showAdmin: boolean = false;
  private clockInterval: any;

  // Bot Animation State
  botFace: string = '&middot;_&middot;';
  botSpeech: string = '';
  private typingToken: number = 0;

  ngOnInit() {
    this.say('idle', 'Bereit, dein Vermögen in ISK umzuwandeln.');

    this.updateClock();
    // Außerhalb der Zone ausführen, um globale Change Detection zu verhindern
    this.ngZone.runOutsideAngular(() => {
      this.clockInterval = setInterval(() => {
        this.updateClock();
        this.cdr.detectChanges(); // UI-Update nur für diese Komponente
      }, 1000);
    });

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
        this.say('error', 'Konnte Abgabeorte nicht vom Server laden.');
        this.locations = [{ id: 1, name: 'Fehler beim Laden (Jita)', transportFee: 0, securityFee: 0, stationId: 60003760 }];
        this.locationId = 1;
      }
    });
  }

  ngOnDestroy() {
    // Intervall sauber beenden, wenn die Komponente zerstört wird
    if (this.clockInterval) {
      clearInterval(this.clockInterval);
    }
  }

  private updateClock() {
    this.currentTime = new Date().toLocaleString('de-DE');
  }

  calculate() {
    if (!this.rawInput || this.rawInput.trim() === '') {
      this.say('warn', 'Keine Zeilen erkannt. Bitte Liste einfügen.');
      return;
    }

    // Check, ob ein Abgabeort gewählt wurde
    if (this.locationId === null) {
      this.say('warn', 'Bitte wähle zuerst einen Abgabeort.');
      return;
    }

    this.isCalculating = true;
    this.say('thinking', 'Ich zähle dein Klimpergeld...');

    this.buybotService.calculateBuyback({ rawInput: this.rawInput, locationId: this.locationId })
      .subscribe({
        next: (res: ParsedItemDto[]) => {
          this.items = res;
          this.totalPrice = res.reduce((acc, item) => acc + (item.totalPrice || 0), 0);
          this.totalVolume = res.reduce((acc, item) => acc + ((item.volumeEach || 0) * item.quantity), 0);
          this.isCalculating = false;

          const hasErrors = res.some((i) => i.status.includes('GESPERRT') || i.status.includes('NICHT GEFUNDEN'));
          if (hasErrors) {
            this.say('warn', 'Manche Sachen nehm ich einfach nicht. Gesperrt oder unbekannt.');
          } else {
            this.say('happy', 'Deal! Ich streiche meine Marge ein, du deine ISK.');
          }
        },
        error: () => {
          this.isCalculating = false;
          this.say('error', 'Verbindung zum Backend verloren.');
        }
      });
  }

  clear() {
    this.rawInput = '';
    this.items = [];
    this.totalPrice = null;
    this.totalVolume = 0;
    this.say('idle', 'Bereit, dein Vermögen in ISK umzuwandeln.');
  }

  copyToClipboard() {
    if (this.totalPrice !== null) {
      navigator.clipboard.writeText(this.totalPrice.toFixed(2)).then(() => {
        this.copyButtonText = '[ KOPIERT ]';
        setTimeout(() => this.copyButtonText = '[ ⎘ KOPIEREN ]', 1500);
      });
    }
  }

  get isAdmin(): boolean {
    return this.authService.hasAnyRole(['ROLE_IT_ADMIN']);
  }
  openAdmin() {
    if (this.isAdmin) {
      this.showAdmin = true;
    } else {
      this.say('warn', 'Zugriff verweigert. Du hast nicht die nötigen Rechte.');
    }
  }

  get isLoggedIn(): boolean {
    return this.authService.currentUser() !== null;
  }

  login() {
    this.authService.login();
  }


  openTutorial() {
    this.showTutorial = true;
  }

  closeModals() {
    this.showTutorial = false;
    this.showAdmin = false;
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
        if (myToken !== this.typingToken) return;
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
