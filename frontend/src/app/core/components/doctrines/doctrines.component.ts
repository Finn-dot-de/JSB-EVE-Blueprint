import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DoctrineService, FleetDoctrine } from '../../services/doctrine.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';
import { MyFitDto, ReadinessService } from '../../services/readiness.service';
import {
  SaveSkillPlanDto,
  SkillEntryDto,
  SkillOptionDto,
  SkillPlanDto,
  SkillPlanService,
} from '../../services/skill-plan.service';
import { typeRender } from '../../shared/eve-image.util';
import { copyText } from '../../shared/clipboard.util';
import { toPlanLines, toSkillPlanText } from '../../shared/skill-plan.util';
import { latestRequest } from '../../shared/latest-request.util';

/** Wie ein Fitting für den Angemeldeten dasteht. */
export type FitStanding = 'FULL' | 'CAN_FLY' | 'MISSING' | 'UNKNOWN';

@Component({
  selector: 'app-doctrines',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './doctrines.component.html',
  styleUrls: ['./doctrines.component.scss']
})
export class DoctrinesComponent implements OnInit {
  public authService = inject(AuthService);
  private doctrineService = inject(DoctrineService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);
  private readinessService = inject(ReadinessService);
  private skillPlanService = inject(SkillPlanService);

  protected readonly typeRender = typeRender;


  doctrines = signal<FleetDoctrine[]>([]);

  // --- Selbstauskunft: was kann ich fliegen? ---
  myFits = signal<Map<number, MyFitDto>>(new Map());
  expandedFitId = signal<number | null>(null);

  // --- Skillplan-Verwaltung ---
  plans = signal<SkillPlanDto[]>([]);
  showPlanManager = signal(false);
  editingPlan = signal<SaveSkillPlanDto | null>(null);
  skillQuery = signal('');
  skillOptions = signal<SkillOptionDto[]>([]);
  planImportText = signal('');
  savingPlan = signal(false);

  /** Das Fitting, dessen Plan-Zuordnung gerade bearbeitet wird. */
  assigningDoctrine = signal<FleetDoctrine | null>(null);
  assignedPlanIds = signal<Set<number>>(new Set());

  showCreateModal = signal(false);
  selectedDoctrine = signal<FleetDoctrine | null>(null);
  editingDoctrineId = signal<number | null>(null); // Speichert die ID beim Bearbeiten

  newEftInput = signal('');
  newDoctrineName = signal('');
  isSubmitting = signal(false);

  searchQuery = signal('');

  groupedDoctrines = computed(() => {
    const query = this.searchQuery().toLowerCase();
    let filtered = this.doctrines();

    if (query) {
      filtered = filtered.filter(doc =>
        doc.name.toLowerCase().includes(query) ||
        doc.shipType.toLowerCase().includes(query) ||
        (doc.doctrineName && doc.doctrineName.toLowerCase().includes(query))
      );
    }

    const groups = new Map<string, FleetDoctrine[]>();
    filtered.forEach(doc => {
      const groupName = doc.doctrineName || 'Ungruppiert';
      if (!groups.has(groupName)) {
        groups.set(groupName, []);
      }
      groups.get(groupName)!.push(doc);
    });

    return Array.from(groups.entries()).map(([name, docs]) => ({
      name,
      docs: docs.sort((a, b) => a.shipType.localeCompare(b.shipType))
    })).sort((a, b) => a.name.localeCompare(b.name));
  });

