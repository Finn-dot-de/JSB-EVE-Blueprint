import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';

import { AuthService } from '../../services/auth.service';
import { DoctrinesComponent } from '../doctrines/doctrines.component';
import { FittingSandboxComponent } from '../fitting-sandbox/fitting-sandbox.component';
import { ReadinessBoardComponent } from '../readiness-board/readiness-board.component';

/** Die drei Reiter dieser Seite. */
export type FittingsTabId = 'DOCTRINES' | 'BOARD' | 'SANDBOX';

/**
 * "Fittings und Doktrinen" - gespeicherte Doktrinen, Readiness Board und
 * Sandbox unter einem Dach.
 *
 * <p>Die drei standen als Reiter im Fleet Activity Tracking und hatten dort
 * nichts verloren: Sie beantworten "was fliegen wir und wer kann es", nicht
 * "wer war dabei". Der Fleet Manager behält Flotten und FAT-Statistik.</p>
 *
 * <p>Der Kreis ist derselbe wie vorher (<code>canSeeReadiness</code>) und steht
 * doppelt: hier als Anzeigegrenze und am <code>roleGuard</code> der Route. Die
 * Sperre selbst steht im Server - alle drei Reiter zeigen Hangar- und
 * Skilldaten der ganzen Corporation.</p>
 */
@Component({
  selector: 'app-fittings',
  standalone: true,
  imports: [CommonModule, DoctrinesComponent, ReadinessBoardComponent, FittingSandboxComponent],
  templateUrl: './fittings.component.html',
  styleUrls: ['./fittings.component.scss']
})
export class FittingsComponent {
  public authService = inject(AuthService);

  activeTab = signal<FittingsTabId>('DOCTRINES');

  /**
   * Welche Reiter schon einmal offen waren.
   *
   * <p>Ein einmal geöffneter Reiter bleibt im DOM und wird nur ausgeblendet.
   * Als Reiter im Fleet Manager lag der Zustand aller drei in einer einzigen
   * Komponente und überlebte jeden Wechsel; als eigene Komponenten würden sie
   * bei jedem Wechsel neu gebaut - die Sandbox verlöre ihre Eingabe und das
   * Board holte seine Daten erneut.</p>
   */
  private geoeffnet = signal<ReadonlySet<FittingsTabId>>(new Set<FittingsTabId>(['DOCTRINES']));

  get canSeeReadiness(): boolean {
    return this.authService.hasAnyRole([
      'ROLE_IT_ADMIN', 'ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_MANAGER', 'ROLE_69', 'ROLE_1337', 'ROLE_A38'
    ]);
  }

  setTab(tab: FittingsTabId) {
    this.activeTab.set(tab);
    if (this.geoeffnet().has(tab)) return;
    // Eine neue Menge statt einer veränderten: Ein Signal, dessen Wert dieselbe
    // Referenz behält, meldet keine Änderung.
    this.geoeffnet.update(offen => new Set([...offen, tab]));
  }

  wurdeGeoeffnet(tab: FittingsTabId): boolean {
    return this.geoeffnet().has(tab);
  }
}
