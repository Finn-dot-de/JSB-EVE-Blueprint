import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { CharacterService } from '../../services/character.service';
import {
  AuthRoleDto,
  AuthRoleSource,
  CorpTitleDto,
  GroupService,
} from '../../services/group.service';
import {
  CharacterRolesDto,
  RoleAssignmentService,
  RoleAuditDto,
  RoleStateDto,
} from '../../services/role-assignment.service';

/** Auswahl im Dialog, hinter der das Freitextfeld erscheint. */
const CUSTOM_ROLE_OPTION = '__custom__';

/** Auswahl im Dialog, die die Zuordnung löst. */
const NO_ROLE_OPTION = '';

/**
 * Ab wie vielen Zeichen die Charaktersuche antwortet.
 *
 * <p>Ohne Untergrenze stünde beim ersten Buchstaben die halbe Corp da - eine
 * Liste, in der niemand sucht, sondern nur scrollt. Zwei Zeichen sind der
 * Punkt, ab dem die Treffermenge wieder überschaubar wird.</p>
 */
const MIN_SEARCH_LENGTH = 2;

/** Wie viele Treffer die Suche zeigt - der Rest wird als Zahl gemeldet. */
const MAX_SEARCH_RESULTS = 8;

/** Eine Gruppe der Auswahlliste - die Rollen sind nach ihrer Herkunft sortiert. */
interface RoleGroup {
  readonly label: string;
  readonly roles: readonly AuthRoleDto[];
}

/**
 * Ein Charakter, wie ihn die Suche anbietet.
 *
 * <p>Mains und Alts liegen in derselben Liste: eine Rolle hängt am Charakter,
 * nicht am Account, und wer einen Alt in die Fleet-Kanäle lassen will, muss ihn
 * auch auswählen können. `mainName` bleibt daneben stehen, weil zwei Charaktere
 * gleichen Namens sonst nicht auseinanderzuhalten wären.</p>
 */
interface CharacterChoice {
  readonly id: number;
  readonly name: string;
  readonly portraitUrl: string;
  readonly corporationName: string;
  /** `null`, wenn dieser Charakter selbst der Main ist. */
  readonly mainName: string | null;
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
  private confirmService = inject(ConfirmService);
  private characterService = inject(CharacterService);
  private roleAssignmentService = inject(RoleAssignmentService);

  readonly customRoleOption = CUSTOM_ROLE_OPTION;
  readonly noRoleOption = NO_ROLE_OPTION;
  readonly minSearchLength = MIN_SEARCH_LENGTH;

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

  // ----- Rollen eines einzelnen Charakters -----

  /** Alle registrierten Charaktere, flach - die Vorlage der Suche. */
  private characterChoices = signal<CharacterChoice[]>([]);
  characterQuery = signal<string>('');
  loadingCharacters = signal<boolean>(true);

  /** Der Charakter, dessen Rollen gerade bearbeitet werden - `null` heisst: keiner gewählt. */
  selectedCharacter = signal<CharacterRolesDto | null>(null);
  loadingSelected = signal<boolean>(false);

  /**
   * Die Rolle, deren Zuweisung oder Entzug gerade läuft.
   *
   * <p>Ein Rollenname statt eines Ja/Nein: sperrt wäre es ein Kennzeichen,
   * lägen alle Knöpfe der Liste gleichzeitig still, und der Nutzer sähe nicht,
   * welcher davon gerade arbeitet.</p>
   */
  pendingRole = signal<string | null>(null);

  /** Der freiwillige Grund, der mit der nächsten Änderung in den Nachweis geht. */
  changeReason = signal<string>('');

  audit = signal<RoleAuditDto[]>([]);
  loadingAudit = signal<boolean>(true);

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

  /** Die Treffer der Charaktersuche, vollständig - die Anzeige kürzt danach. */
  private matchingCharacters = computed<CharacterChoice[]>(() => {
    const query = this.characterQuery().trim().toLowerCase();
    if (query.length < MIN_SEARCH_LENGTH) {
      return [];
    }
    return this.characterChoices().filter((choice) =>
      choice.name.toLowerCase().includes(query),
    );
  });

  characterMatches = computed(() => this.matchingCharacters().slice(0, MAX_SEARCH_RESULTS));

  /** Wie viele Treffer nicht mehr angezeigt werden - sonst wähnte sich der Nutzer am Ende. */
  hiddenMatches = computed(() =>
    Math.max(0, this.matchingCharacters().length - MAX_SEARCH_RESULTS),
  );

  /** Die getragenen Rollen. Der Server sortiert sie bereits nach vorn. */
  heldRoles = computed(() => this.selectedCharacter()?.roles.filter((role) => role.held) ?? []);

