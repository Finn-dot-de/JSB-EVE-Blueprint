import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ToastService } from '../../services/toast.service';
import {
  AuthRoleDto,
  AuthRoleSource,
  CorpTitleDto,
  GroupService,
} from '../../services/group.service';

/** Auswahl im Dialog, hinter der das Freitextfeld erscheint. */
const CUSTOM_ROLE_OPTION = '__custom__';

/** Auswahl im Dialog, die die Zuordnung löst. */
const NO_ROLE_OPTION = '';

/** Eine Gruppe der Auswahlliste - die Rollen sind nach ihrer Herkunft sortiert. */
interface RoleGroup {
  readonly label: string;
  readonly roles: readonly AuthRoleDto[];
}

const GROUP_LABELS: Readonly<Record<AuthRoleSource, string>> = {
  BUILT_IN: 'Eingebaute Rollen',
  CUSTOM: 'Eigene Rollen',
  TITLE: 'Aus Ingame-Titeln entstanden',
};

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './roles.component.html',
  styleUrls: ['./roles.component.scss'],
})
export class RolesComponent implements OnInit {
  private groupService = inject(GroupService);
  private toastService = inject(ToastService);

  readonly customRoleOption = CUSTOM_ROLE_OPTION;
  readonly noRoleOption = NO_ROLE_OPTION;

  titles = signal<CorpTitleDto[]>([]);
  roles = signal<AuthRoleDto[]>([]);
  loadingTitles = signal<boolean>(true);
  loadingRoles = signal<boolean>(true);
  saving = signal<boolean>(false);

  /** Der Titel, dessen Zuordnung gerade bearbeitet wird - `null` heisst: kein Dialog offen. */
  editingTitle = signal<CorpTitleDto | null>(null);
  selectedRole = signal<string>(NO_ROLE_OPTION);
  customRoleName = signal<string>('');

  /** Die Rolle, deren Löschung noch bestätigt werden muss. */
  pendingDelete = signal<string | null>(null);

  newRole = { name: '', description: '', special: false };

  loading = computed(() => this.loadingTitles() || this.loadingRoles());

  /** Die Auswahlliste des Dialogs, nach Herkunft gruppiert. */
  roleGroups = computed<RoleGroup[]>(() => {
    const roles = this.roles();
    return (Object.keys(GROUP_LABELS) as AuthRoleSource[])
      .map((source) => ({
        label: GROUP_LABELS[source],
        roles: roles.filter((role) => role.source === source),
      }))
      .filter((group) => group.roles.length > 0);
  });

  ngOnInit() {
    this.loadTitles();
    this.loadRoles();
  }

  // ===========================================================
  // Laden
  // ===========================================================

  private loadTitles() {
    this.loadingTitles.set(true);
    this.groupService.getCorporationTitles().subscribe({
      next: (titles) => {
        this.titles.set(titles);
        this.loadingTitles.set(false);
      },
      error: (error: unknown) => {
        this.toastService.error(
          this.messageOf(error, 'Die Corp-Titel konnten nicht geladen werden.'),
        );
        this.loadingTitles.set(false);
      },
    });
  }

  private loadRoles() {
    this.loadingRoles.set(true);
    this.groupService.getRoles().subscribe({
      next: (roles) => {
        this.roles.set(roles);
        this.loadingRoles.set(false);
      },
      error: (error: unknown) => {
        this.toastService.error(this.messageOf(error, 'Die Rollen konnten nicht geladen werden.'));
        this.loadingRoles.set(false);
      },
    });
  }

  // ===========================================================
  // Zuordnung eines Titels
  // ===========================================================

  openMapping(title: CorpTitleDto) {
    this.editingTitle.set(title);
    this.selectedRole.set(title.mappedRole ?? NO_ROLE_OPTION);
    this.customRoleName.set('');
  }

  closeMapping() {
    this.editingTitle.set(null);
    this.customRoleName.set('');
  }

  /** Der Rollenname, der gespeichert würde - leer bedeutet: der Titel vergibt nichts mehr. */
  private chosenRoleName(): string {
    return this.selectedRole() === CUSTOM_ROLE_OPTION
      ? this.customRoleName().trim()
      : this.selectedRole();
  }

