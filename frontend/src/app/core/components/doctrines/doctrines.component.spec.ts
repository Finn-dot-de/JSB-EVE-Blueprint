import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DoctrinesComponent } from './doctrines.component';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { DoctrineService, FleetDoctrine } from '../../services/doctrine.service';
import { MyFitDto, ReadinessService } from '../../services/readiness.service';
import { SkillPlanDto, SkillPlanService } from '../../services/skill-plan.service';
import { ToastService } from '../../services/toast.service';

/** Der eigene Stand zu einem Fitting, wie ihn die Selbstauskunft liefert. */
function myFit(overrides: Partial<MyFitDto> = {}): MyFitDto {
  return {
    fitId: 1,
    fitName: 'Logi',
    doctrineName: 'Armor',
    typeId: 33472,
    typeName: 'Nestor',
    iconUrl: '',
    renderUrl: '',
    moduleCount: 5,
    planNames: [],
    hasShip: true,
    owned: 1,
    canFly: true,
    fullySkilled: true,
    skillDataAvailable: true,
    bestCharacterName: 'Pilot Eins',
    missingSkills: [],
    missingPlanSkills: [],
    ...overrides,
  };
}

/** Ein Skillplan, wie ihn der Server liefert. */
function plan(overrides: Partial<SkillPlanDto> = {}): SkillPlanDto {
  return {
    id: 10,
    name: 'Magic 14',
    description: 'Die Grundlagen',
    skills: [{ skillTypeId: 3413, skillName: 'Power Grid Management', level: 5 }],
    usedByFittings: 2,
    ...overrides,
  };
}

/** Ein gespeichertes Fitting, wie es der Server liefert. */
function doctrine(overrides: Partial<FleetDoctrine> = {}): FleetDoctrine {
  return {
    id: 1,
    doctrineName: 'Armor',
    shipType: 'Nestor',
    name: 'Logi',
    eftString: '[Nestor, Logi]',
    ...overrides,
  } as FleetDoctrine;
}

