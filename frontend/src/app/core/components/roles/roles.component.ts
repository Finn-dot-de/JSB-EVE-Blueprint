import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GroupService, CorpTitleDto } from '../../services/group.service';

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './roles.component.html',
  styleUrls: ['./roles.component.scss']
})
export class RolesComponent implements OnInit {
  private groupService = inject(GroupService);

  titles = signal<CorpTitleDto[]>([]);
  loading = signal<boolean>(true);

  ngOnInit() {
    this.groupService.getCorporationTitles().subscribe({
      next: (data) => {
        this.titles.set(data);
        this.loading.set(false);
      },
      error: (err) => { 
        console.error('Fehler beim Laden der Titel', err);
        this.loading.set(false);
      }
    });
  }

  // Gibt dem Icon je nach zugeordneter Rolle eine passende Farbe
  getRoleIconClass(role: string | null): string {
    if (!role) return 'text-secondary'; // Grau für nicht zugeordnet
    if (role.includes('ADMIN') || role.includes('DIRECTOR')) return 'text-warning'; // Orange/Gold
    if (role.includes('FC') || role.includes('FLEET')) return 'text-primary'; // Blau
    if (role.includes('INDUSTRY')) return 'text-success'; // Grün
    return 'text-danger'; // Rot als Standard für andere zugeordnete
  }

  // Wird später aufgerufen, wenn man rechts auf den Edit-Stift klickt
  editMapping(title: CorpTitleDto) {
    console.log('Edit Mapping für:', title.name);
    // Hier öffnen wir später ein kleines Modal/Dropdown,
    // um die Rolle (z.B. ROLE_DIRECTOR) einzutippen und zu speichern.
  }
}
