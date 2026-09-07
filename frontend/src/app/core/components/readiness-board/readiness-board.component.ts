import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { DoctrineReadinessDto, ReadinessService } from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';
import { fitKey } from '../../shared/fit-key.util';
import { ReadinessFitPanelComponent } from '../readiness-fit-panel/readiness-fit-panel.component';

/**
 * Das Readiness Board: eine gespeicherte Doktrin gegen Hangar und Skills der
 * ganzen Corporation.
 *
 * <p>Es hat mit Flottenteilnahme nichts zu tun und stand trotzdem als Reiter im
 * Fleet Manager. Hier ist es eine eigene Komponente unter "Fittings und
 * Doktrinen" - mit demselben Zustand, denselben Ladewegen und derselben
 * Anzeigegrenze wie vorher.</p>
 *
 * <p>Der Aufklapp-Zustand liegt hier und nicht in der Fit-Kachel: Beim Wechsel
 * der Doktrin muss er in einem Zug fallen, und das kann nur die Stelle, die
 * auch das Board neu lädt.</p>
 */
@Component({
  selector: 'app-readiness-board',
  standalone: true,
  imports: [CommonModule, FormsModule, ReadinessFitPanelComponent],
  templateUrl: './readiness-board.component.html',
  styleUrls: ['./readiness-board.component.scss']
})
export class ReadinessBoardComponent implements OnInit {
  private readinessService = inject(ReadinessService);
  private toastService = inject(ToastService);

  protected readonly fitKey = fitKey;

  doctrineNames = signal<string[]>([]);
  selectedDoctrine: string | null = null;
  board = signal<DoctrineReadinessDto | null>(null);
  loadingBoard = signal(false);

  memberFilter = signal('');

  expandedFits = signal<Set<number>>(new Set());
  expandedAccounts = signal<Set<string>>(new Set()); // Key: "fitKey:mainId"

  /**
   * Was schon dasteht, wird nicht noch einmal geholt.
   *
   * <p>Dieselbe Bedingung, die vorher im <code>setTab</code> des Fleet Managers
   * stand. Sie bleibt bestehen, weil die Seite darüber den einmal geöffneten
   * Reiter am Leben hält - ohne die Prüfung liefe bei jedem Wiedereintritt ein
   * zweiter Abruf.</p>
   */
  ngOnInit() {
    if (this.doctrineNames().length === 0) {
      this.loadDoctrineNames(true);
      return;
    }
    if (!this.board()) this.loadBoard();
  }

  loadDoctrineNames(thenLoadBoard = false) {
    this.readinessService.doctrines().subscribe({
      next: (names) => {
        this.doctrineNames.set(names);
        if (names.length > 0 && !this.selectedDoctrine) {
          this.selectedDoctrine = names[0];
        }
        if (thenLoadBoard) this.loadBoard();
      },
      error: () => this.toastService.error('Doktrinen konnten nicht geladen werden.')
    });
  }

  onDoctrineChange() {
    this.board.set(null);
    this.expandedFits.set(new Set());
    this.expandedAccounts.set(new Set());

    this.loadBoard();
  }

  loadBoard() {
    if (!this.selectedDoctrine) return;
    this.loadingBoard.set(true);
    this.readinessService.checkBoard(this.selectedDoctrine).subscribe({
      next: (data) => {
        this.board.set(data);
        this.loadingBoard.set(false);
        if (data.fits.length > 0) this.expandedFits.set(new Set([fitKey(data.fits[0])]));
      },
      error: (err) => {
        this.loadingBoard.set(false);
        this.toastService.error(err.error?.message || 'Readiness-Check fehlgeschlagen.');
      }
    });
  }

  // ================= Aufklapp-Logik =================

  toggleFit(key: number) {
    this.expandedFits.update(current => {
      const next = new Set(current);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }

  isFitExpanded(key: number): boolean {
    return this.expandedFits().has(key);
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
