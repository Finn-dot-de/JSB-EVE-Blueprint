import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthRoleDto, GroupService } from '../../services/group.service';
import {
  MoveDirection,
  MoveKind,
  NavAdminViewDto,
  NavLinkDto,
  NavigationService,
  SaveCategoryDto,
  SaveLinkDto,
} from '../../services/navigation.service';
import { ConfirmService } from '../../services/confirm.service';
import { ToastService } from '../../services/toast.service';

/**
 * Ein Eintrag der obersten Ebene, so wie er in der Verwaltung erscheint.
 *
 * Register und einzelne Menüpunkte teilen sich diese Ebene - und damit auch
 * ihre Reihenfolge. Genau so sieht sie der Nutzer später in der Seitenleiste.
 */
export interface TopLevelRow {
  readonly kind: MoveKind;
  readonly id: number;
  readonly label: string;
  readonly icon: string | null;
  readonly sortOrder: number;
  /** Nur bei Registern gefüllt. */
  readonly children: NavLinkDto[];
  /** Nur bei einzelnen Menüpunkten gefüllt. */
  readonly link: NavLinkDto | null;
}

@Component({
  selector: 'app-navigation-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './navigation-admin.component.html',
  styleUrls: ['./navigation-admin.component.scss'],
})
export class NavigationAdminComponent implements OnInit {
  private navigationService = inject(NavigationService);
  private groupService = inject(GroupService);
  private toastService = inject(ToastService);
  private confirmService = inject(ConfirmService);

  view = signal<NavAdminViewDto>({ categories: [], links: [] });
  roles = signal<AuthRoleDto[]>([]);
  loading = signal(true);
  saving = signal(false);

  editingLink = signal<SaveLinkDto | null>(null);
  editingCategory = signal<SaveCategoryDto | null>(null);
  expandedCategories = signal<Set<number>>(new Set());