  parsedGroups = computed(() => {
    const doc = this.selectedDoctrine();
    if (!doc) return [];

    const rawLines = doc.eftString.split('\n');
    if (rawLines.length <= 1) return [];

    const groups: { name: string; modules: string[] }[] = [
      { name: 'High Slots', modules: [] },
      { name: 'Mid Slots', modules: [] },
      { name: 'Low Slots', modules: [] },
      { name: 'Rigs', modules: [] },
      { name: 'Subsystems', modules: [] },
      { name: 'Drones', modules: [] },
      { name: 'Cargo / Ammo', modules: [] }
    ];

    let currentBlock = 0;
    let hasStarted = false;

    // Wir ignorieren Zeile 0 (Das ist immer [Schiff, Name])
    for (let i = 1; i < rawLines.length; i++) {
      const line = rawLines[i].trim();

      // Bei einer Leerzeile springen wir eine Kategorie weiter (genau so funktioniert das EFT Format)
      if (line === '') {
        if (hasStarted) {
          currentBlock++;
        }
        continue;
      }

      hasStarted = true;

      // Pyfa fügt manchmal "[Empty High slot]" ein, das wollen wir im UI nicht anzeigen
      if (line.startsWith('[Empty') && line.endsWith(']')) {
        continue;
      }

      // Sicherheits-Clamp: Alles nach Block 6 (Drones) landet automatisch im Cargo
      const blockIndex = Math.min(currentBlock, 6);
      groups[blockIndex].modules.push(line);
    }

    // Wir geben dem UI nur die Gruppen zurück, in denen auch wirklich ein Modul steckt
    return groups.filter(g => g.modules.length > 0);
  });

  get isFleetCommander(): boolean {
    return this.authService.hasAnyRole(['ROLE_CEO', 'ROLE_DIRECTOR', 'ROLE_1337', 'ROLE_A38']);
  }

  /**
   * Die Skill-Suche des Plus-Knopfs.
   *
   * Über latestRequest, damit beim schnellen Tippen die Antwort zum letzten
   * Suchwort gewinnt und nicht die zufällig zuletzt eingetroffene.
   */
  private requestSkills = latestRequest<string, SkillOptionDto[]>({
    run: (query) => this.skillPlanService.searchSkills(query),
    next: (options) => this.skillOptions.set(options),
    error: () => this.skillOptions.set([]),
    debounceMs: 250,
    distinct: true,
  });

  ngOnInit() {
    this.loadDoctrines();
    this.loadMyReadiness();
    this.loadPlans();
  }

  loadDoctrines() {
    this.doctrineService.getDoctrines().subscribe(docs => this.doctrines.set(docs));
  }

  // ================= Selbstauskunft =================

  /**
   * Holt den eigenen Stand zu allen Fittings.
   *
   * Ein Fehlschlag bleibt still: die Fitting-Übersicht ist auch ohne die
   * Selbstauskunft nützlich, und ein Mitglied ohne synchronisierte Skills
   * soll hier keine Fehlermeldung vorgesetzt bekommen.
   */
  loadMyReadiness() {
    this.readinessService.myReadiness().subscribe({
      next: (fits) => this.myFits.set(new Map(fits.map((fit) => [fit.fitId, fit]))),
      error: () => this.myFits.set(new Map()),
    });
  }

  myFit(doctrineId: number): MyFitDto | undefined {
    return this.myFits().get(doctrineId);
  }

  /** Wie das Fitting für den Angemeldeten dasteht. */
  standing(doctrineId: number): FitStanding {
    const fit = this.myFit(doctrineId);
    if (!fit || !fit.skillDataAvailable) return 'UNKNOWN';
    if (fit.fullySkilled) return 'FULL';
    if (fit.canFly) return 'CAN_FLY';
    return 'MISSING';
  }

  standingLabel(doctrineId: number): string {
    switch (this.standing(doctrineId)) {
      case 'FULL': return 'Voll ausgeskillt';
      case 'CAN_FLY': return 'Kannst du fliegen';
      case 'MISSING': return 'Skills fehlen';
      default: return 'Keine Skilldaten';
    }
  }

  toggleFitDetails(doctrineId: number) {
    this.expandedFitId.update((current) => (current === doctrineId ? null : doctrineId));
  }

  // ================= Skillpläne =================