  /** Alles, was der Charakter haben könnte - eingebaute Rollen bleiben draussen. */
  availableRoles = computed(
    () =>
      this.selectedCharacter()?.roles.filter((role) => !role.held && role.source !== 'BUILT_IN') ??
      [],
  );

  /**
   * Die Überschrift des Nachweises.
   *
   * <p>Dieselbe Liste zeigt zwei Dinge - den Verlauf eines Charakters oder den
   * der ganzen Corp. Ohne Überschrift wäre nach dem Abwählen nicht zu erkennen,
   * dass sich der Inhalt gerade geändert hat.</p>
   */
  auditHeading = computed(() => {
    const character = this.selectedCharacter();
    return character ? `Änderungen an ${character.characterName}` : 'Letzte Rollenänderungen';
  });

  ngOnInit() {
    this.loadTitles();
    this.loadRoles();
    this.loadCharacters();
    this.loadAudit();
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

  /**
   * Alle registrierten Charaktere für die Suche.
   *
   * <p>Der Account-Endpunkt hängt am selben Rollenkreis wie diese Seite - wer
   * hier Rollen vergeben darf, darf die Liste auch sehen. Mains und Alts werden
   * flach gelegt, weil eine Rolle am Charakter hängt und nicht am Account.</p>
   */
  private loadCharacters() {
    this.loadingCharacters.set(true);
    this.characterService.getAllAccounts().subscribe({
      next: (accounts) => {
        this.characterChoices.set(
          accounts.flatMap((account) => [
            {
              id: account.mainId,
              name: account.mainName,
              portraitUrl: account.portraitUrl,
              corporationName: account.corporationName,
              mainName: null,
            },
            ...account.alts.map((alt) => ({
              id: alt.id,
              name: alt.name,
              portraitUrl: alt.portraitUrl,
              corporationName: alt.corporationName,
              mainName: account.mainName,
            })),
          ]),
        );
        this.loadingCharacters.set(false);
      },
      error: (error: unknown) => {
        this.toastService.error(
          this.messageOf(error, 'Die Charaktere konnten nicht geladen werden.'),
        );
        this.loadingCharacters.set(false);
      },
    });
  }

  /**
   * Der Nachweis: der Verlauf des gewählten Charakters, sonst der der ganzen Corp.
   *
   * <p>Er wird mit angezeigt und nicht weggeklappt. Er ist der Grund, warum ein
   * Knopf, der Rechte verteilt, überhaupt vertretbar ist - wer ihn erst suchen
   * muss, liest ihn nie.</p>
   */
  private loadAudit() {
    const character = this.selectedCharacter();
    this.loadingAudit.set(true);
    const request = character
      ? this.roleAssignmentService.auditFor(character.characterId)
      : this.roleAssignmentService.recentAudit();

    request.subscribe({
      next: (entries) => {
        this.audit.set(entries);
        this.loadingAudit.set(false);
      },
      error: (error: unknown) => {
        this.toastService.error(this.messageOf(error, 'Der Nachweis konnte nicht geladen werden.'));
        this.loadingAudit.set(false);
      },
    });
  }

  // ===========================================================
  // Rollen eines Charakters
  // ===========================================================

  /** Holt die Rollen samt Bewertung und zeigt sie an. */
  selectCharacter(characterId: number) {
    this.loadingSelected.set(true);
    this.characterQuery.set('');
    this.roleAssignmentService.rolesOf(characterId).subscribe({
      next: (character) => {
        this.selectedCharacter.set(character);
        this.loadingSelected.set(false);
        this.loadAudit();
      },
      error: (error: unknown) => {
        this.toastService.error(
          this.messageOf(error, 'Die Rollen des Charakters konnten nicht geladen werden.'),
        );
        this.loadingSelected.set(false);
      },
    });
  }

  clearCharacter() {
    this.selectedCharacter.set(null);
    this.changeReason.set('');
    // Zurück auf den Verlauf der ganzen Corp - sonst bliebe der eines
    // Charakters stehen, der gar nicht mehr angezeigt wird.
    this.loadAudit();
  }

  /**
   * Gibt dem Charakter die Rolle.
   *
   * <p>Der Dienst markiert die Rolle dabei als dauerhaft, sonst nähme der
   * Rollen-Abgleich sie in spätestens zehn Minuten wieder weg. Was die
   * Zuweisung nach sich zieht, steht als `note` an jeder Zeile - der Knopf ist
   * gar nicht erst bedienbar, wo der Dienst ablehnen würde.</p>
   */
  grantRole(role: RoleStateDto) {
    const character = this.selectedCharacter();
    if (!character || !role.assignable || this.pendingRole()) {
      return;
    }

    this.pendingRole.set(role.roleName);
    this.roleAssignmentService
      .grant(character.characterId, role.roleName, this.changeReason().trim())
      .subscribe({
        next: () => {
          this.pendingRole.set(null);
          this.changeReason.set('');
          this.toastService.success(`${character.characterName} hat jetzt ${role.roleName}.`);
          this.refreshSelected(character.characterId);
        },
        error: (error: unknown) => {
          this.pendingRole.set(null);
          this.toastService.error(this.messageOf(error, 'Die Rolle konnte nicht vergeben werden.'));
        },
      });
  }

  /**
   * Nimmt dem Charakter die Rolle wieder ab - nach Rückfrage.
   *
   * <p>Die Rückfrage nennt Charakter UND Rolle beim Namen. Die Zeilen der Liste
   * sehen einander gleich; wer die falsche trifft, nimmt jemandem den Zugang zu
   * allem, was an der Rolle hängt, und merkt es erst, wenn der sich meldet.</p>
   */
  async revokeRole(role: RoleStateDto) {
    const character = this.selectedCharacter();
    if (!character || !role.revocable || this.pendingRole()) {
      return;
    }

    const confirmed = await this.confirmService.ask(
      'Rolle entziehen?',
      `${character.characterName} verliert ${role.roleName} und damit den Zugang zu allem, was an dieser Rolle hängt - auch zu den Discord-Kanälen.`,
      'Entziehen',
    );
    if (!confirmed) {
      return;
    }

    this.pendingRole.set(role.roleName);
    this.roleAssignmentService
      .revoke(character.characterId, role.roleName, this.changeReason().trim())
      .subscribe({
        next: () => {
          this.pendingRole.set(null);
          this.changeReason.set('');
          this.toastService.success(`${character.characterName} hat ${role.roleName} nicht mehr.`);
          this.refreshSelected(character.characterId);
        },
        error: (error: unknown) => {
          this.pendingRole.set(null);
          this.toastService.error(this.messageOf(error, 'Die Rolle konnte nicht entzogen werden.'));
        },
      });
  }

  /**
   * Lädt Rollen und Nachweis des Charakters neu.
   *
   * <p>Neu holen statt die Liste vor Ort zu ändern: mit der Rolle wandert auch
   * ihre Bewertung - eine gerade vergebene Rolle ist danach dauerhaft markiert
   * und entziehbar. Das nachzubilden hiesse, die Regeln des Dienstes ein zweites
   * Mal zu schreiben.</p>
   */
  private refreshSelected(characterId: number) {
    this.roleAssignmentService.rolesOf(characterId).subscribe({
      next: (character) => {
        this.selectedCharacter.set(character);
        this.loadAudit();
      },
      error: (error: unknown) => {
        this.toastService.error(
          this.messageOf(error, 'Die Rollen des Charakters konnten nicht geladen werden.'),
        );
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

  /**
   * Die Warnung, die neben dem Knopf steht - nicht in einer Fussnote.
   *
   * <p>Zwei Fälle, und beide kosten sonst zehn Minuten Ratlosigkeit: eine Rolle
   * aus einem Ingame-Titel ist gar nicht zu entziehen, weil der Abgleich sie
   * wieder einträgt. Und eine getragene Rolle ohne Dauerhaft-Markierung
   * verschwindet von selbst - der Admin sieht sie gesetzt und findet später
   * keinen Zusammenhang mehr.</p>
   *
   * <p>Der ausführliche Text kommt als `note` vom Server und steht in derselben
   * Zeile. Hier steht die Kurzfassung dort, wo der Zeigefinger schon ist.</p>
   *
   * @returns `null`, wenn nichts zu warnen ist
   */
  buttonWarning(role: RoleStateDto): string | null {
    if (role.grantingTitles.length > 0) {
      return `Kommt aus dem Titel ${role.grantingTitles.join(', ')} - kehrt beim nächsten Abgleich zurück.`;
    }
    // Eingebaute Rollen sind nie als dauerhaft markiert und müssen es auch
    // nicht sein: der Abgleich rechnet sie aus der Corp-Zugehörigkeit jedes Mal
    // neu aus und gibt sie zurück. Die Warnung "verschwindet" wäre hier schlicht
    // falsch - und wo sie falsch steht, wird sie auch dort nicht mehr geglaubt,
    // wo sie stimmt.
    if (role.held && !role.survivesSync && role.source !== 'BUILT_IN') {
      return 'Nicht dauerhaft markiert - der nächste Abgleich nimmt sie weg.';
    }
    return null;
  }

  /** Ob gerade diese Rolle geändert wird - nur ihr Knopf zeigt den Ladezustand. */
  isPending(role: RoleStateDto): boolean {
    return this.pendingRole() === role.roleName;
  }

  auditActionLabel(entry: RoleAuditDto): string {
    return entry.action === 'GRANT' ? 'vergeben' : 'entzogen';
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
