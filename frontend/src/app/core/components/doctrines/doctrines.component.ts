import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DoctrineService, FleetDoctrine } from '../../services/doctrine.service';
import { AuthService } from '../../services/auth.service';
import {ToastService} from '../../services/toast.service';
import {ConfirmService} from '../../services/confirm.service';

@Component({
  selector: 'app-doctrines',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './doctrines.component.html',
  styleUrls: ['./doctrines.component.scss']
})
export class DoctrinesComponent implements OnInit {
  public authService = inject(AuthService);
  private doctrineService = inject(DoctrineService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  doctrines = signal<FleetDoctrine[]>([]);

  // Modal States
  showCreateModal = signal(false);
  selectedDoctrine = signal<FleetDoctrine | null>(null);

  // Formular
  newEftInput = signal('');
  isSubmitting = signal(false);

  // Berechnet aus dem EFT-Text automatisch eine saubere Liste der Module für die Detailansicht
  parsedModules = computed(() => {
    const doc = this.selectedDoctrine();
    if (!doc) return [];

    // Splitten den Text am Zeilenumbruch, ignorieren die erste Zeile [Schiff, Name] und leere Zeilen
    const lines = doc.eftString.split('\n');
    return lines.slice(1)
      .map(line => line.trim())
      .filter(line => line.length > 0);
  });

  get isFleetCommander(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_FC', 'ROLE_A38']);
  }

  ngOnInit() {
    this.loadDoctrines();
  }

  loadDoctrines() {
    this.doctrineService.getDoctrines().subscribe(docs => this.doctrines.set(docs));
  }

  openCreateModal() {
    this.newEftInput.set('');
    this.showCreateModal.set(true);
  }

  openDetails(doc: FleetDoctrine) {
    this.selectedDoctrine.set(doc);
  }

  closeModals() {
    this.showCreateModal.set(false);
    this.selectedDoctrine.set(null);
  }

  parseAndSaveFitting() {
    const rawText = this.newEftInput().trim();
    if (!rawText) return;

    const lines = rawText.split('\n');
    const firstLine = lines[0].trim();
    const match = firstLine.match(/^\[(.*?),\s*(.*?)\]/);

    if (match) {
      this.isSubmitting.set(true);
      const shipType = match[1].trim();
      const fitName = match[2].trim();

      this.doctrineService.createDoctrine({
        shipType: shipType,
        name: fitName,
        eftString: rawText
      }).subscribe({
        next: () => {
          this.isSubmitting.set(false);
          this.closeModals();
          this.loadDoctrines();
          this.toastService.success(`Fitting "${fitName}" erfolgreich gespeichert!`);
        },
        error: (err) => {
          this.isSubmitting.set(false);
          // NEU: Roter Error-Toast
          this.toastService.error('Fehler beim Speichern: ' + (err.error?.message || 'Unbekannt'));
        }
      });
    } else {
      // NEU: Fehler-Toast
      this.toastService.error('Ungültiges EFT Format! Die erste Zeile muss [Schiffstyp, Fitting Name] lauten.');
    }
  }

  copyToClipboard(eftString: string | undefined) {
    if (!eftString) return;
    navigator.clipboard.writeText(eftString).then(() => {
      // NEU: Eleganter Info-Toast
      this.toastService.info('Fitting kopiert! Öffne Ingame dein Fitting-Fenster und wähle "Import from Clipboard".');
      this.closeModals();
    }).catch(err => {
      console.error('Konnte Link nicht kopieren: ', err);
      this.toastService.error('Fehler beim Kopieren in die Zwischenablage.');
    });
  }

  async deleteDoctrine(id: number) {
    const confirmed = await this.confirmService.ask(
      'Fitting löschen?',
      'Möchtest du dieses Fitting wirklich unwiderruflich löschen?',
      'Löschen',
      'Abbrechen'
    );

    if (confirmed) {
      this.doctrineService.deleteDoctrine(id).subscribe(() => {
        this.loadDoctrines();
        this.toastService.success('Fitting wurde gelöscht.');
      });
    }
  }
}
