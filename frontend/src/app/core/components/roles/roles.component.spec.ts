import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RolesComponent } from './roles.component';
import { AuthRoleDto, CorpTitleDto, GroupService } from '../../services/group.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { AdminAccountDto, CharacterService } from '../../services/character.service';
import {
  CharacterRolesDto,
  RoleAssignmentService,
  RoleAuditDto,
  RoleStateDto,
} from '../../services/role-assignment.service';

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

/** Eine Rollenbewertung, wie sie der Server für einen Charakter liefert. */
function roleState(overrides: Partial<RoleStateDto> = {}): RoleStateDto {
  return {
    roleName: 'ROLE_RECRUITER',
    description: 'Wirbt an',
    source: 'CUSTOM',
    held: false,
    survivesSync: true,
    assignable: true,
    revocable: false,
    grantingTitles: [],
    note: 'Frei vergebbar.',
    ...overrides,
  };
}

function characterRoles(roles: RoleStateDto[]): CharacterRolesDto {
  return {
    characterId: 42,
    characterName: 'Pilot Eins',
    portraitUrl: 'portrait.jpg',
    roles,
  };
}

function auditEntry(overrides: Partial<RoleAuditDto> = {}): RoleAuditDto {
  return {
    id: 1,
    characterId: 42,
    characterName: 'Pilot Eins',
    portraitUrl: 'portrait.jpg',
    roleName: 'ROLE_RECRUITER',
    action: 'GRANT',
    actorCharacterId: 7,
    actorName: 'Chef',
    selfAssigned: false,
    reason: 'übernimmt die Rekrutierung',
    occurredAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

const account: AdminAccountDto = {
  mainId: 42,
  mainName: 'Pilot Eins',
  portraitUrl: 'portrait.jpg',
  corporationName: 'Own Corp',
  alts: [{ id: 43, name: 'Pilot Zwei', portraitUrl: 'alt.jpg', corporationName: 'Own Corp' }],
};

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
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let characterService: { getAllAccounts: ReturnType<typeof vi.fn> };
  let assignmentService: {
    rolesOf: ReturnType<typeof vi.fn>;
    grant: ReturnType<typeof vi.fn>;
    revoke: ReturnType<typeof vi.fn>;
    auditFor: ReturnType<typeof vi.fn>;
    recentAudit: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    groupService = {
      getCorporationTitles: vi.fn().mockReturnValue(of([title, unmappedTitle])),
      saveTitleMapping: vi.fn().mockReturnValue(of(undefined)),
      getRoles: vi.fn().mockReturnValue(of([role()])),
      saveRole: vi.fn().mockReturnValue(of(role())),
      deleteRole: vi.fn().mockReturnValue(of(undefined)),
    };
    toastService = { success: vi.fn(), error: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };
    characterService = { getAllAccounts: vi.fn().mockReturnValue(of([account])) };
    assignmentService = {
      rolesOf: vi.fn().mockReturnValue(of(characterRoles([roleState()]))),
      grant: vi.fn().mockReturnValue(of(auditEntry())),
      revoke: vi.fn().mockReturnValue(of(auditEntry({ action: 'REVOKE' }))),
      auditFor: vi.fn().mockReturnValue(of([auditEntry()])),
      recentAudit: vi.fn().mockReturnValue(of([])),
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: GroupService, useValue: groupService },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: CharacterService, useValue: characterService },
        { provide: RoleAssignmentService, useValue: assignmentService },
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

  // ===============================================================
  // Rollen eines einzelnen Charakters
  // ===============================================================

  describe('Charaktersuche', () => {
    beforeEach(() => component.ngOnInit());

    it('legt Mains und Alts in dieselbe Liste', () => {
      // Eine Rolle hängt am Charakter, nicht am Account - ein Alt muss wählbar sein.
      component.characterQuery.set('Pilot');

      expect(component.characterMatches().map((choice) => choice.name)).toEqual([
        'Pilot Eins',
        'Pilot Zwei',
      ]);
      expect(component.characterMatches()[0].mainName).toBeNull();
      expect(component.characterMatches()[1].mainName).toBe('Pilot Eins');
      expect(component.loadingCharacters()).toBe(false);
    });

    it('sucht erst ab zwei Zeichen', () => {
      // Beim ersten Buchstaben stünde die halbe Corp da - darin sucht niemand.
      component.characterQuery.set('P');

      expect(component.characterMatches()).toEqual([]);
    });

    it('findet unabhängig von der Gross- und Kleinschreibung', () => {
      component.characterQuery.set('  zWEi ');

      expect(component.characterMatches()).toHaveLength(1);
    });

    it('meldet die Zahl der nicht gezeigten Treffer', () => {
      // Sonst wähnte sich der Nutzer am Ende der Liste und suchte den Rest nie.
      characterService.getAllAccounts.mockReturnValue(
        of([
          {
            ...account,
            alts: Array.from({ length: 12 }, (_, index) => ({
              id: 100 + index,
              name: `Pilot Nummer ${index}`,
              portraitUrl: 'alt.jpg',
              corporationName: 'Own Corp',
            })),
          },
        ]),
      );
      component.ngOnInit();
      component.characterQuery.set('Pilot');

      expect(component.characterMatches()).toHaveLength(8);
      expect(component.hiddenMatches()).toBe(5);
    });

    it('meldet einen Fehlschlag mit der Begründung des Servers', () => {
      characterService.getAllAccounts.mockReturnValue(
        throwError(() => serverError('Keine Berechtigung.')),
      );

      component.ngOnInit();

      expect(toastService.error).toHaveBeenCalledWith('Keine Berechtigung.');
      expect(component.loadingCharacters()).toBe(false);
    });
  });

  describe('Charakter wählen', () => {
    beforeEach(() => component.ngOnInit());

    it('lädt Rollen und Verlauf des Charakters und räumt die Suche weg', () => {
      component.characterQuery.set('Pilot');

      component.selectCharacter(42);

      expect(assignmentService.rolesOf).toHaveBeenCalledWith(42);
      expect(assignmentService.auditFor).toHaveBeenCalledWith(42);
      expect(component.selectedCharacter()?.characterName).toBe('Pilot Eins');
      expect(component.characterQuery()).toBe('');
      expect(component.loadingSelected()).toBe(false);
    });

    it('bleibt ohne Auswahl, wenn das Laden fehlschlägt', () => {
      assignmentService.rolesOf.mockReturnValue(throwError(() => serverError('Unbekannt.')));

      component.selectCharacter(42);

      expect(toastService.error).toHaveBeenCalledWith('Unbekannt.');
      expect(component.selectedCharacter()).toBeNull();
      expect(component.loadingSelected()).toBe(false);
    });

    it('kehrt beim Abwählen zum Verlauf der ganzen Corp zurück', () => {
      component.selectCharacter(42);
      component.changeReason.set('irgendwas');

      component.clearCharacter();

      expect(component.selectedCharacter()).toBeNull();
      expect(component.changeReason()).toBe('');
      // Der Verlauf eines abgewählten Charakters dürfte nicht stehenbleiben.
      expect(assignmentService.recentAudit).toHaveBeenCalledTimes(2);
    });

    it('trennt getragene von noch vergebbaren Rollen', () => {
      assignmentService.rolesOf.mockReturnValue(
        of(
          characterRoles([
            roleState({ roleName: 'ROLE_FC', held: true, revocable: true }),
            roleState({ roleName: 'ROLE_RECRUITER' }),
            // Eingebaute Rollen entstehen aus der Corp-Zugehörigkeit - sie von Hand
            // anzubieten wäre ein Knopf ohne Wirkung.
            roleState({ roleName: 'ROLE_USER', source: 'BUILT_IN', assignable: false }),
          ]),
        ),
      );

      component.selectCharacter(42);

      expect(component.heldRoles().map((state) => state.roleName)).toEqual(['ROLE_FC']);
      expect(component.availableRoles().map((state) => state.roleName)).toEqual([
        'ROLE_RECRUITER',
      ]);
    });
  });

  describe('Rolle zuweisen', () => {
    beforeEach(() => {
      component.ngOnInit();
      component.selectCharacter(42);
    });

    it('vergibt die Rolle samt Grund und lädt danach neu', () => {
      component.changeReason.set('  übernimmt die Rekrutierung  ');

      component.grantRole(roleState());

      expect(assignmentService.grant).toHaveBeenCalledWith(
        42,
        'ROLE_RECRUITER',
        'übernimmt die Rekrutierung',
      );
      expect(toastService.success).toHaveBeenCalledWith('Pilot Eins hat jetzt ROLE_RECRUITER.');
      expect(component.changeReason()).toBe('');
      // Neu holen statt vor Ort ändern: mit der Rolle wandert auch ihre Bewertung.
      expect(assignmentService.rolesOf).toHaveBeenCalledTimes(2);
      expect(component.pendingRole()).toBeNull();
    });

    it('rührt eine nicht vergebbare Rolle nicht an', () => {
      // Der Knopf ist gesperrt; ein zweiter Weg dorthin darf nichts anderes tun.
      component.grantRole(roleState({ assignable: false }));

      expect(assignmentService.grant).not.toHaveBeenCalled();
    });

    it('tut ohne gewählten Charakter nichts', () => {
      component.clearCharacter();

      component.grantRole(roleState());

      expect(assignmentService.grant).not.toHaveBeenCalled();
    });

    it('lässt keine zweite Änderung zu, solange eine läuft', () => {
      component.pendingRole.set('ROLE_FC');

      component.grantRole(roleState());

      expect(assignmentService.grant).not.toHaveBeenCalled();
    });

    it('gibt die Begründung des Servers weiter und behält den Grund', () => {
      assignmentService.grant.mockReturnValue(
        throwError(() => serverError('ROLE_A38 vergibt bereits der Ingame-Titel A38.')),
      );
      component.changeReason.set('Versuch');

      component.grantRole(roleState());

      expect(toastService.error).toHaveBeenCalledWith(
        'ROLE_A38 vergibt bereits der Ingame-Titel A38.',
      );
      expect(component.changeReason()).toBe('Versuch');
      expect(component.pendingRole()).toBeNull();
    });

    it('meldet auch einen Fehlschlag ohne Begründung', () => {
      assignmentService.grant.mockReturnValue(throwError(() => new Error('kaputt')));

      component.grantRole(roleState());

      expect(toastService.error).toHaveBeenCalledWith('Die Rolle konnte nicht vergeben werden.');
    });

    it('meldet, wenn der Charakter danach nicht neu zu laden ist', () => {
      assignmentService.rolesOf
        .mockReturnValueOnce(of(characterRoles([roleState()])))
        .mockReturnValue(throwError(() => new Error('kaputt')));
      component.selectCharacter(42);

      component.grantRole(roleState());

      expect(toastService.error).toHaveBeenCalledWith(
        'Die Rollen des Charakters konnten nicht geladen werden.',
      );
    });
  });

  describe('Rolle entziehen', () => {
    const held = roleState({ held: true, revocable: true });

    beforeEach(() => {
      assignmentService.rolesOf.mockReturnValue(of(characterRoles([held])));
      component.ngOnInit();
      component.selectCharacter(42);
    });

    it('fragt mit Charakter und Rolle im Klartext nach', async () => {
      // Die Zeilen sehen einander gleich - wer die falsche trifft, nimmt jemandem alles.
      await component.revokeRole(held);

      expect(confirmService.ask).toHaveBeenCalledWith(
        'Rolle entziehen?',
        expect.stringContaining('Pilot Eins verliert ROLE_RECRUITER'),
        'Entziehen',
      );
      expect(assignmentService.revoke).toHaveBeenCalledWith(42, 'ROLE_RECRUITER', '');
      expect(toastService.success).toHaveBeenCalledWith('Pilot Eins hat ROLE_RECRUITER nicht mehr.');
    });

    it('lässt einen Abbruch folgenlos', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.revokeRole(held);

      expect(assignmentService.revoke).not.toHaveBeenCalled();
    });

    it('fragt bei einer Titel-Rolle gar nicht erst nach', async () => {
      // Der nächste Abgleich trüge sie ohnehin wieder ein - der Server lehnt ab.
      await component.revokeRole(roleState({ held: true, revocable: false }));

      expect(confirmService.ask).not.toHaveBeenCalled();
      expect(assignmentService.revoke).not.toHaveBeenCalled();
    });

    it('tut ohne gewählten Charakter nichts', async () => {
      component.clearCharacter();

      await component.revokeRole(held);

      expect(confirmService.ask).not.toHaveBeenCalled();
    });

    it('lässt keine zweite Änderung zu, solange eine läuft', async () => {
      component.pendingRole.set('ROLE_FC');

      await component.revokeRole(held);

      expect(confirmService.ask).not.toHaveBeenCalled();
    });

    it('gibt die Begründung des Servers weiter', async () => {
      assignmentService.revoke.mockReturnValue(
        throwError(() => serverError('ROLE_A38 kommt aus dem Ingame-Titel A38.')),
      );

      await component.revokeRole(held);

      expect(toastService.error).toHaveBeenCalledWith('ROLE_A38 kommt aus dem Ingame-Titel A38.');
      expect(component.pendingRole()).toBeNull();
    });

    it('meldet auch einen Fehlschlag ohne Begründung', async () => {
      assignmentService.revoke.mockReturnValue(throwError(() => new Error('kaputt')));

      await component.revokeRole(held);

      expect(toastService.error).toHaveBeenCalledWith('Die Rolle konnte nicht entzogen werden.');
    });
  });

  describe('Nachweis', () => {
    it('zeigt ohne Auswahl den Verlauf der ganzen Corp', () => {
      component.ngOnInit();

      expect(assignmentService.recentAudit).toHaveBeenCalled();
      expect(component.auditHeading()).toBe('Letzte Rollenänderungen');
      expect(component.loadingAudit()).toBe(false);
    });

    it('wechselt mit der Auswahl auf den Verlauf des Charakters', () => {
      component.ngOnInit();

      component.selectCharacter(42);

      expect(component.audit()).toHaveLength(1);
      expect(component.auditHeading()).toBe('Änderungen an Pilot Eins');
    });

    it('meldet einen Fehlschlag, statt eine leere Liste zu zeigen', () => {
      assignmentService.recentAudit.mockReturnValue(throwError(() => new Error('kaputt')));

      component.ngOnInit();

      expect(toastService.error).toHaveBeenCalledWith(
        'Der Nachweis konnte nicht geladen werden.',
      );
      expect(component.loadingAudit()).toBe(false);
    });

    it('schreibt die Richtung lesbar aus', () => {
      expect(component.auditActionLabel(auditEntry())).toBe('vergeben');
      expect(component.auditActionLabel(auditEntry({ action: 'REVOKE' }))).toBe('entzogen');
    });
  });

  describe('Warnung am Knopf', () => {
    it('nennt den Titel, aus dem die Rolle zurückkehrt', () => {
      // Das gehört an den Knopf, nicht in eine Fussnote - sonst klickt jemand dreimal.
      expect(component.buttonWarning(roleState({ grantingTitles: ['A38', 'Rekrut'] }))).toBe(
        'Kommt aus dem Titel A38, Rekrut - kehrt beim nächsten Abgleich zurück.',
      );
    });

    it('warnt vor einer getragenen Rolle ohne Dauerhaft-Markierung', () => {
      // Sie verschwindet still; niemand fände später den Zusammenhang.
      expect(component.buttonWarning(roleState({ held: true, survivesSync: false }))).toBe(
        'Nicht dauerhaft markiert - der nächste Abgleich nimmt sie weg.',
      );
    });

    it('schweigt, wo nichts zu warnen ist', () => {
      expect(component.buttonWarning(roleState())).toBeNull();
      expect(component.buttonWarning(roleState({ held: true, survivesSync: true }))).toBeNull();
    });

    it('warnt nicht vor dem Verschwinden einer eingebauten Rolle', () => {
      // Der Abgleich rechnet sie jedes Mal neu aus und gibt sie zurück. Eine
      // Warnung, die nachweislich falsch ist, wird auch dort nicht geglaubt,
      // wo sie stimmt.
      expect(
        component.buttonWarning(
          roleState({ source: 'BUILT_IN', held: true, survivesSync: false }),
        ),
      ).toBeNull();
    });

    it('zeigt den Ladezustand nur an der Rolle, die gerade geändert wird', () => {
      component.pendingRole.set('ROLE_RECRUITER');

      expect(component.isPending(roleState())).toBe(true);
      expect(component.isPending(roleState({ roleName: 'ROLE_FC' }))).toBe(false);
    });
  });
});