  loadPlans() {
    this.skillPlanService.list().subscribe({
      next: (plans) => this.plans.set(plans),
      error: () => this.plans.set([]),
    });
  }

  openPlanManager() {
    this.showPlanManager.set(true);
    this.editingPlan.set(null);
    this.loadPlans();
  }

  closePlanManager() {
    this.showPlanManager.set(false);
    this.editingPlan.set(null);
    this.skillOptions.set([]);
    this.skillQuery.set('');
    this.planImportText.set('');
  }

  newPlan() {
    this.editingPlan.set({ id: null, name: '', description: null, skills: [] });
  }

  editPlan(plan: SkillPlanDto) {
    this.editingPlan.set({
      id: plan.id,
      name: plan.name,
      description: plan.description,
      skills: [...plan.skills],
    });
  }

  onSkillQuery(query: string) {
    this.skillQuery.set(query);
    if (query.trim().length < 2) {
      this.skillOptions.set([]);
      return;
    }
    this.requestSkills(query.trim());
  }

  /** Übernimmt einen Vorschlag des Plus-Knopfs in den Plan. */
  addSkill(option: SkillOptionDto, level = 5) {
    const plan = this.editingPlan();
    if (!plan) return;
    if (plan.skills.some((skill) => skill.skillTypeId === option.typeId)) {
      this.toastService.info(`${option.typeName} steht schon im Plan.`);
      return;
    }
    this.editingPlan.set({
      ...plan,
      skills: [...plan.skills, { skillTypeId: option.typeId, skillName: option.typeName, level }],
    });
    this.skillQuery.set('');
    this.skillOptions.set([]);
  }

  removeSkill(skillTypeId: number) {
    const plan = this.editingPlan();
    if (!plan) return;
    this.editingPlan.set({
      ...plan,
      skills: plan.skills.filter((skill) => skill.skillTypeId !== skillTypeId),
    });
  }

  setSkillLevel(skillTypeId: number, level: number) {
    const plan = this.editingPlan();
    if (!plan) return;
    this.editingPlan.set({
      ...plan,
      skills: plan.skills.map((skill) =>
        skill.skillTypeId === skillTypeId ? { ...skill, level } : skill,
      ),
    });
  }

  setPlanName(name: string) {
    const plan = this.editingPlan();
    if (plan) this.editingPlan.set({ ...plan, name });
  }

  setPlanDescription(description: string) {
    const plan = this.editingPlan();
    if (plan) this.editingPlan.set({ ...plan, description });
  }

  /** Liest einen eingefügten Plantext ein - "Power Grid Management V" je Zeile. */
  importPlanText() {
    const text = this.planImportText().trim();
    const plan = this.editingPlan();
    if (!text || !plan) return;

    this.skillPlanService.importPlanText(text).subscribe({
      next: (result) => {
        const known = new Set(plan.skills.map((skill) => skill.skillTypeId));
        const added = result.skills.filter((skill) => !known.has(skill.skillTypeId));
        this.editingPlan.set({ ...plan, skills: [...plan.skills, ...added] });
        this.planImportText.set('');

        if (result.unresolved.length > 0) {
          this.toastService.error(`Nicht erkannt: ${result.unresolved.join(', ')}`);
        } else {
          this.toastService.success(`${added.length} Skills übernommen.`);
        }
      },
      error: () => this.toastService.error('Der Plantext konnte nicht gelesen werden.'),
    });
  }

  savePlan() {
    const plan = this.editingPlan();
    if (!plan || !plan.name.trim()) {
      this.toastService.error('Der Plan braucht einen Namen.');
      return;
    }

    this.savingPlan.set(true);
    this.skillPlanService.save(plan).subscribe({
      next: (saved) => {
        this.savingPlan.set(false);
        this.editingPlan.set(null);
        this.toastService.success(`Plan "${saved.name}" gespeichert.`);
        this.loadPlans();
        this.loadMyReadiness();
      },
      error: (err) => {
        this.savingPlan.set(false);
        this.toastService.error(err.error?.message || 'Der Plan konnte nicht gespeichert werden.');
      },
    });
  }