describe('DoctrinesComponent', () => {
  let component: DoctrinesComponent;
  let doctrineService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let readinessService: Record<string, ReturnType<typeof vi.fn>>;
  let skillPlanService: Record<string, ReturnType<typeof vi.fn>>;
  let clipboard: { writeText: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    doctrineService = {
      getDoctrines: vi.fn().mockReturnValue(of([doctrine()])),
      createDoctrine: vi.fn().mockReturnValue(of(doctrine())),
      updateDoctrine: vi.fn().mockReturnValue(of(doctrine())),
      deleteDoctrine: vi.fn().mockReturnValue(of(null)),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };
    readinessService = { myReadiness: vi.fn().mockReturnValue(of([myFit()])) };
    skillPlanService = {
      list: vi.fn().mockReturnValue(of([plan()])),
      searchSkills: vi.fn().mockReturnValue(of([{ typeId: 3426, typeName: 'CPU Management' }])),
      save: vi.fn().mockReturnValue(of(plan())),
      delete: vi.fn().mockReturnValue(of(null)),
      importPlanText: vi.fn().mockReturnValue(of({ skills: [], unresolved: [] })),
      assign: vi.fn().mockReturnValue(of(null)),
    };

    clipboard = { writeText: vi.fn().mockResolvedValue(undefined) };
    vi.stubGlobal('navigator', { clipboard });

    TestBed.configureTestingModule({
      providers: [
        { provide: DoctrineService, useValue: doctrineService },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: AuthService, useValue: { hasAnyRole: vi.fn().mockReturnValue(true) } },
        { provide: ReadinessService, useValue: readinessService },
        { provide: SkillPlanService, useValue: skillPlanService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new DoctrinesComponent());
  });

  afterEach(() => vi.unstubAllGlobals());

  describe('Liste', () => {
    it('lädt die Fittings beim Start', () => {
      component.ngOnInit();

      expect(component.doctrines()).toHaveLength(1);
    });

    it('gruppiert die Fittings nach Doktrin und sortiert beides', () => {
      component.doctrines.set([
        doctrine({ id: 1, doctrineName: 'Shield', shipType: 'Basilisk' }),
        doctrine({ id: 2, doctrineName: 'Armor', shipType: 'Nestor' }),
        doctrine({ id: 3, doctrineName: 'Armor', shipType: 'Guardian' }),
      ]);

      const groups = component.groupedDoctrines();

      expect(groups.map((group) => group.name)).toEqual(['Armor', 'Shield']);
      expect(groups[0].docs.map((doc) => doc.shipType)).toEqual(['Guardian', 'Nestor']);
    });

    it('sammelt Fittings ohne Doktrin unter einem Auffangnamen', () => {
      component.doctrines.set([doctrine({ doctrineName: '' })]);

      expect(component.groupedDoctrines()[0].name).toBe('Ungruppiert');
    });

    it('filtert über Fitting-Name, Schiffstyp und Doktrin', () => {
      component.doctrines.set([
        doctrine({ id: 1, name: 'Logi', shipType: 'Nestor', doctrineName: 'Armor' }),
        doctrine({ id: 2, name: 'DPS', shipType: 'Megathron', doctrineName: 'Shield' }),
      ]);

      component.searchQuery.set('nestor');
      expect(component.groupedDoctrines()).toHaveLength(1);

      component.searchQuery.set('dps');
      expect(component.groupedDoctrines()[0].docs[0].shipType).toBe('Megathron');

      component.searchQuery.set('shield');
      expect(component.groupedDoctrines()[0].name).toBe('Shield');
    });
  });

  describe('Fitting-Ansicht', () => {
    it('zerlegt ein Fitting in seine Slot-Blöcke', () => {
      component.selectedDoctrine.set(
        doctrine({
          eftString: [
            '[Nestor, Logi]',
            'Damage Control II',
            '',
            'Large Shield Extender II',
            '',
            'Heavy Missile Launcher II',
          ].join('\n'),
        }),
      );

      const groups = component.parsedGroups();

      expect(groups).toHaveLength(3);
      expect(groups[0].modules).toEqual(['Damage Control II']);
      expect(groups[2].modules).toEqual(['Heavy Missile Launcher II']);
    });

    it('lässt Pyfas Platzhalter für leere Slots weg', () => {
      component.selectedDoctrine.set(
        doctrine({ eftString: '[Nestor, Logi]\n[Empty High slot]\nDamage Control II' }),
      );

      expect(component.parsedGroups()[0].modules).toEqual(['Damage Control II']);
    });

    it('gibt ohne gewähltes Fitting nichts zurück', () => {
      component.selectedDoctrine.set(null);

      expect(component.parsedGroups()).toEqual([]);
    });
  });

  describe('Anlegen und Ändern', () => {
    it('liest Schiffstyp und Namen aus der Kopfzeile', () => {
      component.newEftInput.set('[Nestor, Logi Standard]\nDamage Control II');

      component.parseAndSaveFitting();

      expect(doctrineService['createDoctrine']).toHaveBeenCalledWith(
        expect.objectContaining({ shipType: 'Nestor', name: 'Logi Standard' }),
      );
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('leitet die Doktrin aus den ersten beiden Wörtern des Fitting-Namens ab', () => {
      // So landen "Armor Logi A" und "Armor Logi B" automatisch zusammen.
      component.newEftInput.set('[Nestor, Armor Logi Variante A]');

      component.parseAndSaveFitting();

      expect(doctrineService['createDoctrine']).toHaveBeenCalledWith(
        expect.objectContaining({ doctrineName: 'Armor Logi' }),
      );
    });

    it('nimmt bei einem einwortigen Namen diesen als Doktrin', () => {
      component.newEftInput.set('[Nestor, Solo]');

      component.parseAndSaveFitting();

      expect(doctrineService['createDoctrine']).toHaveBeenCalledWith(
        expect.objectContaining({ doctrineName: 'Solo' }),
      );
    });

    it('bevorzugt eine von Hand eingetragene Doktrin', () => {
      component.newEftInput.set('[Nestor, Logi Standard]');
      component.newDoctrineName.set('Eigene Doktrin');

      component.parseAndSaveFitting();

      expect(doctrineService['createDoctrine']).toHaveBeenCalledWith(
        expect.objectContaining({ doctrineName: 'Eigene Doktrin' }),
      );
    });

    it('schickt beim Bearbeiten ein Update statt eines neuen Fittings', () => {
      component.openEditModal(doctrine({ id: 7 }));

      component.parseAndSaveFitting();

      expect(doctrineService['updateDoctrine']).toHaveBeenCalledWith(7, expect.anything());
      expect(doctrineService['createDoctrine']).not.toHaveBeenCalled();
    });

    it('weist eine unbrauchbare Kopfzeile ab', () => {
      component.newEftInput.set('Damage Control II');

      component.parseAndSaveFitting();

      expect(doctrineService['createDoctrine']).not.toHaveBeenCalled();
      expect(toastService['error']).toHaveBeenCalledWith(expect.stringContaining('EFT Format'));
    });

    it('speichert leere Eingaben gar nicht erst', () => {
      component.newEftInput.set('   ');

      component.parseAndSaveFitting();

      expect(doctrineService['createDoctrine']).not.toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag beim Speichern', () => {
      doctrineService['createDoctrine'].mockReturnValue(
        throwError(() => ({ error: { message: 'Datenbank weg' } })),
      );
      component.newEftInput.set('[Nestor, Logi]');

      component.parseAndSaveFitting();

      expect(toastService['error']).toHaveBeenCalledWith(expect.stringContaining('Datenbank weg'));
      expect(component.isSubmitting()).toBe(false);
    });
  });

  describe('Dialoge', () => {
    it('räumt das Formular beim Anlegen ab', () => {
      component.newEftInput.set('alter Text');
      component.newDoctrineName.set('alte Doktrin');
      component.editingDoctrineId.set(5);

      component.openCreateModal();

      expect(component.newEftInput()).toBe('');
      expect(component.newDoctrineName()).toBe('');
      expect(component.editingDoctrineId()).toBeNull();
      expect(component.showCreateModal()).toBe(true);
    });

    it('füllt das Formular beim Bearbeiten vor', () => {
      component.openEditModal(doctrine({ id: 7, doctrineName: 'Armor' }));

      expect(component.editingDoctrineId()).toBe(7);
      expect(component.newDoctrineName()).toBe('Armor');
      expect(component.newEftInput()).toBe('[Nestor, Logi]');
    });

    it('lässt den Auffangnamen beim Bearbeiten leer', () => {
      // "Ungruppiert" ist kein Name, den jemand eingetippt hat.
      component.openEditModal(doctrine({ doctrineName: 'Ungruppiert' }));

      expect(component.newDoctrineName()).toBe('');
    });

    it('öffnet und schließt die Detailansicht', () => {
      component.openDetails(doctrine());
      expect(component.selectedDoctrine()).not.toBeNull();

      component.closeModals();
      expect(component.selectedDoctrine()).toBeNull();
      expect(component.showCreateModal()).toBe(false);
    });
  });

  describe('Zwischenablage und Löschen', () => {
    it('kopiert ein Fitting und schließt den Dialog', async () => {
      component.openDetails(doctrine());

      await component.copyToClipboard('[Nestor, Logi]');

      expect(clipboard.writeText).toHaveBeenCalledWith('[Nestor, Logi]');
      expect(toastService['info']).toHaveBeenCalled();
      expect(component.selectedDoctrine()).toBeNull();
    });

    it('kopiert nichts, wenn kein Fitting vorliegt', () => {
      component.copyToClipboard(undefined);

      expect(clipboard.writeText).not.toHaveBeenCalled();
    });

    it('meldet, wenn die Zwischenablage nicht mitspielt', async () => {
      clipboard.writeText.mockRejectedValue(new Error('verweigert'));

      await component.copyToClipboard('[Nestor, Logi]');
      await Promise.resolve();

      expect(toastService['error']).toHaveBeenCalled();
    });

    it('löscht ein Fitting erst nach Rückfrage', async () => {
      await component.deleteDoctrine(7);

      expect(confirmService.ask).toHaveBeenCalled();
      expect(doctrineService['deleteDoctrine']).toHaveBeenCalledWith(7);
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('löscht nichts, wenn die Rückfrage verneint wird', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.deleteDoctrine(7);

      expect(doctrineService['deleteDoctrine']).not.toHaveBeenCalled();
    });
  });

  it('meldet die Rechte für die Oberfläche', () => {
    expect(component.isFleetCommander).toBe(true);
  });
});
