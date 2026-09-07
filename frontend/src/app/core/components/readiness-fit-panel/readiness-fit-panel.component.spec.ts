import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReadinessFitPanelComponent } from './readiness-fit-panel.component';
import { AccountReadinessDto } from '../../services/readiness.service';
import { ToastService } from '../../services/toast.service';

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
      canFlyHull: true,
      skillsMet: 1,
      skillsRequired: 1,
      missingSkills: [],
      missingPlanSkills: [],
    })),
  } as AccountReadinessDto;
}

describe('ReadinessFitPanelComponent', () => {
  let component: ReadinessFitPanelComponent;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };

    TestBed.configureTestingModule({
      providers: [{ provide: ToastService, useValue: toastService }],
    });
    component = TestBed.runInInjectionContext(() => new ReadinessFitPanelComponent());
  });

  describe('Mitglieder-Filter', () => {
    it('gibt ohne Suchbegriff alles zurück', () => {
      const accounts = [account('Alpha'), account('Beta')];

      expect(component.filterAccounts(accounts)).toHaveLength(2);
    });

    it('findet einen Account über seinen Namen', () => {
      component.memberFilter = 'alph';

      expect(component.filterAccounts([account('Alpha'), account('Beta')])).toHaveLength(1);
    });

    it('findet einen Account auch über einen seiner Charaktere', () => {
      component.memberFilter = 'scout';

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
  });

  describe('Aufklapp-Zustand', () => {
    it('liest den Zustand der Seite darüber und hält keinen eigenen', () => {
      // Die Kachel darf sich nicht merken, wer offen ist: Beim Doktrinwechsel
      // räumt die Seite darüber ab, und ein eigener Zustand überlebte das.
      component.fitKey = 7;
      component.openAccounts = new Set(['7:1000']);

      expect(component.isAccountOpen(1000)).toBe(true);
      expect(component.isAccountOpen(2000)).toBe(false);
    });

    it('verwechselt zwei Kacheln nicht, weil der Schlüssel im Namen steckt', () => {
      component.fitKey = 8;
      component.openAccounts = new Set(['7:1000']);

      expect(component.isAccountOpen(1000)).toBe(false);
    });
  });

  describe('Fehlende Skills kopieren', () => {
    const pilot = (missing: number, plan: number) =>
      ({
        characterId: 1,
        characterName: 'Pilot',
        missingSkills: Array.from({ length: missing }, (_, i) => ({
          skillTypeId: i + 1,
          skillName: `Skill ${i + 1}`,
          currentLevel: 0,
          requiredLevel: 4,
        })),
        missingPlanSkills: Array.from({ length: plan }, (_, i) => ({
          skillTypeId: 100 + i,
          skillName: `Plan ${i + 1}`,
          currentLevel: 0,
          requiredLevel: 3,
        })),
      }) as never;

    it('nennt beide Quellen zusammen als Grund, den Knopf überhaupt zu zeigen', () => {
      expect(component.hasMissingSkills(pilot(0, 0))).toBe(false);
      expect(component.hasMissingSkills(pilot(1, 0))).toBe(true);
      expect(component.hasMissingSkills(pilot(0, 1))).toBe(true);
    });

    it('sagt es, statt eine leere Zwischenablage zu hinterlassen', async () => {
      await component.copyMissingSkills(pilot(0, 0));

      expect(toastService['info']).toHaveBeenCalled();
    });
  });
});
