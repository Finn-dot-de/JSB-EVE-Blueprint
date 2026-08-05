import {Component, OnInit, inject, signal, computed} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import {environment} from '../../../../environments/environment';
import { formatCompact } from '../../shared/eve-format.util';
import { FALLBACK_ICON, LOYALTY_ICONS, LOYALTY_LABELS, MILITIA_ICONS, SHIP_CLASS_ICONS } from './dashboard-icons';
import { SIZE_LARGE, allianceLogo, corporationLogo } from '../../shared/eve-image.util';

// 1. NEU: Wir definieren die Struktur, die dein Java-Backend jetzt schickt
export interface DashboardAssetSummaryDto {
  subcapital: Record<string, number>;
  capital: Record<string, number>;
  industrial: Record<string, number>;
  notable: Record<string, number>;
  structures: Record<string, number>;
}

export interface LoyaltyPointDto {
  factionName: string;
  amount: number;
}

export interface LinkedCharacterDto {
  id: number;
  name: string;
  portraitUrl: string;
}

export interface DashboardAffiliationsDto {
  militias: Record<string, number>;
  evermarks: number;
  loyaltyPoints: Record<string, number>;
}

export interface DashboardDto {
  characterName: string;
  portraitUrl: string;
  corporationId: number;
  corporationName: string;
  allianceId: number | null;
  allianceName: string | null;
  totalWalletBalance: number;
  totalSkillPoints: number;
  totalCharacters: number;
  linkedCharacters: LinkedCharacterDto[];
  assets: DashboardAssetSummaryDto;
  affiliations: DashboardAffiliationsDto; // NEU
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

  /** Paragon - die NPC-Corporation, deren Loyalitaetspunkte als Evermarks gelten. */
  private static readonly PARAGON_CORPORATION_ID = 1000419;

  protected readonly paragonLogoUrl =
    corporationLogo(DashboardComponent.PARAGON_CORPORATION_ID, SIZE_LARGE);
  protected readonly allianceLogo = allianceLogo;
  protected readonly corporationLogo = corporationLogo;


  /** Kurzzahl fuer die Kacheln - Implementierung in der gemeinsamen Utility. */
  protected readonly formatShortNumber = formatCompact;

  dashboardData = signal<DashboardDto | null>(null);

  // --- NEU: Wir wandeln die Records für das HTML in saubere Arrays um ---
  subcapitalList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.assets) return [];
    // Wandelt {"Frigate": 5, "Cruiser": 2} in [{name: "Frigate", quantity: 5}, ...] um
    return Object.entries(data.assets.subcapital).map(([name, quantity]) => ({ name, quantity }));
  });

  capitalList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.assets) return [];
    return Object.entries(data.assets.capital).map(([name, quantity]) => ({ name, quantity }));
  });

  industrialList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.assets) return [];
    return Object.entries(data.assets.industrial).map(([name, quantity]) => ({ name, quantity }));
  });

  notableList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.assets) return [];
    return Object.entries(data.assets.notable).map(([name, quantity]) => ({ name, quantity }));
  });

  structuresList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.assets) return [];
    return Object.entries(data.assets.structures).map(([name, quantity]) => ({ name, quantity }));
  });

  militiaList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.affiliations) return [];
    return Object.entries(data.affiliations.militias).map(([name, quantity]) => ({ name, quantity }));
  });

  lpAffiliationList = computed(() => {
    const data = this.dashboardData();
    if (!data || !data.affiliations) return [];
    return Object.entries(data.affiliations.loyaltyPoints).map(([name, quantity]) => ({ name, quantity }));
  });

  /** Bilder und Bezeichnungen stehen als Tabellen in dashboard-icons.ts. */
  getMilitiaIconUrl(name: string): string {
    return MILITIA_ICONS[name] ?? FALLBACK_ICON;
  }

  getLpIconUrl(name: string): string {
    return LOYALTY_ICONS[name] ?? FALLBACK_ICON;
  }

  getLpDisplayName(name: string): string {
    return LOYALTY_LABELS[name] ?? name;
  }

  getShipIconUrl(groupName: string): string {
    return SHIP_CLASS_ICONS[groupName] ?? FALLBACK_ICON;
  }

  ngOnInit() {
    this.http.get<DashboardDto>(`${environment.apiUrl}/dashboard`).subscribe({
      next: (data) => this.dashboardData.set(data),
      error: (err) => console.error('Fehler beim Laden des Dashboards', err)
    });
  }

}
