import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NavigationAdminComponent } from './navigation-admin.component';
import { ConfirmService } from '../../services/confirm.service';
import { GroupService } from '../../services/group.service';
import { NavCategoryDto, NavLinkDto, NavigationService } from '../../services/navigation.service';
import { ToastService } from '../../services/toast.service';

function category(overrides: Partial<NavCategoryDto> = {}): NavCategoryDto {
  return {
    id: 10,
    name: 'Fleet Management',
    icon: 'fa-solid fa-folder',
    sortOrder: 10,
    linkCount: 1,
    ...overrides,
  };
}

function link(overrides: Partial<NavLinkDto> = {}): NavLinkDto {
  return {
    id: 1,
    label: 'Dashboard',
    url: '/dashboard',
    icon: 'fa-solid fa-gauge-high',
    categoryId: null,
    requiredRole: null,
    active: true,
    sortOrder: 0,
    ...overrides,
  };
}

describe('NavigationAdminComponent', () => {
  let component: NavigationAdminComponent;
  let navigationService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let confirmService: { ask: ReturnType<typeof vi.fn> };

  /** Ein Register mit einem Punkt darin, plus zwei Punkte der obersten Ebene. */
  const overview = {
    categories: [category()],
    links: [
      link({ id: 1, label: 'Dashboard', categoryId: null, sortOrder: 0 }),
      link({ id: 2, label: 'Tracking', url: '/fleet/tracking', categoryId: 10, sortOrder: 0 }),
      link({ id: 3, label: 'CharLink', url: '/charlink', categoryId: null, sortOrder: 20 }),
    ],
  };

  beforeEach(() => {
    navigationService = {
      overview: vi.fn().mockReturnValue(of(overview)),
      saveCategory: vi.fn().mockReturnValue(of(category())),
      deleteCategory: vi.fn().mockReturnValue(of(null)),
      saveLink: vi.fn().mockReturnValue(of(link())),
      deleteLink: vi.fn().mockReturnValue(of(null)),
      move: vi.fn().mockReturnValue(of(null)),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };

    TestBed.configureTestingModule({
      providers: [
        { provide: NavigationService, useValue: navigationService },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: confirmService },
        {
          provide: GroupService,
          useValue: {
            getRoles: vi.fn().mockReturnValue(
              of([{ name: 'ROLE_DIRECTOR', description: '', source: 'BUILT_IN', special: false, grantingTitles: [] }]),
            ),
          },
        },
      ],
    });
    component = TestBed.runInInjectionContext(() => new NavigationAdminComponent());
  });

  describe('Übersicht', () => {
    it('lädt beim Start Navigation und Rollen', () => {
      component.ngOnInit();

      expect(navigationService['overview']).toHaveBeenCalled();
      expect(component.roles()).toHaveLength(1);
      expect(component.loading()).toBe(false);
    });

    it('mischt Register und einzelne Punkte in einer Reihenfolge', () => {
      // Genau so sieht die oberste Ebene später in der Seitenleiste aus.
      component.ngOnInit();

      expect(component.topLevel().map((row) => row.label)).toEqual([
        'Dashboard',
        'Fleet Management',
        'CharLink',
      ]);
    });

    it('hängt die Punkte unter ihr Register', () => {
      component.ngOnInit();

      const folder = component.topLevel().find((row) => row.kind === 'CATEGORY');
      expect(folder?.children.map((child) => child.label)).toEqual(['Tracking']);
    });

    it('zeigt ein leeres Register trotzdem an', () => {
      // In der Seitenleiste bleibt es weg - hier muss es sichtbar sein, sonst
      // liesse sich ein frisch angelegtes Register nie befüllen.
      navigationService['overview'].mockReturnValue(
        of({ categories: [category({ id: 20, name: 'Leer', linkCount: 0 })], links: [] }),
      );
      component.load();

      expect(component.topLevel().map((row) => row.label)).toEqual(['Leer']);
    });

    it('meldet einen Fehlschlag beim Laden', () => {
      navigationService['overview'].mockReturnValue(
        throwError(() => ({ error: { message: 'Zugriff verweigert.' } })),
      );

      component.load();

      expect(toastService['error']).toHaveBeenCalledWith('Zugriff verweigert.');
      expect(component.loading()).toBe(false);
    });

    it('klappt ein Register auf und wieder zu', () => {
      component.toggleCategory(10);
      expect(component.isExpanded(10)).toBe(true);

      component.toggleCategory(10);
      expect(component.isExpanded(10)).toBe(false);
    });
  });

  describe('Verschieben', () => {
    beforeEach(() => component.ngOnInit());

    it('schickt Richtung und Art an den Server und lädt neu', () => {
      component.move('CATEGORY', 10, 'UP');

      expect(navigationService['move']).toHaveBeenCalledWith('CATEGORY', 10, 'UP');
      expect(navigationService['overview']).toHaveBeenCalledTimes(2);
    });

    it('sperrt die Pfeile am Rand der Liste', () => {
      // Sonst stünden dort Knöpfe, die nichts tun.
      const rows = component.topLevel();

      expect(component.canMove(rows, 0, 'UP')).toBe(false);
      expect(component.canMove(rows, 0, 'DOWN')).toBe(true);
      expect(component.canMove(rows, rows.length - 1, 'DOWN')).toBe(false);
    });

    it('meldet einen Fehlschlag', () => {
      navigationService['move'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.move('LINK', 1, 'DOWN');

      expect(toastService['error']).toHaveBeenCalled();
    });
  });

  describe('Menüpunkte', () => {
    beforeEach(() => component.ngOnInit());

    it('legt einen neuen Punkt direkt im gewählten Register an', () => {
      component.newLink(10);

      expect(component.editingLink()?.categoryId).toBe(10);
      expect(component.editingLink()?.active).toBe(true);
    });

    it('übernimmt einen bestehenden Punkt zum Bearbeiten', () => {
      component.editLink(link({ id: 7, label: 'Mining', categoryId: 10 }));

      expect(component.editingLink()?.id).toBe(7);
      expect(component.editingLink()?.label).toBe('Mining');
    });

    it('macht aus der leeren Auswahl die oberste Ebene, nicht NaN', () => {
      component.newLink(10);

      component.updateLinkCategory('');

      expect(component.editingLink()?.categoryId).toBeNull();
    });

    it('wandelt die gewählte Register-ID in eine Zahl', () => {
      component.newLink(null);

      component.updateLinkCategory('10');

      expect(component.editingLink()?.categoryId).toBe(10);
    });

    it('speichert nicht ohne Beschriftung oder Ziel', () => {
      component.newLink(null);
      component.updateLink({ label: 'Nur Text' });

      component.saveLink();

      expect(navigationService['saveLink']).not.toHaveBeenCalled();
      expect(toastService['error']).toHaveBeenCalled();
    });

    it('speichert und lädt die Übersicht neu', () => {
      component.newLink(null);
      component.updateLink({ label: 'Neu', url: '/neu' });

      component.saveLink();

      expect(navigationService['saveLink']).toHaveBeenCalled();
      expect(component.editingLink()).toBeNull();
      expect(navigationService['overview']).toHaveBeenCalledTimes(2);
    });

    it('behält die Eingabe nach einem Fehlschlag', () => {
      navigationService['saveLink'].mockReturnValue(
        throwError(() => ({ error: { message: 'Ziel fehlt.' } })),
      );
      component.newLink(null);
      component.updateLink({ label: 'Neu', url: '/neu' });

      component.saveLink();

      expect(toastService['error']).toHaveBeenCalledWith('Ziel fehlt.');
      expect(component.editingLink()?.label).toBe('Neu');
      expect(component.saving()).toBe(false);
    });

    it('schaltet einen Punkt um, ohne den Dialog zu öffnen', () => {
      component.toggleActive(link({ id: 5, active: true }));

      expect(navigationService['saveLink']).toHaveBeenCalledWith(
        expect.objectContaining({ id: 5, active: false }),
      );
      expect(component.editingLink()).toBeNull();
    });

    it('löscht erst nach Rückfrage', async () => {
      await component.deleteLink(link({ id: 5, label: 'Weg damit' }));

      expect(confirmService.ask).toHaveBeenCalled();
      expect(navigationService['deleteLink']).toHaveBeenCalledWith(5);
    });

    it('löscht nach Abbruch nichts', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.deleteLink(link({ id: 5 }));

      expect(navigationService['deleteLink']).not.toHaveBeenCalled();
    });
  });

  describe('Register', () => {
    beforeEach(() => component.ngOnInit());

    it('übernimmt ein bestehendes Register zum Bearbeiten', () => {
      const row = component.topLevel().find((entry) => entry.kind === 'CATEGORY')!;

      component.editCategory(row);

      expect(component.editingCategory()?.id).toBe(10);
      expect(component.editingCategory()?.name).toBe('Fleet Management');
    });

    it('speichert nicht ohne Namen', () => {
      component.newCategory();

      component.saveCategory();

      expect(navigationService['saveCategory']).not.toHaveBeenCalled();
      expect(toastService['error']).toHaveBeenCalled();
    });

    it('speichert und schließt den Dialog', () => {
      component.newCategory();
      component.updateCategory({ name: 'Tools' });

      component.saveCategory();

      expect(navigationService['saveCategory']).toHaveBeenCalled();
      expect(component.editingCategory()).toBeNull();
    });

    it('warnt vor den enthaltenen Punkten beim Löschen', async () => {
      const row = component.topLevel().find((entry) => entry.kind === 'CATEGORY')!;

      await component.deleteCategory(row);

      expect(confirmService.ask).toHaveBeenCalledWith(
        expect.stringContaining('Fleet Management'),
        expect.stringContaining('oberste Ebene'),
      );
      expect(navigationService['deleteCategory']).toHaveBeenCalledWith(10);
    });
  });

  describe('Darstellung', () => {
    beforeEach(() => component.ngOnInit());

    it('erkennt externe Ziele an ihrem Schema', () => {
      expect(component.isExternal('https://wiki.example.org')).toBe(true);
      expect(component.isExternal('/dashboard')).toBe(false);
    });

    it('schreibt das Register eines Punktes aus', () => {
      expect(component.categoryName(10)).toBe('Fleet Management');
      expect(component.categoryName(null)).toBe('Oberste Ebene');
      expect(component.categoryName(999)).toBe('Unbekannt');
    });
  });
});
