import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';

import {
  AccountReadinessDto,
  CharacterReadinessDto,
  FitReadinessDto
} from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';
import { handlePortraitError, handleTypeImageError } from '../../shared/eve-image.util';
import { copyText } from '../../shared/clipboard.util';
import { toPlanLines, toSkillPlanText } from '../../shared/skill-plan.util';

/**
 * Die Kachel zu genau einem Fitting: Abdeckung, Pflicht-Skills und die zwei
 * Spalten "Einsatzbereit" / "Nicht bereit".
 *
 * <p>Sie ist eine eigene Komponente, weil zwei Seiten sie brauchen: das
 * Readiness Board zeigt eine je Fit der Doktrin, die Sandbox genau eine für
 * das eingefügte Fitting. Vorher war sie ein <code>ng-template</code> im Fleet
 * Manager - beim Umzug hätte das entweder zwei Abschriften ergeben oder eine
 * Vorlage, die zwischen zwei Seiten hin und her gereicht wird.</p>
 *
 * <p>Die Kachel hält bewusst KEINEN eigenen Aufklapp-Zustand. Wer aufgeklappt
 * ist, weiß die Seite darüber - sie räumt ihn beim Doktrinwechsel und bei
 * jedem neuen Sandbox-Lauf ab. Läge er hier, überlebte er das Neuladen,
 * solange Angular die Kachel über ihren Schlüssel wiederverwendet.</p>
 */
@Component({
  selector: 'app-readiness-fit-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './readiness-fit-panel.component.html',
  styleUrls: ['./readiness-fit-panel.component.scss']
})
export class ReadinessFitPanelComponent {
  private toastService = inject(ToastService);

  protected readonly onImgError = handleTypeImageError;
  protected readonly onPortraitError = handlePortraitError;

  @Input({ required: true }) fit!: FitReadinessDto;

  /** Die Sandbox zeigt genau ein Fitting - das steht immer offen und ohne Knopf. */
  @Input() forceOpen = false;

  @Input() expanded = false;

  /** Der Suchbegriff aus der Werkzeugleiste der jeweiligen Seite. */
  @Input() memberFilter = '';

  /** Die offenen Accounts der ganzen Seite, Schlüssel "fitKey:mainId". */
  @Input() openAccounts: ReadonlySet<string> = new Set<string>();

  /** Der Schlüssel dieser Kachel - die Seite darüber setzt ihn. */
  @Input({ required: true }) fitKey!: number;

  @Output() fitToggled = new EventEmitter<void>();
  @Output() accountToggled = new EventEmitter<number>();

  isAccountOpen(mainId: number): boolean {
    return this.openAccounts.has(`${this.fitKey}:${mainId}`);
  }

  // ================= Filter =================

  filterAccounts(accounts: AccountReadinessDto[]): AccountReadinessDto[] {
    const q = this.memberFilter.trim().toLowerCase();
    if (!q) return accounts;
    return accounts.filter(a =>
      a.mainName.toLowerCase().includes(q) ||
      a.characters.some(c => c.characterName.toLowerCase().includes(q))
    );
  }

  // ================= Utilities =================

  percent(value: number): string {
    return (value * 100).toFixed(0) + ' %';
  }

  coverageWidth(value: number): string {
    return Math.max(0, Math.min(100, value * 100)).toFixed(0) + '%';
  }

  coverageClass(value: number): string {
    if (value >= 0.75) return 'green';
    if (value >= 0.4) return 'orange';
    return 'red';
  }

  /**
   * Legt die fehlenden Skills eines Piloten als Plantext in die Zwischenablage.
   *
   * Beide Quellen zusammen - Voraussetzungen und Skillplan. So kann ein FC
   * einem Piloten genau die Liste geben, die er ingame einfügen muss.
   */
  copyMissingSkills(character: CharacterReadinessDto): Promise<void> {
    const text = toSkillPlanText(
      toPlanLines([...character.missingSkills, ...character.missingPlanSkills]));
    if (!text) {
      this.toastService.info(`${character.characterName} fehlt nichts.`);
      return Promise.resolve();
    }

    return copyText(text).then((ok) =>
      ok
        ? this.toastService.success(`Fehlende Skills von ${character.characterName} kopiert.`)
        : this.toastService.error('Fehler beim Kopieren in die Zwischenablage.'));
  }

  /** Ob es bei diesem Piloten überhaupt etwas zu kopieren gibt. */
  hasMissingSkills(character: CharacterReadinessDto): boolean {
    return character.missingSkills.length + character.missingPlanSkills.length > 0;
  }
}
