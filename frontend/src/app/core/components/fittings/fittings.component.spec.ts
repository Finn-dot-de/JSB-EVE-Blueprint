import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FittingsComponent } from './fittings.component';
import { AuthService } from '../../services/auth.service';

describe('FittingsComponent', () => {
  let component: FittingsComponent;
  let authService: { hasAnyRole: ReturnType<typeof vi.fn> };

  function baue() {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });
    return TestBed.runInInjectionContext(() => new FittingsComponent());
  }

  beforeEach(() => {
    authService = { hasAnyRole: vi.fn().mockReturnValue(true) };
    component = baue();
  });

  describe('Reiter', () => {
    it('öffnet auf den gespeicherten Doktrinen', () => {
      expect(component.activeTab()).toBe('DOCTRINES');
    });

    it('baut nur den Reiter auf, den jemand auch angesehen hat', () => {
      // Board und Sandbox holen beim Aufbau Daten. Wer die Seite nur wegen der
      // Doktrinen öffnet, soll dafür nicht zwei Auswertungen der ganzen Corp
      // auslösen.
      expect(component.wurdeGeoeffnet('DOCTRINES')).toBe(true);
      expect(component.wurdeGeoeffnet('BOARD')).toBe(false);
      expect(component.wurdeGeoeffnet('SANDBOX')).toBe(false);
    });

    it('hält einen einmal geöffneten Reiter am Leben', () => {
      // Als Reiter im Fleet Manager lag der Zustand aller drei in einer
      // Komponente und überlebte jeden Wechsel. Würden sie hier beim Wechsel
      // abgebaut, verlöre die Sandbox ihre Eingabe und das Board holte seine
      // Daten bei jedem Wiedereintritt erneut.
      component.setTab('SANDBOX');
      component.setTab('BOARD');

      expect(component.activeTab()).toBe('BOARD');
      expect(component.wurdeGeoeffnet('SANDBOX')).toBe(true);
      expect(component.wurdeGeoeffnet('DOCTRINES')).toBe(true);
    });
  });

  describe('Rechte', () => {
    it('zeigt die Seite genau dem Kreis, der die drei Reiter vorher sah', () => {
      expect(component.canSeeReadiness).toBe(true);
      expect(authService.hasAnyRole).toHaveBeenCalledWith([
        'ROLE_IT_ADMIN', 'ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_MANAGER', 'ROLE_69', 'ROLE_1337', 'ROLE_A38',
      ]);
    });

    it('verbirgt sie für alle anderen', () => {
      authService.hasAnyRole.mockReturnValue(false);
      component = baue();

      expect(component.canSeeReadiness).toBe(false);
    });
  });
});
