import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

export interface DashboardDto {
  characterName: string;
  portraitUrl: string;
  corporationName: string;
  allianceName: string | null;
  totalWalletBalance: number;
  totalCharacters: number;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss']
})
export class DashboardComponent implements OnInit {
  private http = inject(HttpClient);

  dashboardData = signal<DashboardDto | null>(null);

  ngOnInit() {
    this.http.get<DashboardDto>('http://localhost:8080/api/dashboard').subscribe({
      next: (data) => this.dashboardData.set(data),
      error: (err) => console.error('Fehler beim Laden des Dashboards', err)
    });
  }
}