  async deletePlan(plan: SkillPlanDto) {
    const hint = plan.usedByFittings > 0
      ? ` Er hängt noch an ${plan.usedByFittings} Fitting(s) - die Zuordnung verschwindet mit.`
      : '';
    const confirmed = await this.confirmService.ask(
      `Plan "${plan.name}" löschen?`, `Das lässt sich nicht rückgängig machen.${hint}`);
    if (!confirmed) return;

    this.skillPlanService.delete(plan.id).subscribe({
      next: () => {
        this.toastService.success(`Plan "${plan.name}" gelöscht.`);
        this.loadPlans();
        this.loadMyReadiness();
      },
      error: (err) => this.toastService.error(
        err.error?.message || 'Der Plan konnte nicht gelöscht werden.'),
    });
  }

  // ================= Zuordnung an ein Fitting =================

  openAssign(doc: FleetDoctrine) {
    this.assigningDoctrine.set(doc);
    const current = this.myFit(doc.id)?.planNames ?? [];
    // Die Zuordnung kommt über die Namen zurück - für die Auswahl brauchen
    // wir die IDs, also über den geladenen Plankatalog auflösen.
    this.assignedPlanIds.set(new Set(
      this.plans().filter((plan) => current.includes(plan.name)).map((plan) => plan.id),
    ));
  }

  closeAssign() {
    this.assigningDoctrine.set(null);
  }

  togglePlanAssignment(planId: number) {
    this.assignedPlanIds.update((current) => {
      const next = new Set(current);
      next.has(planId) ? next.delete(planId) : next.add(planId);
      return next;
    });
  }

  saveAssignment() {
    const doc = this.assigningDoctrine();
    if (!doc) return;

    this.skillPlanService.assign(doc.id, [...this.assignedPlanIds()]).subscribe({
      next: () => {
        this.toastService.success(`Skillpläne für "${doc.name}" gespeichert.`);
        this.closeAssign();
        this.loadPlans();
        this.loadMyReadiness();
      },
      error: (err) => this.toastService.error(
        err.error?.message || 'Die Zuordnung konnte nicht gespeichert werden.'),
    });
  }

  openCreateModal() {
    this.editingDoctrineId.set(null); // Reset für neues Fitting
    this.newEftInput.set('');
    this.newDoctrineName.set('');
    this.showCreateModal.set(true);
  }

  // Öffnet das Modal mit den vorausgefüllten Daten zum Bearbeiten
  openEditModal(doc: FleetDoctrine) {
    this.editingDoctrineId.set(doc.id);
    this.newDoctrineName.set(doc.doctrineName === 'Ungruppiert' ? '' : doc.doctrineName);
    this.newEftInput.set(doc.eftString);
    this.showCreateModal.set(true);
  }

  openDetails(doc: FleetDoctrine) {
    this.selectedDoctrine.set(doc);
  }

  closeModals() {
    this.showCreateModal.set(false);
    this.selectedDoctrine.set(null);
    this.editingDoctrineId.set(null);
  }

