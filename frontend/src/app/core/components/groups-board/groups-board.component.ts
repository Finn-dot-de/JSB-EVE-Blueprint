import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AuthGroupService,
  GroupDecision,
  GroupDto,
  GroupRequestDto,
  SaveGroupDto,
} from '../../services/auth-group.service';
import { AuthRoleDto, GroupService } from '../../services/group.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { ToastService } from '../../services/toast.service';
import { handlePortraitError } from '../../shared/eve-image.util';

type Tab = 'GROUPS' | 'MANAGE';

/**
 * Was in einer Tabellenzeile möglich ist - genau ein Zustand je Gruppe.
 *
 * <p>Der Zustand steht bewusst als eigener Begriff da und wird nicht an drei
 * Stellen aus `isMember` und `hasPendingRequest` neu zusammengesetzt: der Knopf,
 * die farbige Hervorhebung der Zeile und der Test müssen dieselbe Antwort
 * bekommen, sonst leuchtet eine Zeile grün, deren Knopf "Beitreten" sagt.</p>
 */
export type GroupRowState = 'MEMBER' | 'PENDING' | 'OPEN';

/**
 * Die Rollen, die überall entscheiden dürfen - wortgleich zu
 * `AccessRules.LEADERSHIP_OR_IT` im Backend.
 *
 * <p>Die Liste blendet hier nur aus; abgelehnt wird eine Entscheidung ohnehin
 * erst im Backend. Laufen die beiden Stellen auseinander, sieht ein Nutzer
 * höchstens einen Reiter zu viel oder zu wenig - er kann dadurch nichts.</p>
 */
const ADMIN_ROLES = ['ROLE_DIRECTOR', 'ROLE_CEO', 'ROLE_IT_ADMIN'];

/**
 * Die eingebauten Rollen, die als Leitung nicht taugen - dieselbe Liste wie
 * `SystemRoles.builtIn()` im Backend.
 *
 * <p>Zwei Gründe, ein Verbot: `ROLE_USER`, `ROLE_MEMBER`, `ROLE_GUEST` und
 * `ROLE_MARAUDERS_ASSOCIATED` trägt praktisch jeder Angemeldete - als
 * Leitungsrolle eingetragen dürfte damit jeder über jede Anfrage entscheiden,
 * auch über die seiner Freunde. Die drei Führungsrollen wiederum entscheiden
 * über jede Gruppe ohnehin schon.</p>
 *
 * <p>Der Server weist alle sieben ab. Standen bisher nur die ersten drei hier,
 * bot die Liste `ROLE_CEO` und `ROLE_DIRECTOR` zur Wahl an und das Speichern
 * lief in eine Fehlermeldung für etwas, das die Oberfläche selbst angeboten
 * hatte.</p>
 */
const BUILT_IN_ROLES = [
  'ROLE_USER',
  'ROLE_MEMBER',
  'ROLE_MARAUDERS_ASSOCIATED',
  'ROLE_GUEST',
  'ROLE_CEO',
  'ROLE_DIRECTOR',
  'ROLE_IT_ADMIN',
];

/** Was in der Spalte "Leitung" steht, solange keine Rolle hinterlegt ist. */
const LEADERLESS_LABEL = 'Ohne Leitung';

/**
 * Der Rollenname, den der Server aus einer Eingabe machen wird - Schritt für
 * Schritt wie `SystemRoles.normalize` im Backend.
 *
 * <p>Der Vorschlag im Formular muss zeichengenau das ergeben, was am Ende
 * gespeichert wird. Stünde im Feld "ROLE_Wurmloch SIG" und in der Tabelle
 * danach `ROLE_WURMLOCH_SIG`, wüsste der Admin nicht, welche der beiden Rollen
 * er im Rollenkatalog suchen soll.</p>
 *
 * <p>Leer, wenn nach dem Säubern nichts übrig bleibt - dort wirft das Backend;
 * hier bleibt der Hinweis dann einfach weg, statt einen Namen zu behaupten.</p>
 */
export function normalizeRoleName(raw: string): string {
  const upperCase = raw.trim().toUpperCase();
  const withoutPrefix = upperCase.startsWith('ROLE_') ? upperCase.slice('ROLE_'.length) : upperCase;
  const cleaned = withoutPrefix.replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return cleaned ? `ROLE_${cleaned}` : '';
}

