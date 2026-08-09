import { Component, EventEmitter, OnInit, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  BuybotAdminService,
  AdminConfig,
  AdminLocation,
  AdminCategory,
  AdminType
} from '../../services/buybot-admin.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';

@Component({
  selector: 'app-buybot-admin',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './buybot-admin.component.html',
  styleUrls: ['../buybot/buybot.component.scss'] // Erbt das CSS vom Terminal
})
export class BuybotAdminComponent implements OnInit {
  @Output() closePanel = new EventEmitter<void>();

  private adminService = inject(BuybotAdminService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  // State
  config: AdminConfig = {
    priceBasis: 'buy',
    globalModifier: 90,
    volumeThreshold: 350000,
    valueThreshold: 1000000000,
    itemValueThreshold: 500000000,
    botTexts: {
      idle: '', thinking: '', success: '', warnMissing: '', warnRejected: '', error: '', highVolume: '', highValue: '', expensiveItem: ''
    }
  };

  locations: AdminLocation[] = [];
  categories: AdminCategory[] = [];
  types: AdminType[] = [];

  // Formular-Modelle für neue Einträge
  newLoc: AdminLocation = { name: '', transportFee: 0, securityFee: 0 };
  newCat = { name: '', modifier: 90 };
  newType = { name: '', modifier: 90, isBlacklisted: false };

  ngOnInit() {
    this.loadAllData();
  }

  loadAllData() {
    this.adminService.getConfig().subscribe(c => {
      this.config = c;
      if (!this.config.botTexts) {
        this.config.botTexts = {
          idle: '', thinking: '', success: '', warnMissing: '', warnRejected: '', error: '', highVolume: '', highValue: '', expensiveItem: ''
        };
      }
    });
    this.adminService.getLocations().subscribe(l => this.locations = l);
    this.adminService.getCategories().subscribe(c => this.categories = c);
    this.adminService.getTypes().subscribe(t => this.types = t);
  }

  saveConfig() {
    this.adminService.updateConfig(this.config).subscribe({
      next: () => this.toastService.success('Konfiguration erfolgreich gespeichert!'),
      error: (err) => this.toastService.error('Fehler beim Speichern: ' + err.message)
    });
  }

  addLocation() {
    if (!this.newLoc.name) return;
    this.adminService.addLocation(this.newLoc as any).subscribe(() => {
      this.newLoc = { name: '', transportFee: 0, securityFee: 0 };
      this.adminService.getLocations().subscribe(l => this.locations = l);
      this.toastService.success('Abgabeort hinzugefügt.');
    });
  }

  isSearching = false;

  searchStationId() {
    if (!this.newLoc.name) {
      this.toastService.info('Bitte gib zuerst den Namen des Ortes ein.');
      return;
    }

    this.isSearching = true;
    this.adminService.searchStationId(this.newLoc.name).subscribe({
      next: (id) => {
        this.newLoc.stationId = id;
        this.isSearching = false;
        this.toastService.success(`Station ID ${id} erfolgreich gefunden!`);
      },
      error: () => {
        this.isSearching = false;
        this.toastService.error('Station nicht gefunden. Existiert sie wirklich und hast du die Rechte dazu?');
      }
    });
  }

  async deleteLocation(id: number) {
    const confirmed = await this.confirmService.ask(
      'Ort löschen?',
      'Soll dieser Abgabeort wirklich gelöscht werden?',
      'LÖSCHEN',
      'ABBRECHEN'
    );

    if (confirmed) {
      this.adminService.deleteLocation(id).subscribe(() => {
        this.locations = this.locations.filter(l => l.id !== id);
        this.toastService.info('Abgabeort entfernt.');
      });
    }
  }

  addCategory() {
    if (!this.newCat.name) return;
    this.adminService.addCategory(this.newCat.name, this.newCat.modifier).subscribe({
      next: () => {
        this.newCat.name = '';
        this.adminService.getCategories().subscribe(c => this.categories = c);
        this.toastService.success('Kategorie zur Whitelist hinzugefügt.');
      },
      error: () => this.toastService.error('Kategorie in der EVE SDE nicht gefunden!')
    });
  }

  async deleteCategory(id: number) {
    const confirmed = await this.confirmService.ask(
      'Kategorie entfernen?',
      'Soll diese Kategorie wirklich aus der Whitelist entfernt werden?',
      'ENTFERNEN',
      'ABBRECHEN'
    );

    if (confirmed) {
      this.adminService.deleteCategory(id).subscribe(() => {
        this.categories = this.categories.filter(c => c.categoryId !== id);
        this.toastService.info('Kategorie entfernt.');
      });
    }
  }

  addType() {
    if (!this.newType.name) return;
    this.adminService.addType(this.newType.name, this.newType.modifier, this.newType.isBlacklisted).subscribe({
      next: () => {
        this.newType.name = '';
        this.newType.isBlacklisted = false;
        this.adminService.getTypes().subscribe(t => this.types = t);
        this.toastService.success('Item-Regel erfolgreich gespeichert.');
      },
      error: () => this.toastService.error('Exaktes Item in der EVE SDE nicht gefunden!')
    });
  }

  async deleteType(id: number) {
    const confirmed = await this.confirmService.ask(
      'Item-Regel löschen?',
      'Soll diese spezifische Item-Regel wirklich gelöscht werden?',
      'LÖSCHEN',
      'ABBRECHEN'
    );

    if (confirmed) {
      this.adminService.deleteType(id).subscribe(() => {
        this.types = this.types.filter(t => t.typeId !== id);
        this.toastService.info('Item-Regel entfernt.');
      });
    }
  }

  close() {
    this.closePanel.emit();
  }
}