  parseAndSaveFitting() {
    const rawText = this.newEftInput().trim();
    if (!rawText) return;

    const lines = rawText.split('\n');
    const firstLine = lines[0].trim();
    const match = firstLine.match(/^\[(.*?),\s*(.*?)\]/);

    if (match) {
      this.isSubmitting.set(true);
      const shipType = match[1].trim();
      const fitName = match[2].trim();

      let finalDoctrineName = this.newDoctrineName().trim();

      if (!finalDoctrineName) {
        const nameParts = fitName.split(' ');

        if (nameParts.length >= 2) {
          finalDoctrineName = `${nameParts[0]} ${nameParts[1]}`;
        } else {
          finalDoctrineName = fitName;
        }
      }

      const payload = {
        doctrineName: finalDoctrineName,
        shipType: shipType,
        name: fitName,
        eftString: rawText
      };

      // Entscheide: Update (PUT) oder Create (POST)
      const request$ = this.editingDoctrineId()
        ? this.doctrineService.updateDoctrine(this.editingDoctrineId()!, payload)
        : this.doctrineService.createDoctrine(payload);

      request$.subscribe({
        next: () => {
          this.isSubmitting.set(false);
          this.closeModals();
          this.loadDoctrines();
          this.toastService.success(`Fitting erfolgreich unter "${finalDoctrineName}" gespeichert!`);
        },
        error: (err) => {
          this.isSubmitting.set(false);
          this.toastService.error('Fehler beim Speichern: ' + (err.error?.message || 'Unbekannt'));
        }
      });
    } else {
      this.toastService.error('Ungültiges EFT Format! Die erste Zeile muss [Schiffstyp, Fitting Name] lauten.');
    }
  }

  copyToClipboard(eftString: string | undefined): Promise<void> {
    if (!eftString) return Promise.resolve();
    return copyText(eftString).then((ok) => {
      if (!ok) {
        this.toastService.error('Fehler beim Kopieren in die Zwischenablage.');
        return;
      }
      this.toastService.info(
        'Fitting kopiert! Öffne Ingame dein Fitting-Fenster und wähle "Import from Clipboard".');
      this.closeModals();
    });
  }

  /**
   * Legt die fehlenden Skills als Plantext in die Zwischenablage.
   *
   * Beide Quellen zusammen: was zum Fliegen fehlt und was zum Skillplan fehlt.
   * Genau diese Liste muss der Pilot trainieren, und in dieser Form nimmt der
   * EVE-Client sie beim Anlegen eines Skillplans entgegen.
   */
  copyMissingSkills(doctrineId: number): Promise<void> {
    const fit = this.myFit(doctrineId);
    if (!fit) return Promise.resolve();

    const text = toSkillPlanText(
      toPlanLines([...fit.missingSkills, ...fit.missingPlanSkills]));
    if (!text) {
      this.toastService.info('Dir fehlt nichts - der Plan ist vollständig.');
      return Promise.resolve();
    }

    return copyText(text).then((ok) =>
      ok
        ? this.toastService.success(
            'Skills kopiert! Ingame im Charakterbogen einen Skillplan anlegen und einfügen.')
        : this.toastService.error('Fehler beim Kopieren in die Zwischenablage.'));
  }

  /** Legt den kompletten Plan als Text in die Zwischenablage. */
  copyPlan(plan: SkillPlanDto): Promise<void> {
    const text = toSkillPlanText(toPlanLines(plan.skills));
    if (!text) {
      this.toastService.info(`"${plan.name}" enthält noch keine Skills.`);
      return Promise.resolve();
    }

    return copyText(text).then((ok) =>
      ok
        ? this.toastService.success(`"${plan.name}" kopiert.`)
        : this.toastService.error('Fehler beim Kopieren in die Zwischenablage.'));
  }

  /** Ob es zu diesem Fitting überhaupt etwas zu kopieren gibt. */
  hasMissingSkills(doctrineId: number): boolean {
    const fit = this.myFit(doctrineId);
    return !!fit && fit.missingSkills.length + fit.missingPlanSkills.length > 0;
  }

  async deleteDoctrine(id: number) {
    const confirmed = await this.confirmService.ask(
      'Fitting löschen?',
      'Möchtest du dieses Fitting wirklich unwiderruflich löschen?',
      'Löschen',
      'Abbrechen'
    );

    if (confirmed) {
      this.doctrineService.deleteDoctrine(id).subscribe(() => {
        this.loadDoctrines();
        this.toastService.success('Fitting wurde gelöscht.');
      });
    }
  }
}