  /** Ob die Eingabe im Dialog gespeichert werden kann. */
  canSaveMapping = computed(() => {
    if (this.saving()) {
      return false;
    }
    // Freitext ohne Eingabe wäre gleichbedeutend mit "keine Rolle" - das ist
    // aber schon ein eigener Eintrag der Liste und hier fast sicher ein Versehen.
    return this.selectedRole() !== CUSTOM_ROLE_OPTION || this.customRoleName().trim().length > 0;
  });

  saveMapping() {
    const title = this.editingTitle();
    if (!title || !this.canSaveMapping()) {
      return;
    }

    const roleName = this.chosenRoleName();
    this.saving.set(true);
    this.groupService.saveTitleMapping(title.titleId, roleName).subscribe({
      next: () => {
        this.saving.set(false);
        this.toastService.success(
          roleName
            ? `${title.name} vergibt jetzt ${roleName}.`
            : `${title.name} vergibt keine Rolle mehr.`,
        );
        this.closeMapping();
        // Beide Listen: ein frei eingetippter Name ist danach eine neue Rolle.
        this.loadTitles();
        this.loadRoles();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.toastService.error(this.messageOf(error, 'Die Zuordnung konnte nicht gespeichert werden.'));
      },
    });
  }

  // ===========================================================
  // Verwaltung der Rollen
  // ===========================================================

  canCreateRole = computed(() => !this.saving());

  createRole() {
    if (!this.newRole.name.trim()) {
      this.toastService.error('Gib der Rolle einen Namen.');
      return;
    }

    this.saving.set(true);
    this.groupService.saveRole({ ...this.newRole, name: this.newRole.name.trim() }).subscribe({
      next: (saved) => {
        this.saving.set(false);
        // Der Server normalisiert den Namen - er wird gemeldet, damit niemand
        // nach einer Rolle sucht, die unter anderer Schreibweise gespeichert ist.
        this.toastService.success(`${saved.name} gespeichert.`);
        this.newRole = { name: '', description: '', special: false };
        this.loadRoles();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.toastService.error(this.messageOf(error, 'Die Rolle konnte nicht gespeichert werden.'));
      },
    });
  }

  askDelete(roleName: string) {
    this.pendingDelete.set(roleName);
  }

  cancelDelete() {
    this.pendingDelete.set(null);
  }

  confirmDelete() {
    const roleName = this.pendingDelete();
    if (!roleName) {
      return;
    }

    this.saving.set(true);
    this.groupService.deleteRole(roleName).subscribe({
      next: () => {
        this.saving.set(false);
        this.pendingDelete.set(null);
        this.toastService.success(`${roleName} gelöscht.`);
        this.loadRoles();
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.pendingDelete.set(null);
        // Der Server nennt den Grund, etwa welcher Titel die Rolle noch vergibt.
        this.toastService.error(this.messageOf(error, 'Die Rolle konnte nicht gelöscht werden.'));
      },
    });
  }

  // ===========================================================
  // Darstellung
  // ===========================================================

  /** Gibt dem Icon je nach zugeordneter Rolle eine passende Farbe. */
  getRoleIconClass(role: string | null): string {
    if (!role) return 'text-secondary'; // Grau für nicht zugeordnet
    if (role.includes('ADMIN') || role.includes('DIRECTOR')) return 'text-warning'; // Orange/Gold
    if (role.includes('FC') || role.includes('FLEET')) return 'text-primary'; // Blau
    if (role.includes('INDUSTRY')) return 'text-success'; // Grün
    return 'text-danger'; // Rot als Standard für andere zugeordnete
  }

  sourceLabel(source: AuthRoleSource): string {
    return GROUP_LABELS[source];
  }

  /** Nur selbst angelegte Rollen lassen sich wieder entfernen. */
  isDeletable(role: AuthRoleDto): boolean {
    return role.source === 'CUSTOM';
  }

  /**
   * Die Begründung des Servers, sonst der Ersatztext.
   *
   * Gerade beim Löschen steht dort das Entscheidende - etwa welcher Titel die
   * Rolle noch vergibt. Ein pauschales "hat nicht geklappt" liesse den Nutzer
   * ratlos vor einer Schaltfläche zurück, die scheinbar grundlos nichts tut.
   */
  private messageOf(error: unknown, fallback: string): string {
    const body = (error as HttpErrorResponse | null)?.error as { message?: string } | null;
    const message = typeof body?.message === 'string' ? body.message.trim() : '';
    return message || fallback;
  }
}
