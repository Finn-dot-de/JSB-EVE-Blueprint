import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FleetManagerComponent } from './fleet-manager.component';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { FleetService } from '../../services/fleet.service';
import { ReadinessService } from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';
import { AccountReadinessDto, DoctrineReadinessDto } from '../../services/readiness.service';

/** Ein Account, wie ihn das Readiness-Board liefert. */
function account(mainName: string, characterNames: string[] = []): AccountReadinessDto {
  return {
    mainId: 1000,
    mainName,
    portraitUrl: '',
    corporationName: 'Corp',
    owned: 1,
    charactersOwning: 1,
    canFly: true,
    pilotsCapable: 1,
    skillDataAvailable: true,
    bestSkillsMet: 1,
    skillsRequired: 1,
    hasShip: true,
    hasSkills: true,
    isReady: true,
    characters: characterNames.map((characterName) => ({
      characterId: 1,
      characterName,
      portraitUrl: '',
      main: false,
      owned: 1,
      skillDataAvailable: true,
      canFly: true,
      skillsMet: 1,
      skillsRequired: 1,
      missingSkills: [],
    })),
  } as AccountReadinessDto;
}

function board(typeIds: number[]): DoctrineReadinessDto {
  return {
    doctrineName: 'Armor',
    accountsTotal: 1,
    hullsChecked: typeIds.length,
    hulls: typeIds.map((typeId) => ({
      typeId,
      typeName: `Huelle ${typeId}`,
      iconUrl: '',
      renderUrl: '',
      requiredSkills: [],
      hullsTotal: 1,
      accountsReady: 1,
      accountsTotal: 1,
      coverage: 1,
      ready: [],
      notReady: [],
    })),
  } as DoctrineReadinessDto;
}