  /**
   * Die oberste Ebene in der Reihenfolge, in der sie später erscheint.
   *
   * Register ohne Menüpunkte stehen hier trotzdem - anders als in der
   * Seitenleiste, wo ein leerer Ordner weggelassen wird. In der Verwaltung
   * müssen sie sichtbar bleiben, sonst könnte man ein frisch angelegtes
   * Register nie befüllen.
   */
  topLevel = computed<TopLevelRow[]>(() => {
    const { categories, links } = this.view();

    const rows: TopLevelRow[] = categories.map((category) => ({
      kind: 'CATEGORY' as const,
      id: category.id,
      label: category.name,
      icon: category.icon,
      sortOrder: category.sortOrder,
      children: links
        .filter((link) => link.categoryId === category.id)
        .sort((a, b) => a.sortOrder - b.sortOrder),
      link: null,
    }));

    links
      .filter((link) => link.categoryId === null)
      .forEach((link) =>
        rows.push({
          kind: 'LINK' as const,
          id: link.id,
          label: link.label,
          icon: link.icon,
          sortOrder: link.sortOrder,
          children: [],
          link,
        }),
      );

    return rows.sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label));
  });

  /** Für die Auswahl beim Bearbeiten eines Menüpunkts. */
  categories = computed(() => this.view().categories);

  ngOnInit() {
    this.load();
    this.loadRoles();
  }

  load() {
    this.loading.set(true);
    this.navigationService.overview().subscribe({
      next: (view) => {
        this.view.set(view);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.toastService.error(err.error?.message || 'Die Navigation konnte nicht geladen werden.');
      },
    });
  }

  /** Die Rollenauswahl kommt aus dem Rollenkatalog - Tippfehler sind so ausgeschlossen. */
  private loadRoles() {
    this.groupService.getRoles().subscribe({
      next: (roles) => this.roles.set(roles),
      error: () => this.roles.set([]),
    });
  }

  // ================= Aufklappen =================

  toggleCategory(categoryId: number) {
    this.expandedCategories.update((current) => {
      const next = new Set(current);
      next.has(categoryId) ? next.delete(categoryId) : next.add(categoryId);
      return next;
    });
  }

  isExpanded(categoryId: number): boolean {
    return this.expandedCategories().has(categoryId);
  }

  // ================= Verschieben =================

  /**
   * Ob der Eintrag noch weiter in diese Richtung kann.
   *
   * Ohne diese Prüfung stünden am Rand Knöpfe, die nichts tun - der Server
   * lehnt den Tausch dort ohnehin still ab.
   */
  canMove(rows: readonly { id: number; kind?: MoveKind }[], index: number,
          direction: MoveDirection): boolean {
    return direction === 'UP' ? index > 0 : index < rows.length - 1;
  }

  move(kind: MoveKind, id: number, direction: MoveDirection) {
    this.navigationService.move(kind, id, direction).subscribe({
      next: () => this.load(),
      error: (err) => this.toastService.error(
        err.error?.message || 'Der Eintrag konnte nicht verschoben werden.'),
    });
  }

  // ================= Menüpunkte =================

  newLink(categoryId: number | null = null) {
    this.editingLink.set({
      id: null,
      label: '',
      url: '',
      icon: 'fa-solid fa-link',
      categoryId,
      requiredRole: null,
      active: true,
    });
  }

  editLink(link: NavLinkDto) {
    this.editingLink.set({
      id: link.id,
      label: link.label,
      url: link.url,
      icon: link.icon,
      categoryId: link.categoryId,
      requiredRole: link.requiredRole,
      active: link.active,
    });
  }

  closeLink() {
    this.editingLink.set(null);
  }

  updateLink(patch: Partial<SaveLinkDto>) {
    const link = this.editingLink();
    if (link) this.editingLink.set({ ...link, ...patch });
  }

  /**
   * Das Register kommt als Text aus dem Auswahlfeld - leer bedeutet
   * "oberste Ebene" und muss zu null werden, nicht zu NaN.
   */
  updateLinkCategory(value: string) {
    this.updateLink({ categoryId: value ? Number(value) : null });
  }

  saveLink() {
    const link = this.editingLink();
    if (!link) return;
    if (!link.label.trim() || !link.url.trim()) {
      this.toastService.error('Beschriftung und Ziel sind Pflicht.');
      return;
    }

    this.saving.set(true);
    this.navigationService.saveLink(link).subscribe({
      next: () => {
        this.saving.set(false);
        this.editingLink.set(null);
        this.toastService.success(`"${link.label}" gespeichert.`);
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.toastService.error(err.error?.message || 'Der Menüpunkt konnte nicht gespeichert werden.');
      },
    });
  }

  async deleteLink(link: NavLinkDto) {
    const confirmed = await this.confirmService.ask(
      `"${link.label}" löschen?`, 'Der Menüpunkt verschwindet für alle aus der Seitenleiste.');
    if (!confirmed) return;

    this.navigationService.deleteLink(link.id).subscribe({
      next: () => {
        this.toastService.success(`"${link.label}" gelöscht.`);
        this.load();
      },
      error: (err) => this.toastService.error(
        err.error?.message || 'Der Menüpunkt konnte nicht gelöscht werden.'),
    });
  }

  /** Schaltet einen Punkt an oder ab, ohne den Bearbeiten-Dialog zu öffnen. */
  toggleActive(link: NavLinkDto) {
    this.navigationService
      .saveLink({
        id: link.id,
        label: link.label,
        url: link.url,
        icon: link.icon,
        categoryId: link.categoryId,
        requiredRole: link.requiredRole,
        active: !link.active,
      })
      .subscribe({
        next: () => this.load(),
        error: (err) => this.toastService.error(
          err.error?.message || 'Der Menüpunkt konnte nicht geändert werden.'),
      });
  }

  // ================= Register =================

  newCategory() {
    this.editingCategory.set({ id: null, name: '', icon: 'fa-solid fa-folder' });
  }

  editCategory(row: TopLevelRow) {
    this.editingCategory.set({ id: row.id, name: row.label, icon: row.icon });
  }

  closeCategory() {
    this.editingCategory.set(null);
  }

  updateCategory(patch: Partial<SaveCategoryDto>) {
    const category = this.editingCategory();
    if (category) this.editingCategory.set({ ...category, ...patch });
  }

  saveCategory() {
    const category = this.editingCategory();
    if (!category) return;
    if (!category.name.trim()) {
      this.toastService.error('Das Register braucht einen Namen.');
      return;
    }

    this.saving.set(true);
    this.navigationService.saveCategory(category).subscribe({
      next: () => {
        this.saving.set(false);
        this.editingCategory.set(null);
        this.toastService.success(`Register "${category.name}" gespeichert.`);
        this.load();
      },
      error: (err) => {
        this.saving.set(false);
        this.toastService.error(err.error?.message || 'Das Register konnte nicht gespeichert werden.');
      },
    });
  }

  async deleteCategory(row: TopLevelRow) {
    const hint = row.children.length > 0
      ? ` Die ${row.children.length} enthaltenen Menüpunkte rutschen in die oberste Ebene.`
      : '';
    const confirmed = await this.confirmService.ask(
      `Register "${row.label}" löschen?`, `Das lässt sich nicht rückgängig machen.${hint}`);
    if (!confirmed) return;

    this.navigationService.deleteCategory(row.id).subscribe({
      next: () => {
        this.toastService.success(`Register "${row.label}" gelöscht.`);
        this.load();
      },
      error: (err) => this.toastService.error(
        err.error?.message || 'Das Register konnte nicht gelöscht werden.'),
    });
  }

  // ================= Darstellung =================

  categoryName(categoryId: number | null): string {
    if (categoryId === null) return 'Oberste Ebene';
    return this.categories().find((category) => category.id === categoryId)?.name ?? 'Unbekannt';
  }

  /** Externe Ziele erkennt man an ihrem Schema - so wie die Seitenleiste auch. */
  isExternal(url: string): boolean {
    return url.startsWith('http');
  }
}
