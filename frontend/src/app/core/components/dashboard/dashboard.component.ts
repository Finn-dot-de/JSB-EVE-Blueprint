import {Component, OnInit, inject, signal, computed} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

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

  // Liefert die originalen Bildpfade deiner Vorlage
  getMilitiaIconUrl(name: string): string {
    switch (name) {
      case 'Amarr': return 'https://images.evetech.net/corporations/500003/logo?size=128';
      case 'Gallente': return 'https://images.evetech.net/corporations/500004/logo?size=128';
      case 'Minmatar': return 'https://images.evetech.net/corporations/500002/logo?size=128';
      case 'Caldari': return 'https://images.evetech.net/corporations/500001/logo?size=128';
      case 'Angel': return 'https://images.evetech.net/corporations/500011/logo?size=128';
      case 'Guristas': return 'https://images.evetech.net/corporations/500010/logo?size=128';
      default: return 'assets/fallback.png';
    }
  }

  // Liefert die originalen Bildpfade für die LPs deiner Vorlage
  getLpIconUrl(name: string): string {
    switch (name) {
      case 'Total': return 'data:image/png;base64,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'; // Cross-Swords Base64
      case 'CONCORD': return 'https://images.evetech.net/corporations/1000125/logo?size=128';
      case 'FederalAdmin': return 'https://images.evetech.net/corporations/1000119/logo?size=128';
      case 'BloodRaiders': return 'https://images.evetech.net/corporations/1000134/logo?size=128';
      case 'FreedomExtension': return 'https://images.evetech.net/corporations/1000061/logo?size=128';
      default: return 'assets/fallback.png';
    }
  }

  // Für die Lesbarkeit der LP-Namen im Tooltip
  getLpDisplayName(name: string): string {
    switch (name) {
      case 'Total': return 'Total Loyalty Points';
      case 'CONCORD': return 'CONCORD';
      case 'FederalAdmin': return 'Federal Administration';
      case 'BloodRaiders': return 'Blood Raiders';
      case 'FreedomExtension': return 'Freedom Extension';
      default: return name;
    }
  }

  // --- Automatische Kategorisierung der Schiffe für die UI ---
  getShipIconUrl(groupName: string): string {
    switch (groupName) {
      // Subcapitals
      case 'Frigate': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAJxJREFUeNpiYBgFo2AUjIKhDP7//z8fhAfSchiYPyCWP3369DkI09URyJbLycl1qKioFAC55+niCHTLgUIZQMwBFBKguSNwWY4kTztHELKcEkcwEmM5kEoAsXfs2HHi8+fPP93c3E7w8/P/wKGFH4gLoOwFjIyMidTKauSC+RSFABAEALEBhbF4AYg3jBbdo2AUjIJRMAoGJQAIMABD/+3i1WyW1AAAAABJRU5ErkJggg==';
      case 'Destroyer': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAL1JREFUeNpiYBgFo2AUjIKhDP7//z8fhAfSchiYPyCWP3369DkI09URyJbLycl1qKioFAC55+niCHTLgUIZQMwBFBKguSNwWY4kTztHELKcEkcwEmM5kEoAsXfs2HHi8+fPP93c3E7w8/P/wKGFH4gLoOwFjIyMidTKauSC+RSFABAEALEBhbF4AYg3DMrSlJFAFOyniiWMjI645FgIab59+7YCFdwASsQLSHYA1OUJo9XuKBgFo2BYA4AAAwDuOPZAKdxOvgAAAABJRU5ErkJggg==';
      case 'Cruiser': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAMVJREFUeNpiYBgFo2AUUAD+//8/H4QH0nIYmD8glj99+vQ5CNPVEciWy8nJdaioqBQAuefp4gh0y4FCGUDMARQSoLkjcFmOJE87RxCynKaOINZymjiCVMup7ghyLMfmCCA3AJc6JkIGHT169OajR48eAJkLgPgHsQ5gZGT8AKQ2QrkGZDsACm6QYjkpgIlhgMGoA1gIKTAwMJA/c+ZMgrGxsQMZ5ttT7ABVVVUFIKUwICEAzEqMQKphtOk1CkbBKKAlAAgwADz48ZFJ3SP6AAAAAElFTkSuQmCC';
      case 'Battlecruiser': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAOFJREFUeNpiYBgFo2AUUAD+//8/H4QH0nIYmD8glj99+vQ5CNPVEciWy8nJdaioqBQAuefp4gh0y4FCGUDMARQSoLkjcFmOJE87RxCynKaOINZymjiCVMup7ghyLMfmCCA3AJc6JkIGHT169OajR48eAJkLgPgHsQ5gZGT8AKQ2QrkGZDsACm6QYjkpgIlhgMGoA1gIKTAwMJA/c+ZMgrGxsQMZ5ttT7ABVVVUFIKUwICEAzEqMQKphWLeqGAmUZvupYgkjoyPZaeD27dvUiP8EaElKchpwhGoeBaOAZgAgwADRIPnv6gjUcQAAAABJRU5ErkJggg==';
      case 'Battleship': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAATVJREFUeNpiYBgFo4AC8P////kgPJCWw8D8AbH86dOnz0GYro5AtlxOTq5DRUWlAMg9TxdHoFsOFMoAYg6gkADNHYHLciR52jmCkOU0dQSxltPEEaRaTnVHELIcapEAIUcAuQG47GAi5IijR4/efPTo0QMgcwEQ/0C2AEjtB2FsjmBkZPwApDZCuQZkOwAKbuCw3ACK9+MKCUKAiYxogVsODJ0LIEyJI1gosdzGxgYUxBeA4v5AOgHqCEdo8FM3BHBZDsQbgBYmQtMIySFBlAOMjY1xWo6U6Mh2BN5suH379uOwGu/IkSPngcIN+LIVUjUNyoL90GzYQLYDYIAYy3G0FSh3ACmWY3MERQ4gx3J0R+BzACMhA4CJ6yF6giPVEVAzGshNiwEMlANqmDEKRsEwBQABBgAOibaZR7OUTgAAAABJRU5ErkJggg==';

      // Capitals
      case 'Carrier': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAPZJREFUeNpiYBgFFID////PB+GBtBwG5g+I5U+fPn0OwnR1BLLlcnJyHSoqKgVA7nm6OALdcqBQBhBzAIUEaO4IXJYjydPOEYQsp6kjiLWcJo4g1XKqOoJcy6niCEotp9gR1LAcmyOA3AB0eSZcGo8ePXrz0aNHD4DMBUD8g1wHMDIyfgBSG6FcA6IdAAU3KLGcGIDTAdbW1uoWFhYcVMjGAkDKn9ya7jzUAIrj/8iRI+eBQg10cwQOywPoEhJUsZxcR1DVclIdQRPLiXUETS0n5Ai6WI7LEXS1HIcj6Gs5tmY53S1HdsSAWY4EAgbS8lFAMQAIMACyRj6cz5mg0AAAAABJRU5ErkJggg==';
      case 'Force Auxiliary': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAARtJREFUeNpiYBgFRIL////P/088mE+suSzEWg6kEp49e/bi6NGjN/Gptba2VpeSkkoA6mFgZGRMpJrPnz59+lxOTq4DKJQBxA64sIWFhQdQ+XlSQ4IUyzmI0CdAsSPItZwqjqDUcoocQS3LiXUEEz7NbGxsL4DUAiD+QY2sDMxBBkAqgJQ8D3K5ADV8f+TIkfNAoQaiHEANR1BkOaWOoIrl5DqCqpaT6ghyLGcixSHAukBCVVU1gYRseQGIN9C1OKZKMTxaF4z4usBgQOsCNBcPTF1ADUdQXCJS4giqFcfkOIJUyxmJMRBI7YcmTlDRupGAFn+QWmCCu2BjY7ORWsUxckgQBKQEOyMJ7gCVBaCKSIJuFdEooAcACDAAVWOwQScYFDwAAAAASUVORK5CYII=';
      case 'Dreadnought': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAARxJREFUeNpiYBgFo4AC8P////kgPJCWw8D8AbH86dOnz0GYro5AtlxOTq5DRUWlAMg9TxdHoFsOFMoAYg6gkADNHYHLciR52jmCkOU0dQSxltPEEaRaTlVHkGs5VRxBqeUUO4IalmNzBJAbgC7PhEvj0aNHbz569OgBkLkAiH+Q6wBGRsYPQGojlGtAtAOg4AYllhMDmBgGGIw6gAWXhIGBgfyZM2cSjI2NHdCkJkATFtYUD6QKsEjZk5p18IHzUIvw5XmsAKisgagQAPqQEZviI0eO+FtbW4Oy0n6geY6wkIA6aD8o4IDZ94KNjc1GejTFzkN9Dfc50IHnoQ4PoFd78DzdLcfWKKW75ciOGDDLkUDAQFo+CigGAAEGAKzczqNJim5wAAAAAElFTkSuQmCC';
      case 'Supercarrier': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAXlJREFUeNrsl7FugzAQQKMOKGPGTigDH+CxY8aO/gQm1DFjNjpWYsjYbskndOMHQF2QMlZiyYTSCSQG2o2eJbuyEOCYs8PSk04oPuT3wBgui8V/IKJt2wPLOeEiDrPAi6K4sLyphAx3XffF87wt/DzdRKILh6EnyCUMraxLDMGluj0JFdyqxLVwKxK6cKMSU+FGJLBwHYk71SSO43zB4Qj5g1nONE0JHOiUVy27ghXm6pMkOcHQs5YARsIIfKqEUbiuhDF4H0glMQYXNR2BVkfiGjir8e2sdcvPkGRMQgEnfA65pr3ulUJiDF6hnoe6rl8lic1QS9YD3wh4HMcfqJ2QZdlO6v38rkQP3BcnR1H0zmuP2G3oD0lw8BicGOkL4E7Qbwg+/75Hcs8K7JwgCI5G4SLyPH8Qayt/3cTzwOCU0jcY2kGubfWGpGmav1ZcwMuyrCT4vdXuOAzDNUh8ivWW+oatdXj37YdtWtAS7I/JLHAplhj4rwADAAc8Lmsu6N7iAAAAAElFTkSuQmCC';
      case 'Titan': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAUhJREFUeNrslz0OgjAYhqmTo6MTEwcwYeUOduEAbo4egdHNI+DMpFNHHSCOcAUHorfArwlNKukP9gcXvuSLUJs+T4q+QBDMZVFd1+W0/wlnlf8F3rbti/akEjw8DMNjFEUHOK0nkRjCYWgPvYShlXcJEbwoijWF99/7k1DA6779SWjgrPxI6OBlWda0vUno4DCWQWMuE4QSdI6xAEDvKrggmHiJrBfIZIzFGJE4jt9pmhI43FRV1SRJcoXjBtbesi1GCO3g40znQN+YhO1voCOEPFjaSbb96zoPduKk2wGtACsRXBbFAzl7ARlcFcW8hJWACq6LYjbfRiBXwcdEMbeGcWEHUYznKJ6j2EoAohir4DIJJwKyKB7xtOwvin98ZHcbxSbvDc6i2PRvrBJAugXgPv+k937oi2mW9GsYxzEO7MvFGn7qI8AAjvv49jSDa0QAAAAASUVORK5CYII=';

      // Industrials
      case 'Mining': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAANxJREFUeNpiYBgFo2AUjAIiwf///+f/Jx7MJ9ZcFmItB1IJz549e3H06NGb+NRaW1urS0lJJQD1MDAyMiZSzedPnz59Licn1wEUygBiB1zYwsLCA6j8PKkhQYrlHEToE6DYEeRaTjVHUGI5NkcAuQHY1DDhMwCU4B49evQAyFwAxD9IdQAwEX4AUhuhXAOSHQAFN8ixnFjANNDly6gDBtwBjPiy4fv37z/8/fv3gYiIyAcK7FAAYWCOaATSDSTVBYKCggK4sg+1AAuePMyIzcWjYBSMglEw7ABAgAEAnPm+J1RtnyoAAAAASUVORK5CYII=';
      case 'Hauler': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAPhJREFUeNpiYBgFo2AUEAn+//8//z/xYD6x5rIQazmQSnj27NmLo0eP3sSn1traWl1KSioBqIeBkZExkWo+f/r06XM5ObkOoFAGEDvgwhYWFh5A5edJDQlSLOcgQp8AxY4g13KqOYISy7E5AsgNwKaGCZ8BoAT36NGjB0DmAiD+QaoDgInwA5DaCOUakOwAKLhBjuXEAqaBLl9GHTDgDmDElw3fv3//4e/fvw9EREQ+UGCHAggDc0QjkG4gqS4QFBQUwJV9qAVY8ORhRmwuHnYAXxrYT1WLGBkdSW4P3L59W4GKbkiAFulEpwFHqKZRMApGwfAGAAEGAJy+xoU1pcSFAAAAAElFTkSuQmCC';
      case 'Industrial Command Ship': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAUhJREFUeNpiYBgFo2AUEAD///+f/598MJ+Q+SyELAdSCc+ePXtx9OjRm6Q43NraWl1KSioBaAYDIyNjIskOQLbc0tJy4aNHjx4A+TeIdYCFhQXH8ePH20FmEHIEzmB/+vTpczk5uQ6gUAYQc5ARfQJAfJ7Y6KCq5WQ7gpqWY3MEkBuALMeETQMowUHjfAEQ/6DUAcD4/wCkNkK5BgQdAAU3qGE5IcA00OXMqANYiEnBQKqATPMnQBMg2UUxyPL96CmXBOAPNMMRnyNwOsDY2BhuOTBbXrCxsdlIis1HjhzxB9YHIIfvBzmClELj//bt24+DCiMQG2jQeaBwA3oBQmJNCiqE+qEFUQNBB8AAJZbjqs6JdgA1LMfmCKIcQE3L0R2B7gBGbAqBqfYhkHkBiDdQu3UFNbuBkNoAGpY9AQyjYBSMAiQAEGAA489NnZ/6iiAAAAAASUVORK5CYII=';
      case 'Capital Industrial': return 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAGXRFWHRTb2Z0d2FyZQBBZG9iZSBJbWFnZVJlYWR5ccllPAAAAZ9JREFUeNrsV7FugzAQhQihjJkZWLtGYgHB0G7dwifQrWM/IVu7tX9A+gfNxJgFxBjGdKuQoBk7dqPn1qmQhWufTduFkyzHoNx7tu/eHYYxmcC6rks7dUtF/i0ROExJ27bHoiieMcTDMDxzHCcBH4ZpmldoAn3wIAge67p+gfVBloDv+/OyLG+JDxEJ7rE3TfPquu4dPLqGMVe4vgWMvex1jAquTGJM8CESsIz772ZDfyABR+98A+NdlwDc/xtMW7pcCglQO4wBLrLZf+vMRMCSiWCYbhT9P9AAVJZiAr5jIxdhK/Bx8RMJLgHP877BIS2rKIq2GOQ8z1dQDwjxHSGBEY0uy7KSiBH5DY728HjNCgiykhIRuqdCtBYSOJkOOK+cSxNgwZF9QcojwRKwOFJ8uvMKxhOmLxjqA8j8hW0kUkc2tHOmQJ3zBvQBl7zq1/MttFinNAtKcIwKHtXSLNsHSEmxbdtHndIMcbNUyiQmjxcqu9dOZSwJLLgpS4KmUNXrbLj6z8j3Zyr/6QcK5thNJI8YURnH2flkv20fAgwA/mRRRowKgjEAAAAASUVORK5CYII=';

      // --- NOTABLE ---
      case 'Skill Injector': return 'https://images.evetech.net/types/40520/icon?size=32'; // Large Skill Injector
      case 'Skill Extractor': return 'https://images.evetech.net/types/40519/icon?size=32'; // Extractor

      // --- STRUCTURES ---
      case 'Citadel': return 'https://images.evetech.net/types/35834/icon?size=32'; // Keepstar
      case 'Refinery': return 'https://images.evetech.net/types/35836/render?size=32'; // Tatara
      case 'Engineering Complex': return 'https://images.evetech.net/types/35825/icon?size=32'; // Raitaru

      default: return 'assets/fallback.png';
    }
  }

  formatShortNumber(value: number | undefined): string {
    if (value === undefined || value === null) return '0';

    if (value >= 1e12) {
      return (value / 1e12).toFixed(2) + 'T'; // Trillionen (Tera)
    }
    if (value >= 1e9) {
      return (value / 1e9).toFixed(2) + 'B'; // Milliarden (Billions)
    }
    if (value >= 1e6) {
      return (value / 1e6).toFixed(2) + 'M'; // Millionen (Millions)
    }
    if (value >= 1e3) {
      return (value / 1e3).toFixed(2) + 'K'; // Tausend (Kilo)
    }

    return value.toFixed(0);
  }

  ngOnInit() {
    this.http.get<DashboardDto>('http://localhost:8080/api/dashboard').subscribe({
      next: (data) => this.dashboardData.set(data),
      error: (err) => console.error('Fehler beim Laden des Dashboards', err)
    });
  }
}