@Component({
  selector: 'app-groups-board',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './groups-board.component.html',
  styleUrls: ['./groups-board.component.scss'],
})
export class GroupsBoardComponent implements OnInit {
  private groupsService = inject(AuthGroupService);
  private groupService = inject(GroupService);
  private authService = inject(AuthService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  /** Für das Template sichtbar gemacht - fehlende Portraits fallen sonst als gebrochenes Bild auf. */
  protected readonly onPortraitError = handlePortraitError;

  activeTab = signal<Tab>('GROUPS');
  groups = signal<GroupDto[]>([]);
  requests = signal<GroupRequestDto[]>([]);
  loading = signal(false);
  loadingRequests = signal(false);
  saving = signal(false);
  editingGroup = signal<SaveGroupDto | null>(null);

  /**
   * Ob der Rollenname von Hand angefasst wurde.
   *
   * <p>Solange nicht, schreibt jeder Tastendruck im Namensfeld den Vorschlag
   * fort. Danach nie wieder: eine getippte Eingabe darf nicht unter der Hand
   * verschwinden, weil jemand noch ein Wort am Gruppennamen ändert.</p>
   */
  private roleNameTouched = signal(false);

  /** Auswahlwerte des Modals - erst beim Öffnen geholt, siehe {@link loadModalOptions}. */
  roles = signal<AuthRoleDto[]>([]);
  private modalOptionsLoaded = signal(false);

  /**
   * Was in der Kästchenliste "Wer entscheidet über Anfragen" zur Wahl steht.
   *
   * <p>Derselbe Katalog wie für die Gruppenrolle, nur ohne die eingebauten
   * Rollen - siehe {@link BUILT_IN_ROLES}.</p>
   */
  leaderRoles = computed(() => this.roles().filter((role) => !BUILT_IN_ROLES.includes(role.name)));

  /**
   * Die Kästchen der Mehrfachauswahl.
   *
   * <p>Der Katalog aus `GET /api/groups/roles` deckt die üblichen Leitungsrollen
   * ab: `RoleCatalogService.catalog()` legt system_roles, die eingebauten Rollen
   * und die Zuordnungen aus `title_role_mappings` übereinander, und dort stehen
   * FC und Recruiter. Er ist trotzdem keine Garantie - fällt die Titel-Zuordnung
   * weg oder kam die Rolle auf einem anderen Weg in die Gruppe, ist sie
   * eingetragen, aber nicht im Katalog. Das Backend prüft die Leitungsrollen
   * seinerseits nicht gegen den Katalog, nimmt sie also weiter an. Ohne Kästchen
   * fiele so eine Rolle beim nächsten Speichern still heraus und die Gruppe
   * verlöre ihre Leitung, ohne dass jemand etwas abgewählt hätte. Deshalb stehen
   * die bereits eingetragenen Rollen hinten mit in der Liste.</p>
   */
  leaderRoleChoices = computed(() => {
    const catalog = this.leaderRoles().map((role) => role.name);
    const selected = this.editingGroup()?.leaderRoleNames ?? [];
    return [...catalog, ...selected.filter((name) => !catalog.includes(name))];
  });

  /**
   * Der Rollenname, mit dem der Server rechnen wird.
   *
   * <p>Der Rückfall auf den Gruppennamen steht wortgleich in
   * `AuthGroupService.saveGroup`: ein leeres Feld heißt nicht "keine Rolle",
   * sondern "leite sie ab". Damit stimmt der Hinweis unter den Feldern auch
   * dann, wenn jemand das Rollenfeld leert.</p>
   */
  effectiveRoleName = computed(() => {
    const group = this.editingGroup();
    if (!group) return '';
    return normalizeRoleName(group.roleName.trim() ? group.roleName : group.name);
  });

  /**
   * Ob beim Speichern eine Rolle entsteht, statt eine vorhandene zu benutzen.
   *
   * <p>Trägt den einen Satz im Modal: hier wird nicht nur ausgewählt, hier legt
   * das Speichern etwas an, das danach im Rollenkatalog steht und über den
   * Discord-Abgleich weiterwandert.</p>
   */
  createsRole = computed(() => {
    const roleName = this.effectiveRoleName();
    return roleName !== '' && !this.roles().some((role) => role.name === roleName);
  });

  /**
   * Ob der Nutzer zum Kreis der globalen Verwalter gehört.
   *
   * <p>Als Getter und nicht als Feld, weil `/api/auth/me` beim Aufbau der Seite
   * noch unterwegs sein kann: ein einmal berechneter Wert bliebe dann für immer
   * `false`. Der Zugriff läuft über das Signal `currentUser`, `canManage` hängt
   * also weiterhin an der Reaktivität.</p>
   */
  get isAdmin(): boolean {
    return this.authService.hasAnyRole(ADMIN_ROLES);
  }

  /**
   * Ob der Reiter "Verwaltung" überhaupt erscheint.
   *
   * <p>`isLeader` kommt weiterhin vom Server: welche Rollen der Betrachter
   * wirklich trägt, weiß nur er. Seit die Leitung eine Rolle ist, hieße das
   * Flag "trägt eine der Leitungsrollen dieser Gruppe" - nachrechnen liesse es sich
   * im Browser trotzdem nicht zuverlässig, weil Rollen auch aus Ingame-Titeln
   * stammen.</p>
   */
  canManage = computed(() => this.isAdmin || this.groups().some((group) => group.isLeader));

  /** Für eine Zahl am Reiter - offene Anfragen sind die einzigen, die geliefert werden. */
  pendingCount = computed(() => this.requests().length);

  ngOnInit() {
    this.load();
  }

  /**
   * Holt die Gruppen und - falls der Nutzer entscheiden darf - die Anfragen.
   *
   * <p>Die Anfragen hängen an den Gruppen: ob jemand eine Leitungsrolle trägt,
   * steht erst fest, wenn die Liste da ist. Deshalb die Verkettung statt zweier
   * unabhängiger Aufrufe.</p>
   */
  load() {
    this.loading.set(true);
    this.groupsService.getGroups().subscribe({
      next: (groups) => {
        this.groups.set(groups);
        this.loading.set(false);
        if (this.canManage()) this.loadRequests();
        else this.requests.set([]);
      },
      error: (err) => {
        this.loading.set(false);
        this.toastService.error(err.error?.message || 'Gruppen konnten nicht geladen werden.');
      },
    });
  }

  loadRequests() {
    this.loadingRequests.set(true);
    this.groupsService.getOpenRequests().subscribe({
      next: (requests) => {
        this.requests.set(requests);
        this.loadingRequests.set(false);
      },
      error: (err) => {
        this.loadingRequests.set(false);
        this.toastService.error(err.error?.message || 'Anfragen konnten nicht geladen werden.');
      },
    });
  }

  setTab(tab: Tab) {
    this.activeTab.set(tab);
    if (tab === 'MANAGE' && this.canManage() && !this.loadingRequests()) this.loadRequests();
  }

  // ================= Beitreten und Austreten =================

  /**
   * Der Zustand einer Zeile - Grundlage für den einen Knopf rechts und für die
   * farbige Hervorhebung eigener Mitgliedschaften.
   *
   * <p>Mitgliedschaft schlägt eine offene Anfrage: wer schon drin ist, dem
   * hilft "Anfrage ausstehend" nicht weiter. Der Fall entsteht, wenn jemand
   * über einen anderen Weg die Rolle bekommen hat, während sein Antrag lief.</p>
   */
  rowState(group: GroupDto): GroupRowState {
    if (group.isMember) return 'MEMBER';
    if (group.hasPendingRequest) return 'PENDING';
    return 'OPEN';
  }

  /**
   * Stellt die Beitrittsanfrage.
   *
   * <p>Die Zeile wird sofort umgestellt, statt die ganze Liste neu zu holen:
   * die Antwort trägt die Anfrage bereits, ein zweiter Aufruf brächte nichts
   * Neues. Bei einem Fehler bleibt die Zeile unverändert.</p>
   */
  apply(group: GroupDto) {
    if (this.rowState(group) !== 'OPEN') return;

    this.groupsService.applyForGroup(group.id).subscribe({
      next: () => {
        this.groups.update((list) =>
          list.map((entry) =>
            entry.id === group.id ? { ...entry, hasPendingRequest: true } : entry,
          ),
        );
        this.toastService.success('Anfrage gestellt.');
        // Wer eine Leitungsrolle traegt, darf ueber die eigene Gruppe entscheiden -
        // die neue Anfrage gehoert dann sofort in seine Liste.
        if (this.canManage()) this.loadRequests();
      },
      error: (err) => this.toastService.error(err.error?.message || 'Anfrage fehlgeschlagen.'),
    });
  }

  /**
   * Tritt aus der Gruppe aus.
   *
   * <p>Der Server fragt niemanden - wer raus will, ist raus. Die Rückfrage
   * hier ist deshalb die einzige Bremse vor einem Verlust, den nur ein neuer
   * Antrag rückgängig macht.</p>
   *
   * <p>Danach wird die ganze Liste neu geholt und nicht bloß die Zeile
   * umgeschrieben: mit der Rolle fällt auch die Mitgliederzahl, und wer die
   * letzte Leitungsrolle dieser Gruppe verliert, verliert den Verwaltungs-Reiter
   * gleich mit.</p>
   */
  async leave(group: GroupDto) {
    if (!group.isMember) return;

    const confirmed = await this.confirmService.ask(
      'Gruppe verlassen?',
      `Die Rolle ${group.roleName} wird deinem Charakter abgenommen. Zurück geht es nur über einen neuen Antrag an "${group.name}".`,
      'Verlassen',
    );
    if (!confirmed) return;

    this.groupsService.leaveGroup(group.id).subscribe({
      next: () => {
        this.toastService.success('Gruppe verlassen.');
        this.load();
      },
      error: (err) => this.toastService.error(err.error?.message || 'Austritt fehlgeschlagen.'),
    });
  }

  // ================= Verwaltung =================

  approve(request: GroupRequestDto) {
    this.decide(request, 'approve', 'Anfrage angenommen.');
  }

  async reject(request: GroupRequestDto) {
    const confirmed = await this.confirmService.ask(
      'Wirklich ablehnen?',
      `Die Anfrage von ${request.characterName} für "${request.groupName}" wird abgelehnt.`,
      'Ablehnen',
    );
    if (!confirmed) return;

    this.decide(request, 'reject', 'Anfrage abgelehnt.');
  }

  /**
   * Die entschiedene Anfrage verschwindet aus der Liste - sie ist nicht mehr
   * offen. Die Gruppen kommen neu, weil sich bei einer Annahme die
   * Mitgliederzahl ändert (und die eigene Mitgliedschaft, wenn jemand über
   * seine eigene Anfrage entschieden hat).
   */
  private decide(request: GroupRequestDto, decision: GroupDecision, message: string) {
    this.groupsService.decideRequest(request.requestId, decision).subscribe({
      next: () => {
        this.requests.update((list) =>
          list.filter((entry) => entry.requestId !== request.requestId),
        );
        this.toastService.success(message);
        if (decision === 'approve') this.load();
      },
      error: (err) => this.toastService.error(err.error?.message || 'Entscheidung fehlgeschlagen.'),
    });
  }

  // ================= Pflege der Gruppen =================

  newGroup() {
    this.loadModalOptions();
    this.roleNameTouched.set(false);
    this.editingGroup.set({
      id: null,
      name: '',
      description: '',
      roleName: '',
      leaderRoleNames: [],
    });
  }

  editGroup(group: GroupDto) {
    this.loadModalOptions();
    // Beim Bearbeiten schlägt nichts mehr vor: die Rolle ist vergeben. Ein
    // Vorschlag, der beim Feilen am Gruppennamen mitliefe, benennte sie um -
    // und ihre Träger wären ihre Mitgliedschaft los, ohne ausgetreten zu sein.
    this.roleNameTouched.set(true);
    this.editingGroup.set({
      id: group.id,
      name: group.name,
      description: group.description,
      roleName: group.roleName,
      // Eine Kopie: die Auswahl im Modal darf die Tabelle dahinter nicht
      // schon vor dem Speichern umschreiben.
      leaderRoleNames: [...group.leaderRoleNames],
    });
  }

  closeModal() {
    this.editingGroup.set(null);
  }

  updateGroup(patch: Partial<SaveGroupDto>) {
    const group = this.editingGroup();
    if (group) this.editingGroup.set({ ...group, ...patch });
  }

  /**
   * Der Gruppenname - und, solange niemand das Rollenfeld angefasst hat, gleich
   * der Vorschlag dazu.
   *
   * <p>Aus "Wurmloch SIG" wird `ROLE_WURMLOCH_SIG`, ohne dass jemand die
   * Schreibregel kennen muss. Beides in einem Zug, damit der Vorschlag beim
   * Tippen mitläuft statt erst beim Verlassen des Feldes zu erscheinen.</p>
   */
  updateGroupName(value: string) {
    const patch: Partial<SaveGroupDto> = { name: value };
    if (!this.roleNameTouched()) patch.roleName = normalizeRoleName(value);
    this.updateGroup(patch);
  }

  /** Ab hier gilt die Eingabe und nicht mehr der Vorschlag - siehe {@link roleNameTouched}. */
  updateGroupRoleName(value: string) {
    this.roleNameTouched.set(true);
    this.updateGroup({ roleName: value });
  }

  /** Ob dieses Kästchen der Mehrfachauswahl angehakt ist. */
  isLeaderRole(roleName: string): boolean {
    return this.editingGroup()?.leaderRoleNames.includes(roleName) ?? false;
  }

  /**
   * Hakt eine Leitungsrolle an oder ab.
   *
   * <p>Die Liste wird neu gebaut statt an Ort und Stelle geändert: `editingGroup`
   * ist ein Signal, und ein `push` in dieselbe Liste ändert die Referenz nicht -
   * die Kästchen blieben stehen, wie sie waren.</p>
   */
  toggleLeaderRole(roleName: string) {
    const group = this.editingGroup();
    if (!group) return;

    this.updateGroup({
      leaderRoleNames: group.leaderRoleNames.includes(roleName)
        ? group.leaderRoleNames.filter((name) => name !== roleName)
        : [...group.leaderRoleNames, roleName],
    });
  }

  saveGroup() {
    const group = this.editingGroup();
    if (!group) return;
    // Nur der Name ist Pflicht: fehlt die Rolle, leitet der Server sie aus ihm
    // ab - und {@link effectiveRoleName} zeigt vorher, was dabei herauskommt.
    if (!group.name.trim()) {
      this.toastService.error('Die Gruppe braucht einen Namen.');
      return;
    }

    this.saving.set(true);
    this.groupsService.saveGroup(group).subscribe({
      next: () => {
        this.saving.set(false);
        this.editingGroup.set(null);
        this.toastService.success('Gruppe gespeichert.');
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.toastService.error(err.error?.message || 'Gruppe konnte nicht gespeichert werden.');
      },
    });
  }

  async deleteGroup(group: GroupDto) {
    const confirmed = await this.confirmService.ask(
      'Gruppe löschen?',
      `"${group.name}" verschwindet samt ihrer offenen Anfragen. Die Rolle ${group.roleName} bleibt bestehen und muss im Rollenkatalog gepflegt werden.`,
      'Löschen',
    );
    if (!confirmed) return;

    this.groupsService.deleteGroup(group.id).subscribe({
      next: () => {
        this.toastService.success('Gruppe gelöscht.');
        this.load();
      },
      error: (err) =>
        this.toastService.error(err.error?.message || 'Gruppe konnte nicht gelöscht werden.'),
    });
  }

  /**
   * Der Rollenkatalog für die Vorschlagsliste und die Kästchen des Modals.
   *
   * <p>Erst beim Öffnen und nur einmal: die Liste braucht Verwalterrechte, und
   * zum Zeitpunkt von {@link ngOnInit} steht noch nicht fest, ob der Nutzer sie
   * hat. Ein Fehlschlag setzt die Sperre zurück, damit der nächste Versuch es
   * wieder probiert.</p>
   */
  private loadModalOptions() {
    if (this.modalOptionsLoaded()) return;
    this.modalOptionsLoaded.set(true);

    this.groupService.getRoles().subscribe({
      next: (roles) => this.roles.set(roles),
      error: () => {
        this.roles.set([]);
        this.modalOptionsLoaded.set(false);
      },
    });
  }

  // ================= Darstellung =================

  /**
   * Die Etiketten der Spalte "Leitung" - eines je zuständiger Rolle.
   *
   * <p>Ohne hinterlegte Rolle steht dort genau ein gedämpftes "Ohne Leitung"
   * statt einer leeren Zelle: das ist ein gültiger Zustand - dann entscheiden
   * die Admins - und keine fehlende Angabe.</p>
   *
   * <p>Die Liste kommt sortiert vom Server; hier wird nicht noch einmal
   * umgestellt, sonst stünden Tabelle und Antwort verschieden.</p>
   */
  leaderLabels(group: GroupDto): string[] {
    return group.leaderRoleNames.length > 0 ? group.leaderRoleNames : [LEADERLESS_LABEL];
  }

  /**
   * Die Zeile unter dem Gruppennamen.
   *
   * <p>Am Bildschirm stand hier eine 0, während ein Antrag lief, und das las
   * sich wie ein Fehler. Gezählt werden die Träger der Rolle; eine offene
   * Anfrage ist noch keine Mitgliedschaft und zählt deshalb nicht mit.</p>
   */
  memberLabel(group: GroupDto): string {
    return group.memberCount === 1 ? '1 Mitglied' : `${group.memberCount} Mitglieder`;
  }
}
