import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RolesComponent } from './roles.component';
import { AuthRoleDto, CorpTitleDto, GroupService } from '../../services/group.service';
import { ToastService } from '../../services/toast.service';

const title: CorpTitleDto = { titleId: 1, name: 'A38', mappedRole: 'ROLE_A38' };
const unmappedTitle: CorpTitleDto = { titleId: 2, name: 'Rekrut', mappedRole: null };

function role(overrides: Partial<AuthRoleDto> = {}): AuthRoleDto {
  return {
    name: 'ROLE_RECRUITER',
    description: 'Wirbt an',
    source: 'CUSTOM',
    special: false,
    grantingTitles: [],
    ...overrides,
  };
}

/** Ein Fehler, wie ihn der Server mit Begründung liefert. */
function serverError(message: string) {
  return new HttpErrorResponse({ status: 400, error: { message } });
}

describe('RolesComponent', () => {
  let component: RolesComponent;
  let groupService: {
    getCorporationTitles: ReturnType<typeof vi.fn>;
    saveTitleMapping: ReturnType<typeof vi.fn>;
    getRoles: ReturnType<typeof vi.fn>;
    saveRole: ReturnType<typeof vi.fn>;
    deleteRole: ReturnType<typeof vi.fn>;
  };
  let toastService: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    groupService = {
      getCorporationTitles: vi.fn().mockReturnValue(of([title, unmappedTitle])),
      saveTitleMapping: vi.fn().mockReturnValue(of(undefined)),
      getRoles: vi.fn().mockReturnValue(of([role()])),
      saveRole: vi.fn().mockReturnValue(of(role())),
      deleteRole: vi.fn().mockReturnValue(of(undefined)),
    };
    toastService = { success: vi.fn(), error: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: GroupService, useValue: groupService },
        { provide: ToastService, useValue: toastService },
      ],
    });
    component = TestBed.runInInjectionContext(() => new RolesComponent());
  });

  describe('Laden', () => {
    it('holt Titel und Rollen beim Start', () => {
      component.ngOnInit();

      expect(component.titles()).toHaveLength(2);
      expect(component.roles()).toHaveLength(1);
      expect(component.loading()).toBe(false);
    });

    it('bleibt geladen, solange eine der beiden Listen fehlt', () => {
      // Zwei getrennte Abfragen: ein halb geladener Zustand darf nicht als fertig gelten.
      groupService.getRoles.mockReturnValue(of([role()]));
      component.ngOnInit();
      component.loadingTitles.set(true);

      expect(component.loading()).toBe(true);
    });

    it('gibt die Begründung des Servers weiter, statt sie zu verschlucken', () => {
      // Ohne Ingame-Director-Token nennt der Server genau diesen Grund.
      groupService.getCorporationTitles.mockReturnValue(
        throwError(() => serverError('Kein Charakter mit Director-Rechten registriert.')),
      );

      component.ngOnInit();

      expect(toastService.error).toHaveBeenCalledWith(
        'Kein Charakter mit Director-Rechten registriert.',
      );
      expect(component.loadingTitles()).toBe(false);
    });

    it('meldet auch einen Fehlschlag ohne Begründung', () => {
      groupService.getRoles.mockReturnValue(throwError(() => new Error('kaputt')));

      component.ngOnInit();

      expect(toastService.error).toHaveBeenCalledWith('Die Rollen konnten nicht geladen werden.');
      expect(component.loadingRoles()).toBe(false);
    });
  });

  describe('Auswahlliste', () => {
    it('gruppiert die Rollen nach ihrer Herkunft', () => {
      groupService.getRoles.mockReturnValue(
        of([
          role({ name: 'ROLE_USER', source: 'BUILT_IN' }),
          role({ name: 'ROLE_RECRUITER', source: 'CUSTOM' }),
          role({ name: 'ROLE_A38', source: 'TITLE' }),
        ]),
      );
      component.ngOnInit();

      expect(component.roleGroups().map((group) => group.roles[0].name)).toEqual([
        'ROLE_USER',
        'ROLE_RECRUITER',
        'ROLE_A38',
      ]);
    });

    it('lässt leere Gruppen weg, statt Überschriften ohne Inhalt zu zeigen', () => {
      component.ngOnInit();

      expect(component.roleGroups()).toHaveLength(1);
      expect(component.roleGroups()[0].label).toBe('Eigene Rollen');
    });
  });

  describe('Zuordnung bearbeiten', () => {
    beforeEach(() => component.ngOnInit());

    it('öffnet den Dialog mit der bereits vergebenen Rolle', () => {
      component.openMapping(title);

      expect(component.editingTitle()).toBe(title);
      expect(component.selectedRole()).toBe('ROLE_A38');
    });

    it('öffnet einen nicht zugeordneten Titel ohne Vorauswahl', () => {
      component.openMapping(unmappedTitle);

      expect(component.selectedRole()).toBe('');
    });

    it('speichert die gewählte Rolle und lädt beide Listen neu', () => {
      component.openMapping(unmappedTitle);
      component.selectedRole.set('ROLE_RECRUITER');

      component.saveMapping();

      expect(groupService.saveTitleMapping).toHaveBeenCalledWith(2, 'ROLE_RECRUITER');
      expect(toastService.success).toHaveBeenCalled();
      expect(component.editingTitle()).toBeNull();
      // Ein frei eingetippter Name ist danach eine neue Rolle - der Katalog muss mit.
      expect(groupService.getRoles).toHaveBeenCalledTimes(2);
      expect(groupService.getCorporationTitles).toHaveBeenCalledTimes(2);
    });

    it('löst die Zuordnung mit einem leeren Rollennamen', () => {
      component.openMapping(title);
      component.selectedRole.set('');

      component.saveMapping();

      expect(groupService.saveTitleMapping).toHaveBeenCalledWith(1, '');
    });

    it('übernimmt einen frei eingetippten Rollennamen', () => {
      component.openMapping(unmappedTitle);
      component.selectedRole.set(component.customRoleOption);
      component.customRoleName.set('  Recruiter  ');

      component.saveMapping();

      expect(groupService.saveTitleMapping).toHaveBeenCalledWith(2, 'Recruiter');
    });

    it('sperrt das Speichern, solange der Freitext leer ist', () => {
      // Sonst wäre "eigene Rolle" ohne Eingabe stillschweigend ein Löschen.
      component.openMapping(title);
      component.selectedRole.set(component.customRoleOption);

      expect(component.canSaveMapping()).toBe(false);

      component.saveMapping();
      expect(groupService.saveTitleMapping).not.toHaveBeenCalled();

      component.customRoleName.set('Recruiter');
      expect(component.canSaveMapping()).toBe(true);
    });

    it('lässt den Dialog nach einem Fehlschlag offen', () => {
      groupService.saveTitleMapping.mockReturnValue(throwError(() => new Error('kaputt')));
      component.openMapping(title);

      component.saveMapping();

      expect(toastService.error).toHaveBeenCalled();
      expect(component.editingTitle()).not.toBeNull();
      expect(component.saving()).toBe(false);
    });

    it('tut ohne offenen Dialog nichts', () => {
      component.saveMapping();

      expect(groupService.saveTitleMapping).not.toHaveBeenCalled();
    });

    it('vergisst den Freitext beim Abbrechen', () => {
      component.openMapping(title);
      component.selectedRole.set(component.customRoleOption);
      component.customRoleName.set('Recruiter');

      component.closeMapping();

      expect(component.editingTitle()).toBeNull();
      expect(component.customRoleName()).toBe('');
    });
  });

  describe('Rollen anlegen', () => {
    beforeEach(() => component.ngOnInit());

    it('legt eine Rolle an und leert das Formular', () => {
      component.newRole = { name: 'Recruiter', description: 'Wirbt an', special: true };

      component.createRole();

      expect(groupService.saveRole).toHaveBeenCalledWith({
        name: 'Recruiter',
        description: 'Wirbt an',
        special: true,
      });
      expect(component.newRole.name).toBe('');
      expect(groupService.getRoles).toHaveBeenCalledTimes(2);
    });

    it('meldet den Namen so, wie der Server ihn gespeichert hat', () => {
      // Aus "Recruiter" wird ROLE_RECRUITER - danach sucht man sonst vergeblich.
      groupService.saveRole.mockReturnValue(of(role({ name: 'ROLE_RECRUITER' })));
      component.newRole = { name: 'recruiter', description: '', special: false };

      component.createRole();

      expect(toastService.success).toHaveBeenCalledWith('ROLE_RECRUITER gespeichert.');
    });

    it('fragt ohne Namen gar nicht erst beim Server nach', () => {
      component.newRole = { name: '   ', description: '', special: false };

      component.createRole();

      expect(groupService.saveRole).not.toHaveBeenCalled();
      expect(toastService.error).toHaveBeenCalled();
    });

    it('behält die Eingabe nach einem Fehlschlag', () => {
      groupService.saveRole.mockReturnValue(throwError(() => serverError('Name schon vergeben.')));
      component.newRole = { name: 'Recruiter', description: '', special: false };

      component.createRole();

      expect(toastService.error).toHaveBeenCalledWith('Name schon vergeben.');
      expect(component.newRole.name).toBe('Recruiter');
      expect(component.saving()).toBe(false);
    });
  });

  describe('Rollen löschen', () => {
    beforeEach(() => component.ngOnInit());

    it('fragt vor dem Löschen nach', () => {
      component.askDelete('ROLE_RECRUITER');

      expect(component.pendingDelete()).toBe('ROLE_RECRUITER');
      expect(groupService.deleteRole).not.toHaveBeenCalled();
    });

    it('löscht nach der Bestätigung', () => {
      component.askDelete('ROLE_RECRUITER');

      component.confirmDelete();

      expect(groupService.deleteRole).toHaveBeenCalledWith('ROLE_RECRUITER');
      expect(component.pendingDelete()).toBeNull();
      expect(toastService.success).toHaveBeenCalled();
      expect(groupService.getRoles).toHaveBeenCalledTimes(2);
    });

    it('bricht die Rückfrage folgenlos ab', () => {
      component.askDelete('ROLE_RECRUITER');

      component.cancelDelete();

      expect(component.pendingDelete()).toBeNull();
      expect(groupService.deleteRole).not.toHaveBeenCalled();
    });

    it('nennt den Grund, wenn ein Titel die Rolle noch vergibt', () => {
      groupService.deleteRole.mockReturnValue(
        throwError(() => serverError('ROLE_RECRUITER wird noch von Rekrut vergeben.')),
      );
      component.askDelete('ROLE_RECRUITER');

      component.confirmDelete();

      expect(toastService.error).toHaveBeenCalledWith(
        'ROLE_RECRUITER wird noch von Rekrut vergeben.',
      );
      expect(component.saving()).toBe(false);
    });

    it('tut ohne offene Rückfrage nichts', () => {
      component.confirmDelete();

      expect(groupService.deleteRole).not.toHaveBeenCalled();
    });
  });

  describe('Darstellung', () => {
    it('färbt das Symbol nach der zugeordneten Rolle', () => {
      expect(component.getRoleIconClass(null)).toBe('text-secondary');
      expect(component.getRoleIconClass('ROLE_DIRECTOR')).toBe('text-warning');
      expect(component.getRoleIconClass('ROLE_IT_ADMIN')).toBe('text-warning');
      expect(component.getRoleIconClass('ROLE_FLEET_COMMANDER')).toBe('text-primary');
      expect(component.getRoleIconClass('ROLE_INDUSTRY')).toBe('text-success');
      expect(component.getRoleIconClass('ROLE_SONSTIGES')).toBe('text-danger');
    });

    it('schreibt die Herkunft lesbar aus', () => {
      expect(component.sourceLabel('BUILT_IN')).toBe('Eingebaute Rollen');
      expect(component.sourceLabel('CUSTOM')).toBe('Eigene Rollen');
      expect(component.sourceLabel('TITLE')).toBe('Aus Ingame-Titeln entstanden');
    });

    it('bietet nur eigene Rollen zum Löschen an', () => {
      // Eingebaute stecken im Programm, aus Titeln entstandene hängen an der Zuordnung.
      expect(component.isDeletable(role({ source: 'CUSTOM' }))).toBe(true);
      expect(component.isDeletable(role({ source: 'BUILT_IN' }))).toBe(false);
      expect(component.isDeletable(role({ source: 'TITLE' }))).toBe(false);
    });
  });
});
