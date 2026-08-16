import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { ToastService } from '../../services/toast.service';
import { MenuEntryDto, NavigationService } from '../../services/navigation.service';

export interface MenuItem {
  name: string;
  icon: string;
  route?: string;
  expanded?: boolean;
  isExternal?: boolean;
  children?: MenuItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, CommonModule],
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.scss']
})
export class SidebarComponent implements OnInit {
  private navigationService = inject(NavigationService);
  private toastService = inject(ToastService);

  menuItems = signal<MenuItem[]>([]);

  ngOnInit() {
    this.loadNavigation();
  }

  loadNavigation() {
    this.navigationService.menu().subscribe({
      next: (entries) => this.buildMenu(entries),
      error: () => this.toastService.error('Die Navigation konnte nicht geladen werden.')
    });
  }

  /**
   * Übernimmt das Menü, wie der Server es liefert.
   *
   * Hier stand früher eine Blaupause aus 17 fest verdrahteten Namen, die die
   * Reihenfolge bestimmte. Damit landete jedes neu angelegte Register dauerhaft
   * unten und ließ sich nicht verschieben, ohne den Code anzufassen. Reihenfolge
   * und Zuordnung kommen jetzt aus der Datenbank und sind über die Verwaltung
   * unter /admin/navigation pflegbar.
   */
  buildMenu(entries: MenuEntryDto[]) {
    this.menuItems.set(
      entries.map((entry) =>
        entry.children.length > 0
          ? {
              name: entry.label,
              icon: entry.icon,
              expanded: false,
              children: entry.children.map((child) => ({
                name: child.label,
                icon: child.icon,
                route: child.url,
                isExternal: child.external,
              })),
            }
          : {
              name: entry.label,
              icon: entry.icon,
              route: entry.url ?? undefined,
              isExternal: entry.external,
            },
      ),
    );
  }

  toggleMenu(item: MenuItem) {
    if (item.children) {
      item.expanded = !item.expanded;
    }
  }
}
