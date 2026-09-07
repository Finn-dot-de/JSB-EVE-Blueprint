import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { ReadinessService, SandboxResultDto } from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';
import { handleTypeImageError } from '../../shared/eve-image.util';
import { copyText } from '../../shared/clipboard.util';
import { fitKey } from '../../shared/fit-key.util';
import { ReadinessFitPanelComponent } from '../readiness-fit-panel/readiness-fit-panel.component';

/**
 * Die EFT-Sandbox: ein eingefügtes Fitting sofort gegen Hangar und Skills
 * halten, ohne es zu speichern.
 *
 * <p>Sie fragt dasselbe wie das Readiness Board, nur für ein Fitting, das es
 * noch gar nicht gibt. Deshalb teilt sie sich mit dem Board die Fit-Kachel und
 * sonst nichts - kein Doktrinname, keine Auswahl, kein gespeicherter Zustand.</p>
 */
@Component({
  selector: 'app-fitting-sandbox',
  standalone: true,
  imports: [CommonModule, FormsModule, ReadinessFitPanelComponent],
  templateUrl: './fitting-sandbox.component.html',
  styleUrls: ['./fitting-sandbox.component.scss']
})
export class FittingSandboxComponent {
  private readinessService = inject(ReadinessService);
  private toastService = inject(ToastService);

  protected readonly onImgError = handleTypeImageError;
  protected readonly fitKey = fitKey;

  sandboxInput = signal('');
  sandboxResult = signal<SandboxResultDto | null>(null);
  sandboxError = signal<string | null>(null);
  loadingSandbox = signal(false);

  memberFilter = signal('');

  expandedAccounts = signal<Set<string>>(new Set()); // Key: "fitKey:mainId"

  runSandbox() {
    const eft = this.sandboxInput().trim();
    if (!eft) return;

    this.loadingSandbox.set(true);
    this.sandboxError.set(null);

    this.readinessService.sandbox(eft).subscribe({
      next: (data) => {
        this.sandboxResult.set(data);
        this.loadingSandbox.set(false);
        this.expandedAccounts.set(new Set());
      },
      error: (err) => {
        this.loadingSandbox.set(false);
        this.sandboxResult.set(null);
        this.sandboxError.set(err.error?.message || 'Das Fitting konnte nicht ausgewertet werden.');
      }
    });
  }

  clearSandbox() {
    this.sandboxInput.set('');
    this.sandboxResult.set(null);
    this.sandboxError.set(null);
  }

  copyFitToClipboard(eft: string): Promise<void> {
    return copyText(eft).then((ok) =>
      ok
        ? this.toastService.info(
            'Fitting kopiert! Ingame das Fitting-Fenster öffnen und "Import from Clipboard" wählen.')
        : this.toastService.error('Fehler beim Kopieren in die Zwischenablage.'));
  }

  toggleAccount(fitKeyOfPanel: number, mainId: number) {
    const key = `${fitKeyOfPanel}:${mainId}`;
    this.expandedAccounts.update(current => {
      const next = new Set(current);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }

  isAccountExpanded(fitKeyOfPanel: number, mainId: number): boolean {
    return this.expandedAccounts().has(`${fitKeyOfPanel}:${mainId}`);
  }
}
