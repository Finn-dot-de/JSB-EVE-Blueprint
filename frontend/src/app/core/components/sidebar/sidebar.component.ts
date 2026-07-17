import { Component, OnInit, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

export interface NavigationLinkDto {
  id: number;
  label: string;
  url: string;
  icon: string;
  category: string | null;
  requiredRole: string | null;
}

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
  private http = inject(HttpClient);

  menuItems: MenuItem[] = [];

  // Die exakte Blaupause für deine gewünschte Reihenfolge
  private layoutOrder = [
    'Dashboard',
    'Services',
    'CharLink',
    'Gruppen Management',
    'Admin',
    'CorpTools',
    'Fleet Management',
    'SOV Monitor',
    'Buyback Program',
    'Intel Parser',
    'Moon Mining Pay!',
    'Patreon',
    'Reverse Buyback',
    'Ship Replacement',
    'Sovereignty Timer',
    'SYN Wiki',
    'Wiki'
  ];

  ngOnInit() {
    this.loadNavigation();
  }

  loadNavigation() {
    // Falls du das Backend noch nicht befüllt hast, kannst du zum Testen
    // hier auch erst mal statische Dummy-Daten laden lassen.
    this.http.get<NavigationLinkDto[]>('http://localhost:8080/api/navigation').subscribe({
      next: (links) => this.buildMenu(links),
      error: (err) => console.error('Fehler beim Laden der Navigation', err)
    });
  }

  buildMenu(links: NavigationLinkDto[]) {
    const categoryMap = new Map<string, MenuItem[]>();
    const rootItemsMap = new Map<string, MenuItem>();

    // 1. Links aus der DB sortieren
    links.forEach(link => {
      // Wenn die URL mit http beginnt, ist es ein externer Link
      const isExt = link.url.startsWith('http');

      const item: MenuItem = {
        name: link.label,
        icon: link.icon,
        route: link.url,
        isExternal: isExt
      };

      if (link.category) {
        if (!categoryMap.has(link.category)) {
          categoryMap.set(link.category, []);
        }
        categoryMap.get(link.category)!.push(item);
      } else {
        rootItemsMap.set(link.label, item);
      }
    });

    // 2. Das fertige Menü anhand der Blaupause zusammenbauen
    const sortedMenu: MenuItem[] = [];
    const processedKeys = new Set<string>();

    this.layoutOrder.forEach(key => {
      // Ist es eine Kategorie/Ein Ordner?
      if (categoryMap.has(key)) {
        let folderIcon = 'fa-solid fa-folder';
        // Spezifische Ordner-Icons
        if (key === 'CorpTools') folderIcon = 'fa-solid fa-folder';
        // Du kannst hier bei Bedarf andere Icons definieren, z.B. fa-users-gear für Gruppen

        sortedMenu.push({
          name: key,
          icon: folderIcon,
          expanded: false, // Standardmäßig zugeklappt
          children: categoryMap.get(key)
        });
        processedKeys.add(key);
      }
      // Ist es ein direkter Link?
      else if (rootItemsMap.has(key)) {
        sortedMenu.push(rootItemsMap.get(key)!);
        processedKeys.add(key);
      }
    });

    // 3. Alles, was in der DB ist, aber NICHT in der Blaupause steht, unten dranhängen
    categoryMap.forEach((children, categoryName) => {
      if (!processedKeys.has(categoryName)) {
        sortedMenu.push({ name: categoryName, icon: 'fa-solid fa-folder', expanded: false, children });
      }
    });

    rootItemsMap.forEach((item, label) => {
      if (!processedKeys.has(label)) {
        sortedMenu.push(item);
      }
    });

    // 4. Menü rendern
    this.menuItems = sortedMenu;
  }

  toggleMenu(item: MenuItem) {
    if (item.children) {
      item.expanded = !item.expanded;
    }
  }
}
