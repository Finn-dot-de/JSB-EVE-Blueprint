import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReadinessBoardComponent } from './readiness-board.component';
import { DoctrineReadinessDto, ReadinessService } from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';

/**
 * Ein Board mit einem Fit je übergebener ID.
 *
 * Die fitId ist bewusst mitgeführt: sie ist der Aufklapp-Schlüssel, nicht die
 * typeId - eine Doktrin darf zwei Fits derselben Hülle enthalten.
 */
function board(fits: Array<{ fitId: number; typeId: number }>): DoctrineReadinessDto {
  return {
    doctrineName: 'Armor',
    accountsTotal: 1,
    fitsChecked: fits.length,
    fits: fits.map(({ fitId, typeId }) => ({
      fitId,
      fitName: `Fit ${fitId}`,
      typeId,
      typeName: `Huelle ${typeId}`,
      iconUrl: '',
      renderUrl: '',
      moduleCount: 3,
      requiredSkills: [],
      hullSkillsRequired: 0,
      unresolved: [],
      planNames: [],
      planSkills: [],
      hullsTotal: 1,
      accountsReady: 1,
      accountsTotal: 1,
      coverage: 1,
      ready: [],
      notReady: [],
    })),
  } as DoctrineReadinessDto;
}

describe('ReadinessBoardComponent', () => {
  let component: ReadinessBoardComponent;
  let readinessService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    readinessService = {
      doctrines: vi.fn().mockReturnValue(of(['Armor', 'Shield'])),
      checkBoard: vi.fn().mockReturnValue(of(board([{ fitId: 7, typeId: 33472 }]))),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: ReadinessService, useValue: readinessService },
        { provide: ToastService, useValue: toastService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new ReadinessBoardComponent());
  });

  describe('Laden', () => {
    it('lädt beim ersten Öffnen die Doktrinen nach und gleich das Board dazu', () => {
      component.ngOnInit();

      expect(readinessService['doctrines']).toHaveBeenCalled();
      expect(component.selectedDoctrine).toBe('Armor');
      expect(component.board()).not.toBeNull();
    });

    it('lädt die Doktrinen nicht erneut, wenn sie schon da sind', () => {
      // Die Seite darüber hält den einmal geöffneten Reiter am Leben. Ohne
      // diese Prüfung liefe bei jedem Wiedereintritt ein zweiter Abruf.
      component.ngOnInit();
      readinessService['doctrines'].mockClear();
      readinessService['checkBoard'].mockClear();

      component.ngOnInit();

      expect(readinessService['doctrines']).not.toHaveBeenCalled();
      expect(readinessService['checkBoard']).not.toHaveBeenCalled();
    });

    it('klappt beim Laden das erste Fitting auf', () => {
      component.selectedDoctrine = 'Armor';

      component.loadBoard();

      // Der Schlüssel ist die fitId, nicht die typeId.
      expect(component.isFitExpanded(7)).toBe(true);
      expect(component.loadingBoard()).toBe(false);
    });

    it('lädt ohne gewählte Doktrin kein Board', () => {
      component.selectedDoctrine = null;

      component.loadBoard();

      expect(readinessService['checkBoard']).not.toHaveBeenCalled();
    });

    it('räumt beim Wechsel der Doktrin das alte Board ab', () => {
      component.ngOnInit();
      expect(component.board()).not.toBeNull();

      component.selectedDoctrine = 'Shield';
      component.onDoctrineChange();

      expect(readinessService['checkBoard']).toHaveBeenCalledWith('Shield');
    });

    it('räumt beim Wechsel der Doktrin auch die aufgeklappten Zeilen ab', () => {
      // Sonst stünde eine offene Accountliste unter einem Fitting, zu dem sie
      // gar nicht gehört.
      component.ngOnInit();
      component.toggleAccount(7, 1000);

      component.selectedDoctrine = 'Shield';
      component.onDoctrineChange();

      expect(component.isAccountExpanded(7, 1000)).toBe(false);
    });

    it('meldet einen Fehlschlag des Boards', () => {
      readinessService['checkBoard'].mockReturnValue(
        throwError(() => ({ error: { message: 'Auswertung fehlgeschlagen.' } })),
      );
      component.selectedDoctrine = 'Armor';

      component.loadBoard();

      expect(toastService['error']).toHaveBeenCalledWith('Auswertung fehlgeschlagen.');
      expect(component.loadingBoard()).toBe(false);
    });

    it('meldet, wenn die Doktrinen nicht ladbar sind', () => {
      readinessService['doctrines'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadDoctrineNames();

      expect(toastService['error']).toHaveBeenCalled();
    });
  });

  describe('Aufklappen', () => {
    it('klappt ein Fitting auf und wieder zu', () => {
      component.toggleFit(1);
      expect(component.isFitExpanded(1)).toBe(true);

      component.toggleFit(1);
      expect(component.isFitExpanded(1)).toBe(false);
    });

    it('klappt einen Account je Fitting getrennt auf', () => {
      // Derselbe Account kann unter zwei Fittings unterschiedlich aufgeklappt sein.
      component.toggleAccount(1, 1000);

      expect(component.isAccountExpanded(1, 1000)).toBe(true);
      expect(component.isAccountExpanded(2, 1000)).toBe(false);
    });
  });
});
