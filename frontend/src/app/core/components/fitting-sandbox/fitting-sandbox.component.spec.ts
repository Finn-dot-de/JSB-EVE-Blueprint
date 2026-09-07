import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FittingSandboxComponent } from './fitting-sandbox.component';
import { ReadinessService } from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';

describe('FittingSandboxComponent', () => {
  let component: FittingSandboxComponent;
  let readinessService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let clipboard: { writeText: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    readinessService = {
      sandbox: vi.fn().mockReturnValue(of({ fit: {}, board: {} })),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };

    clipboard = { writeText: vi.fn().mockResolvedValue(undefined) };
    vi.stubGlobal('navigator', { clipboard });

    TestBed.configureTestingModule({
      providers: [
        { provide: ReadinessService, useValue: readinessService },
        { provide: ToastService, useValue: toastService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new FittingSandboxComponent());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe('Auswerten', () => {
    it('wertet ein eingefügtes Fitting aus', () => {
      component.sandboxInput.set('[Nestor, Fit]');

      component.runSandbox();

      expect(readinessService['sandbox']).toHaveBeenCalledWith('[Nestor, Fit]');
      expect(component.sandboxResult()).not.toBeNull();
      expect(component.loadingSandbox()).toBe(false);
    });

    it('wertet leere Eingaben gar nicht erst aus', () => {
      component.sandboxInput.set('   ');

      component.runSandbox();

      expect(readinessService['sandbox']).not.toHaveBeenCalled();
    });

    it('zeigt die Meldung des Servers bei einem unbrauchbaren Fitting', () => {
      readinessService['sandbox'].mockReturnValue(
        throwError(() => ({ error: { message: 'Unbekannter Schiffstyp.' } })),
      );
      component.sandboxInput.set('[Erfunden, Fit]');

      component.runSandbox();

      expect(component.sandboxError()).toBe('Unbekannter Schiffstyp.');
      expect(component.sandboxResult()).toBeNull();
    });

    it('räumt die Sandbox vollständig ab', () => {
      component.sandboxInput.set('[Nestor, Fit]');
      component.runSandbox();

      component.clearSandbox();

      expect(component.sandboxInput()).toBe('');
      expect(component.sandboxResult()).toBeNull();
      expect(component.sandboxError()).toBeNull();
    });

    it('klappt bei jedem neuen Lauf die aufgeklappten Accounts wieder zu', () => {
      // Sonst stünde eine offene Zeile unter einem Fitting, das inzwischen ein
      // anderes ist.
      component.sandboxInput.set('[Nestor, Fit]');
      component.runSandbox();
      component.toggleAccount(-33472, 1000);

      component.runSandbox();

      expect(component.isAccountExpanded(-33472, 1000)).toBe(false);
    });
  });

  describe('Zwischenablage', () => {
    it('kopiert ein Fitting in die Zwischenablage', async () => {
      await component.copyFitToClipboard('[Nestor, Fit]');

      expect(clipboard.writeText).toHaveBeenCalledWith('[Nestor, Fit]');
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('meldet, wenn die Zwischenablage nicht mitspielt', async () => {
      clipboard.writeText.mockRejectedValue(new Error('verweigert'));

      await component.copyFitToClipboard('[Nestor, Fit]');

      expect(toastService['error']).toHaveBeenCalled();
    });
  });

  describe('Aufklappen', () => {
    it('klappt einen Account auf und wieder zu', () => {
      component.toggleAccount(-33472, 1000);
      expect(component.isAccountExpanded(-33472, 1000)).toBe(true);

      component.toggleAccount(-33472, 1000);
      expect(component.isAccountExpanded(-33472, 1000)).toBe(false);
    });
  });
});
