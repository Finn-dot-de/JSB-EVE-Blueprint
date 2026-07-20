import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DoctrineService, FleetDoctrine } from '../../services/doctrine.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';

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

  showCreateModal = signal(false);
  selectedDoctrine = signal<FleetDoctrine | null>(null);
  editingDoctrineId = signal<number | null>(null); // Speichert die ID beim Bearbeiten

  newEftInput = signal('');
  newDoctrineName = signal('');
  isSubmitting = signal(false);

  searchQuery = signal('');

  groupedDoctrines = computed(() => {
    const query = this.searchQuery().toLowerCase();
    let filtered = this.doctrines();

    if (query) {
      filtered = filtered.filter(doc =>
        doc.name.toLowerCase().includes(query) ||
        doc.shipType.toLowerCase().includes(query) ||
        (doc.doctrineName && doc.doctrineName.toLowerCase().includes(query))
      );
    }

    const groups = new Map<string, FleetDoctrine[]>();
    filtered.forEach(doc => {
      const groupName = doc.doctrineName || 'Ungruppiert';
      if (!groups.has(groupName)) {
        groups.set(groupName, []);
      }
      groups.get(groupName)!.push(doc);
    });

    return Array.from(groups.entries()).map(([name, docs]) => ({
      name,
      docs: docs.sort((a, b) => a.shipType.localeCompare(b.shipType))
    })).sort((a, b) => a.name.localeCompare(b.name));
  });

  parsedGroups = computed(() => {
    const doc = this.selectedDoctrine();
    if (!doc) return [];

    const rawLines = doc.eftString.split('\n');
    if (rawLines.length <= 1) return [];

    const groups: { name: string; modules: string[] }[] = [
      { name: 'High Slots', modules: [] },
      { name: 'Mid Slots', modules: [] },
      { name: 'Low Slots', modules: [] },
      { name: 'Rigs', modules: [] },
      { name: 'Subsystems', modules: [] },
      { name: 'Drones', modules: [] },
      { name: 'Cargo / Ammo', modules: [] }
    ];

    let currentBlock = 0;
    let hasStarted = false;

    // Wir ignorieren Zeile 0 (Das ist immer [Schiff, Name])
    for (let i = 1; i < rawLines.length; i++) {
      const line = rawLines[i].trim();

      // Bei einer Leerzeile springen wir eine Kategorie weiter (genau so funktioniert das EFT Format)
      if (line === '') {
        if (hasStarted) {
          currentBlock++;
        }
        continue;
      }

      hasStarted = true;

      // Pyfa fügt manchmal "[Empty High slot]" ein, das wollen wir im UI nicht anzeigen
      if (line.startsWith('[Empty') && line.endsWith(']')) {
        continue;
      }

      // Sicherheits-Clamp: Alles nach Block 6 (Drones) landet automatisch im Cargo
      const blockIndex = Math.min(currentBlock, 6);
      groups[blockIndex].modules.push(line);
    }

    // Wir geben dem UI nur die Gruppen zurück, in denen auch wirklich ein Modul steckt
    return groups.filter(g => g.modules.length > 0);
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
    this.editingDoctrineId.set(null); // Reset für neues Fitting
    this.newEftInput.set('');
    this.newDoctrineName.set('');
    this.showCreateModal.set(true);
  }

  // Öffnet das Modal mit den vorausgefüllten Daten zum Bearbeiten
  openEditModal(doc: FleetDoctrine) {
    this.editingDoctrineId.set(doc.id);
    this.newDoctrineName.set(doc.doctrineName === 'Ungruppiert' ? '' : doc.doctrineName);
    this.newEftInput.set(doc.eftString);
    this.showCreateModal.set(true);
  }

  openDetails(doc: FleetDoctrine) {
    this.selectedDoctrine.set(doc);
  }

  closeModals() {
    this.showCreateModal.set(false);
    this.selectedDoctrine.set(null);
    this.editingDoctrineId.set(null);
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

      let finalDoctrineName = this.newDoctrineName().trim();

      if (!finalDoctrineName) {
        const nameParts = fitName.split(' ');

        if (nameParts.length >= 2) {
          finalDoctrineName = `${nameParts[0]} ${nameParts[1]}`;
        } else {
          finalDoctrineName = fitName;
        }
      }

      const payload = {
        doctrineName: finalDoctrineName,
        shipType: shipType,
        name: fitName,
        eftString: rawText
      };

      // Entscheide: Update (PUT) oder Create (POST)
      const request$ = this.editingDoctrineId()
        ? this.doctrineService.updateDoctrine(this.editingDoctrineId()!, payload)
        : this.doctrineService.createDoctrine(payload);

      request$.subscribe({
        next: () => {
          this.isSubmitting.set(false);
          this.closeModals();
          this.loadDoctrines();
          this.toastService.success(`Fitting erfolgreich unter "${finalDoctrineName}" gespeichert!`);
        },
        error: (err) => {
          this.isSubmitting.set(false);
          this.toastService.error('Fehler beim Speichern: ' + (err.error?.message || 'Unbekannt'));
        }
      });
    } else {
      this.toastService.error('Ungültiges EFT Format! Die erste Zeile muss [Schiffstyp, Fitting Name] lauten.');
    }
  }

  copyToClipboard(eftString: string | undefined) {
    if (!eftString) return;
    navigator.clipboard.writeText(eftString).then(() => {
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