describe('FleetManagerComponent', () => {
  let component: FleetManagerComponent;
  let fleetService: Record<string, ReturnType<typeof vi.fn>>;
  let readinessService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let authService: { hasAnyRole: ReturnType<typeof vi.fn> };
  let clipboard: { writeText: ReturnType<typeof vi.fn> };

  const fleet = { id: 55, fleetName: 'Roam', trackingType: 'LIVE', trackingCode: 'abc' };

  beforeEach(() => {
    vi.useFakeTimers();

    fleetService = {
      getRecentFleets: vi.fn().mockReturnValue(of([fleet])),
      getFleetAttendance: vi.fn().mockReturnValue(of([])),
      createFleet: vi.fn().mockReturnValue(of(fleet)),
      syncFleetViaEsi: vi.fn().mockReturnValue(of(3)),
      closeFleet: vi.fn().mockReturnValue(of(null)),
    };
    readinessService = {
      doctrines: vi.fn().mockReturnValue(of(['Armor', 'Shield'])),
      checkBoard: vi.fn().mockReturnValue(of(board([33472]))),
      sandbox: vi.fn().mockReturnValue(of({ fit: {}, board: {} })),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };
    authService = { hasAnyRole: vi.fn().mockReturnValue(true) };

    clipboard = { writeText: vi.fn().mockResolvedValue(undefined) };
    vi.stubGlobal('navigator', { clipboard });
    vi.stubGlobal('window', { location: { origin: 'https://auth.example.org' } });

    TestBed.configureTestingModule({
      providers: [
        { provide: FleetService, useValue: fleetService },
        { provide: ReadinessService, useValue: readinessService },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: AuthService, useValue: authService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new FleetManagerComponent());
  });

  afterEach(() => {
    component.ngOnDestroy();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  describe('Flottenliste', () => {
    it('lädt die Flotten beim Start und wählt die erste aus', () => {
      component.ngOnInit();

      expect(component.recentFleets()).toHaveLength(1);
      expect(component.selectedFleetId()).toBe(55);
      expect(component.selectedFleetObj()?.fleetName).toBe('Roam');
    });

    it('lädt die Liste regelmäßig nach', () => {
      // Ein laufender FAT soll ohne Neuladen aktuell bleiben.
      component.ngOnInit();
      expect(fleetService['getRecentFleets']).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(10_000);

      expect(fleetService['getRecentFleets']).toHaveBeenCalledTimes(2);
    });

    it('stoppt das Nachladen beim Verlassen der Seite', () => {
      component.ngOnInit();

      component.ngOnDestroy();
      vi.advanceTimersByTime(30_000);

      expect(fleetService['getRecentFleets']).toHaveBeenCalledTimes(1);
    });

    it('behält eine bereits gewählte Flotte beim Nachladen', () => {
      component.selectFleet(55);
      fleetService['getRecentFleets'].mockReturnValue(of([fleet, { ...fleet, id: 99 }]));

      component.loadRecentFleets();

      expect(component.selectedFleetId()).toBe(55);
    });

    it('lädt die Anwesenheitsliste zur gewählten Flotte', () => {
      component.selectFleet(55);

      expect(fleetService['getFleetAttendance']).toHaveBeenCalledWith(55);
    });
  });

  describe('Flotte anlegen', () => {
    it('legt ohne Namen gar nichts an', () => {
      component.fleetName = '';

      component.createFleet();

      expect(fleetService['createFleet']).not.toHaveBeenCalled();
    });

    it('schickt die Eingaben und räumt das Formular auf', () => {
      component.fleetName = 'Roam';
      component.doctrineInput = 'Armor';
      component.expiryMinutes = 30;
      component.trackingType = 'LINK';
      component.showCreateModal.set(true);

      component.createFleet();

      expect(fleetService['createFleet']).toHaveBeenCalledWith({
        fleetName: 'Roam',
        doctrine: 'Armor',
        linkExpiryMinutes: 30,
        trackingType: 'LINK',
      });
      expect(component.fleetName).toBe('');
      expect(component.showCreateModal()).toBe(false);
      expect(component.isCreating()).toBe(false);
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('zeigt die Meldung des Servers, wenn das Anlegen scheitert', () => {
      fleetService['createFleet'].mockReturnValue(
        throwError(() => ({ error: { message: 'Du bist in keiner Flotte.' } })),
      );
      component.fleetName = 'Roam';

      component.createFleet();

      expect(toastService['error']).toHaveBeenCalledWith('Du bist in keiner Flotte.');
      expect(component.isCreating()).toBe(false);
    });
  });

  describe('ESI-Abgleich und Beenden', () => {
    it('meldet die Zahl neu erfasster Teilnehmer', () => {
      component.syncEsi(55);

      expect(toastService['success']).toHaveBeenCalledWith(
        expect.stringContaining('3 neue Member'),
      );
      expect(component.isSyncing()).toBe(false);
    });

    it('zeigt die Meldung des Servers, wenn der Abgleich scheitert', () => {
      fleetService['syncFleetViaEsi'].mockReturnValue(
        throwError(() => ({ error: { message: 'Du bist offline.' } })),
      );

      component.syncEsi(55);

      expect(toastService['error']).toHaveBeenCalledWith('Du bist offline.');
      expect(component.isSyncing()).toBe(false);
    });

    it('beendet einen FAT erst nach Rückfrage', async () => {
      await component.closeFleet(55);

      expect(confirmService.ask).toHaveBeenCalled();
      expect(fleetService['closeFleet']).toHaveBeenCalledWith(55);
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('beendet nichts, wenn die Rückfrage verneint wird', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.closeFleet(55);

      expect(fleetService['closeFleet']).not.toHaveBeenCalled();
    });
  });

  describe('Teilnahme-Link', () => {
    it('baut die Adresse aus dem Tracking-Code', () => {
      expect(component.getJoinUrlFor('abc-123')).toBe(
        'https://auth.example.org/fleet/join/abc-123',
      );
    });

    it('bestätigt das Kopieren in die Zwischenablage', async () => {
      component.copyLinkToClipboard('abc-123');
      await Promise.resolve();

      expect(clipboard.writeText).toHaveBeenCalledWith(
        'https://auth.example.org/fleet/join/abc-123',
      );
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('meldet, wenn die Zwischenablage nicht mitspielt', async () => {
      clipboard.writeText.mockRejectedValue(new Error('verweigert'));

      component.copyLinkToClipboard('abc-123');
      await Promise.resolve();
      await Promise.resolve();

      expect(toastService['error']).toHaveBeenCalled();
    });

    it('kopiert ein Fitting in die Zwischenablage', async () => {
      component.copyFitToClipboard('[Nestor, Fit]');
      await Promise.resolve();

      expect(clipboard.writeText).toHaveBeenCalledWith('[Nestor, Fit]');
      expect(toastService['info']).toHaveBeenCalled();
    });
  });

  describe('Reiter und Readiness-Board', () => {
    it('lädt beim ersten Wechsel auf das Board die Doktrinen nach', () => {
      component.setTab('BOARD');

      expect(readinessService['doctrines']).toHaveBeenCalled();
      expect(component.selectedDoctrine).toBe('Armor');
      expect(component.board()).not.toBeNull();
    });

    it('lädt die Doktrinen nicht erneut, wenn sie schon da sind', () => {
      component.setTab('BOARD');
      readinessService['doctrines'].mockClear();

      component.setTab('FLEETS');
      component.setTab('BOARD');

      expect(readinessService['doctrines']).not.toHaveBeenCalled();
    });

    it('klappt beim Laden die erste Hülle auf', () => {
      component.selectedDoctrine = 'Armor';

      component.loadBoard();

      expect(component.isHullExpanded(33472)).toBe(true);
      expect(component.loadingBoard()).toBe(false);
    });

    it('lädt ohne gewählte Doktrin kein Board', () => {
      component.selectedDoctrine = null;

      component.loadBoard();

      expect(readinessService['checkBoard']).not.toHaveBeenCalled();
    });

    it('räumt beim Wechsel der Doktrin das alte Board ab', () => {
      component.setTab('BOARD');
      expect(component.board()).not.toBeNull();

      component.selectedDoctrine = 'Shield';
      component.onDoctrineChange();

      expect(readinessService['checkBoard']).toHaveBeenCalledWith('Shield');
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
    it('klappt eine Hülle auf und wieder zu', () => {
      component.toggleHull(1);
      expect(component.isHullExpanded(1)).toBe(true);

      component.toggleHull(1);
      expect(component.isHullExpanded(1)).toBe(false);
    });

    it('klappt einen Account je Hülle getrennt auf', () => {
      // Derselbe Account kann unter zwei Hüllen unterschiedlich aufgeklappt sein.
      component.toggleAccount(1, 1000);

      expect(component.isAccountExpanded(1, 1000)).toBe(true);
      expect(component.isAccountExpanded(2, 1000)).toBe(false);
    });
  });

  describe('Sandbox', () => {
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
  });

  describe('Mitglieder-Filter', () => {
    it('gibt ohne Suchbegriff alles zurück', () => {
      const accounts = [account('Alpha'), account('Beta')];

      expect(component.filterAccounts(accounts)).toHaveLength(2);
    });

    it('findet einen Account über seinen Namen', () => {
      component.memberFilter.set('alph');

      expect(component.filterAccounts([account('Alpha'), account('Beta')])).toHaveLength(1);
    });

    it('findet einen Account auch über einen seiner Charaktere', () => {
      component.memberFilter.set('scout');

      const accounts = [account('Alpha', ['Mein Scout']), account('Beta')];

      expect(component.filterAccounts(accounts)).toHaveLength(1);
    });
  });

  describe('Darstellung', () => {
    it('zeigt die Abdeckung als Prozentwert', () => {
      expect(component.percent(0.755)).toBe('76 %');
      expect(component.coverageWidth(0.5)).toBe('50%');
    });

    it('begrenzt die Balkenbreite auf sinnvolle Werte', () => {
      expect(component.coverageWidth(-1)).toBe('0%');
      expect(component.coverageWidth(2)).toBe('100%');
    });

    it('färbt die Abdeckung nach ihrer Höhe', () => {
      expect(component.coverageClass(0.9)).toBe('green');
      expect(component.coverageClass(0.5)).toBe('orange');
      expect(component.coverageClass(0.1)).toBe('red');
    });

    it('meldet die Rechte für die Oberfläche', () => {
      expect(component.isFleetCommander).toBe(true);
      expect(component.canSeeReadiness).toBe(true);
    });
  });
});
