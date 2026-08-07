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

describe('Fitting-Seite: Selbstauskunft und Skillpläne', () => {
  let component: DoctrinesComponent;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let readinessService: Record<string, ReturnType<typeof vi.fn>>;
  let skillPlanService: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    const doctrineService = {
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

  describe('Eigener Stand', () => {
    it('holt beim Start den eigenen Stand und die Pläne', () => {
      component.ngOnInit();

      expect(readinessService['myReadiness']).toHaveBeenCalled();
      expect(skillPlanService['list']).toHaveBeenCalled();
      expect(component.myFit(1)?.typeName).toBe('Nestor');
    });

    it('unterscheidet die drei Stufen', () => {
      readinessService['myReadiness'].mockReturnValue(
        of([
          myFit({ fitId: 1, canFly: true, fullySkilled: true }),
          myFit({ fitId: 2, canFly: true, fullySkilled: false }),
          myFit({ fitId: 3, canFly: false, fullySkilled: false }),
        ]),
      );
      component.loadMyReadiness();

      expect(component.standing(1)).toBe('FULL');
      expect(component.standing(2)).toBe('CAN_FLY');
      expect(component.standing(3)).toBe('MISSING');
      expect(component.standingLabel(2)).toBe('Kannst du fliegen');
    });

    it('meldet ohne Skilldaten weder "kann" noch "kann nicht"', () => {
      // Ein Charakter ohne synchronisierte Skills darf nicht als unfähig dastehen.
      readinessService['myReadiness'].mockReturnValue(
        of([myFit({ skillDataAvailable: false, canFly: false })]),
      );
      component.loadMyReadiness();

      expect(component.standing(1)).toBe('UNKNOWN');
      expect(component.standingLabel(1)).toBe('Keine Skilldaten');
    });

    it('behandelt ein unbekanntes Fitting als unbekannt', () => {
      component.loadMyReadiness();

      expect(component.standing(999)).toBe('UNKNOWN');
    });

    it('bleibt bei einem Fehlschlag still', () => {
      // Die Fitting-Übersicht ist auch ohne Selbstauskunft nützlich, und wer
      // keine Skills synchronisiert hat, soll hier keinen Fehler vorgesetzt bekommen.
      readinessService['myReadiness'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadMyReadiness();

      expect(component.myFits().size).toBe(0);
      expect(toastService['error']).not.toHaveBeenCalled();
    });

    it('klappt die Details auf und wieder zu', () => {
      component.toggleFitDetails(1);
      expect(component.expandedFitId()).toBe(1);

      component.toggleFitDetails(1);
      expect(component.expandedFitId()).toBeNull();
    });
  });

  describe('Skillplan bearbeiten', () => {
    beforeEach(() => component.ngOnInit());

    it('übernimmt einen gesuchten Skill in den Plan', () => {
      component.newPlan();

      component.addSkill({ typeId: 3426, typeName: 'CPU Management' }, 4);

      expect(component.editingPlan()?.skills).toEqual([
        { skillTypeId: 3426, skillName: 'CPU Management', level: 4 },
      ]);
    });

    it('nimmt denselben Skill nicht zweimal auf', () => {
      component.newPlan();
      component.addSkill({ typeId: 3426, typeName: 'CPU Management' });

      component.addSkill({ typeId: 3426, typeName: 'CPU Management' });

      expect(component.editingPlan()?.skills).toHaveLength(1);
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('ändert die Stufe eines Eintrags', () => {
      component.newPlan();
      component.addSkill({ typeId: 3426, typeName: 'CPU Management' });

      component.setSkillLevel(3426, 3);

      expect(component.editingPlan()?.skills[0].level).toBe(3);
    });

    it('entfernt einen Eintrag wieder', () => {
      component.newPlan();
      component.addSkill({ typeId: 3426, typeName: 'CPU Management' });

      component.removeSkill(3426);

      expect(component.editingPlan()?.skills).toEqual([]);
    });

    it('sucht erst ab zwei Zeichen', () => {
      vi.useFakeTimers();
      component.newPlan();

      component.onSkillQuery('c');
      vi.advanceTimersByTime(300);
      expect(skillPlanService['searchSkills']).not.toHaveBeenCalled();

      component.onSkillQuery('cpu');
      vi.advanceTimersByTime(300);
      expect(skillPlanService['searchSkills']).toHaveBeenCalledWith('cpu');

      vi.useRealTimers();
    });

    it('übernimmt einen bestehenden Plan zum Bearbeiten', () => {
      component.editPlan(plan());

      expect(component.editingPlan()?.id).toBe(10);
      expect(component.editingPlan()?.name).toBe('Magic 14');
    });

    it('speichert nicht ohne Namen', () => {
      component.newPlan();

      component.savePlan();

      expect(skillPlanService['save']).not.toHaveBeenCalled();
      expect(toastService['error']).toHaveBeenCalled();
    });

    it('speichert und zieht beide Listen nach', () => {
      component.newPlan();
      component.setPlanName('Magic 14');

      component.savePlan();

      expect(skillPlanService['save']).toHaveBeenCalled();
      expect(component.editingPlan()).toBeNull();
      // Der Plan verändert den eigenen Stand - beides muss mit.
      expect(skillPlanService['list']).toHaveBeenCalledTimes(2);
      expect(readinessService['myReadiness']).toHaveBeenCalledTimes(2);
    });

    it('behält die Eingabe nach einem Fehlschlag', () => {
      skillPlanService['save'].mockReturnValue(
        throwError(() => ({ error: { message: 'Name schon vergeben.' } })),
      );
      component.newPlan();
      component.setPlanName('Magic 14');

      component.savePlan();

      expect(toastService['error']).toHaveBeenCalledWith('Name schon vergeben.');
      expect(component.editingPlan()?.name).toBe('Magic 14');
      expect(component.savingPlan()).toBe(false);
    });

    it('räumt den Dialog beim Schließen ab', () => {
      component.openPlanManager();
      component.newPlan();

      component.closePlanManager();

      expect(component.showPlanManager()).toBe(false);
      expect(component.editingPlan()).toBeNull();
    });
  });

  describe('Plantext einfügen', () => {
    beforeEach(() => {
      component.ngOnInit();
      component.newPlan();
    });

    it('übernimmt die erkannten Skills', () => {
      skillPlanService['importPlanText'].mockReturnValue(
        of({
          skills: [{ skillTypeId: 3413, skillName: 'Power Grid Management', level: 5 }],
          unresolved: [],
        }),
      );
      component.planImportText.set('Power Grid Management V');

      component.importPlanText();

      expect(component.editingPlan()?.skills).toHaveLength(1);
      expect(component.planImportText()).toBe('');
    });

    it('nimmt keinen Skill doppelt auf', () => {
      component.addSkill({ typeId: 3413, typeName: 'Power Grid Management' });
      skillPlanService['importPlanText'].mockReturnValue(
        of({
          skills: [{ skillTypeId: 3413, skillName: 'Power Grid Management', level: 5 }],
          unresolved: [],
        }),
      );
      component.planImportText.set('Power Grid Management V');

      component.importPlanText();

      expect(component.editingPlan()?.skills).toHaveLength(1);
    });

    it('nennt die Zeilen, die nicht erkannt wurden', () => {
      skillPlanService['importPlanText'].mockReturnValue(
        of({ skills: [], unresolved: ['Erfundener Skill'] }),
      );
      component.planImportText.set('Erfundener Skill V');

      component.importPlanText();

      expect(toastService['error']).toHaveBeenCalledWith('Nicht erkannt: Erfundener Skill');
    });

    it('schickt ohne Text gar nichts los', () => {
      component.planImportText.set('   ');

      component.importPlanText();

      expect(skillPlanService['importPlanText']).not.toHaveBeenCalled();
    });
  });

  describe('Zuordnung an ein Fitting', () => {
    beforeEach(() => component.ngOnInit());

    it('nimmt die bereits zugeordneten Pläne vorausgewählt auf', () => {
      readinessService['myReadiness'].mockReturnValue(of([myFit({ planNames: ['Magic 14'] })]));
      component.loadMyReadiness();

      component.openAssign(doctrine());

      expect([...component.assignedPlanIds()]).toEqual([10]);
    });

    it('schaltet einen Plan an und wieder ab', () => {
      component.openAssign(doctrine());

      component.togglePlanAssignment(10);
      expect(component.assignedPlanIds().has(10)).toBe(true);

      component.togglePlanAssignment(10);
      expect(component.assignedPlanIds().has(10)).toBe(false);
    });

    it('speichert die Zuordnung und schließt den Dialog', () => {
      component.openAssign(doctrine());
      component.togglePlanAssignment(10);

      component.saveAssignment();

      expect(skillPlanService['assign']).toHaveBeenCalledWith(1, [10]);
      expect(component.assigningDoctrine()).toBeNull();
    });

    it('tut ohne offenen Dialog nichts', () => {
      component.saveAssignment();

      expect(skillPlanService['assign']).not.toHaveBeenCalled();
    });
  });

  describe('Pläne löschen', () => {
    beforeEach(() => component.ngOnInit());

    it('warnt, wenn der Plan noch an Fittings hängt', async () => {
      await component.deletePlan(plan({ usedByFittings: 3 }));

      expect(confirmService.ask).toHaveBeenCalledWith(
        expect.stringContaining('Magic 14'),
        expect.stringContaining('3 Fitting'),
      );
      expect(skillPlanService['delete']).toHaveBeenCalledWith(10);
    });

    it('löscht nach Abbruch nichts', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.deletePlan(plan());

      expect(skillPlanService['delete']).not.toHaveBeenCalled();
    });
  });
});

describe('Fehlende Skills in die Zwischenablage', () => {
  let component: DoctrinesComponent;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let readinessService: Record<string, ReturnType<typeof vi.fn>>;
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    readinessService = {
      myReadiness: vi.fn().mockReturnValue(
        of([
          myFit({
            missingSkills: [
              { skillTypeId: 3413, skillName: 'Power Grid Management', requiredLevel: 5, currentLevel: 2 },
            ],
            missingPlanSkills: [
              { skillTypeId: 3394, skillName: 'Hull Upgrades', requiredLevel: 4, currentLevel: 0 },
            ],
          }),
        ]),
      ),
    };

    writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    TestBed.configureTestingModule({
      providers: [
        {
          provide: DoctrineService,
          useValue: { getDoctrines: vi.fn().mockReturnValue(of([doctrine()])) },
        },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: { ask: vi.fn().mockResolvedValue(true) } },
        { provide: AuthService, useValue: { hasAnyRole: vi.fn().mockReturnValue(true) } },
        { provide: ReadinessService, useValue: readinessService },
        {
          provide: SkillPlanService,
          useValue: { list: vi.fn().mockReturnValue(of([plan()])) },
        },
      ],
    });
    component = TestBed.runInInjectionContext(() => new DoctrinesComponent());
    component.ngOnInit();
  });

  afterEach(() => vi.unstubAllGlobals());

  it('legt beide Lücken zusammen als Plantext ab', async () => {
    // Was zum Fliegen fehlt und was zum Skillplan fehlt - genau das muss der
    // Pilot trainieren, in der Form, die EVE beim Einfügen annimmt.
    await component.copyMissingSkills(1);

    expect(writeText).toHaveBeenCalledWith('Power Grid Management V\nHull Upgrades IV');
    expect(toastService['success']).toHaveBeenCalled();
  });

  it('erkennt, ob es überhaupt etwas zu kopieren gibt', () => {
    expect(component.hasMissingSkills(1)).toBe(true);
    expect(component.hasMissingSkills(999)).toBe(false);
  });

  it('sagt Bescheid, wenn nichts fehlt, statt Leeres zu kopieren', () => {
    readinessService['myReadiness'].mockReturnValue(of([myFit()]));
    component.loadMyReadiness();

    component.copyMissingSkills(1);

    expect(writeText).not.toHaveBeenCalled();
    expect(toastService['info']).toHaveBeenCalled();
  });

  it('tut bei einem unbekannten Fitting nichts', () => {
    component.copyMissingSkills(999);

    expect(writeText).not.toHaveBeenCalled();
  });

  it('meldet einen Fehlschlag der Zwischenablage', async () => {
    writeText.mockRejectedValue(new Error('verweigert'));

    await component.copyMissingSkills(1);
    await Promise.resolve();

    expect(toastService['error']).toHaveBeenCalled();
  });

  it('kopiert auch einen kompletten Plan', async () => {
    await component.copyPlan(plan());

    expect(writeText).toHaveBeenCalledWith('Power Grid Management V');
  });

  it('meldet einen leeren Plan, statt Leeres zu kopieren', () => {
    component.copyPlan(plan({ skills: [] }));

    expect(writeText).not.toHaveBeenCalled();
    expect(toastService['info']).toHaveBeenCalled();
  });
});
