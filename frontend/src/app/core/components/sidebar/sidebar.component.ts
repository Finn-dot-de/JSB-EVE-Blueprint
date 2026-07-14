import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';

export interface MenuItem {
  name: string;
  icon: string; // Hier steht jetzt z.B. 'fa-solid fa-gear'
  route?: string;
  expanded?: boolean;
  children?: MenuItem[];
}

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, CommonModule],
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.scss']
})
export class SidebarComponent {

  menuItems: MenuItem[] = [
    { name: 'Dashboard', icon: 'fa-solid fa-chart-pie', route: '/dashboard' },
    { name: 'Services', icon: 'fa-solid fa-server', route: '/services' },
    { name: 'CharLink', icon: 'fa-solid fa-link', route: '/charlink' },
    {
      name: 'Gruppen Management',
      icon: 'fa-solid fa-users-gear',
      expanded: false,
      children: [
        { name: 'Member Übersicht', icon: 'fa-solid fa-user-group', route: '/groups/members' },
        { name: 'Rechte', icon: 'fa-solid fa-shield-halved', route: '/groups/rights' }
      ]
    },
    {
      name: 'CorpTools',
      icon: 'fa-solid fa-toolbox',
      expanded: true,
      children: [
        { name: 'Character Audit', icon: 'fa-solid fa-eye', route: '/corptools/audit' },
        { name: 'Industry Structures', icon: 'fa-solid fa-hammer', route: '/corptools/industry' }
      ]
    },
    { name: 'Buyback Program', icon: 'fa-solid fa-cart-shopping', route: '/buyback' },
    { name: 'Wiki', icon: 'fa-solid fa-book-journal-whills', route: '/wiki' }
  ];

  toggleMenu(item: MenuItem) {
    if (item.children) {
      item.expanded = !item.expanded;
    }
  }
}
