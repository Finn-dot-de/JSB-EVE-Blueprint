import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { environment } from '../../../environments/environment';

import { CharlinkComponent } from './charlink/charlink.component';
import { ConfirmComponent } from './confirm/confirm.component';
import { DiscordAdminComponent } from './discord-admin/discord-admin.component';
import { FleetJoinComponent } from './fleet-join/fleet-join.component';
import { HomeComponent } from './home/home.component';
import { NavbarComponent } from './navbar/navbar.component';
import { ServicesComponent } from './services/services.component';
import { SidebarComponent } from './sidebar/sidebar.component';
import { ToastComponent } from './toast/toast.component';

import { AuthService } from '../services/auth.service';
import { CharacterService } from '../services/character.service';
import { ConfirmService } from '../services/confirm.service';
import {
  DiscordCharacterAudit,
  DiscordRoleAudit,
  DiscordRollenBefund,
  DiscordService,
  DiscordSyncErgebnis,
} from '../services/discord.service';
import { FleetService } from '../services/fleet.service';
import { ThemeService } from '../services/theme.service';
import { ToastService } from '../services/toast.service';

/** Baut eine Komponente ohne Template-Rendering - geprüft wird ihre Logik. */
function build<T>(factory: () => T): T {
  return TestBed.runInInjectionContext(factory);
}

describe('SidebarComponent', () => {
  let httpMock: HttpTestingController;
  let component: SidebarComponent;
  let toastService: { error: ReturnType<typeof vi.fn> };

  const navUrl = `${environment.apiUrl}/navigation`;

  /** Ein einzelner Punkt der obersten Ebene, wie der Server ihn liefert. */
  function entry(label: string, url = '/seite', external = false) {
    return { label, icon: 'fa-solid fa-x', url, external, children: [] };
  }

  /** Ein Register mit seinen Punkten. */
  function folder(label: string, childLabels: string[]) {
    return {
      label,
      icon: 'fa-solid fa-folder',
      url: null,
      external: false,
      children: childLabels.map((child) => ({
        label: child,
        url: '/seite',
        icon: 'fa-solid fa-x',
        external: false,
      })),
    };
  }

  beforeEach(() => {
    toastService = { error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ToastService, useValue: toastService },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    component = build(() => new SidebarComponent());
  });

  afterEach(() => httpMock.verify());

  it('lädt die Menüpunkte beim Start', () => {
    component.ngOnInit();

    httpMock.expectOne(navUrl).flush([entry('Dashboard')]);

    expect(component.menuItems()).toHaveLength(1);
    expect(component.menuItems()[0].name).toBe('Dashboard');
  });

  it('übernimmt die Reihenfolge des Servers unverändert', () => {
    // Hier stand früher eine Blaupause aus fest verdrahteten Namen. Sortiert
    // wird jetzt in der Datenbank, damit die Verwaltung überhaupt etwas
    // bewirken kann - die Leiste darf sie also nicht mehr umsortieren.
    component.buildMenu([entry('CharLink'), entry('Dashboard'), entry('Services')]);

    expect(component.menuItems().map((item) => item.name)).toEqual([
      'CharLink',
      'Dashboard',
      'Services',
    ]);
  });

  it('macht aus einem Register einen zugeklappten Ordner', () => {
    component.buildMenu([folder('CorpTools', ['Mining', 'Assets'])]);

    expect(component.menuItems()).toHaveLength(1);
    expect(component.menuItems()[0].name).toBe('CorpTools');
    expect(component.menuItems()[0].children).toHaveLength(2);
    expect(component.menuItems()[0].expanded).toBe(false);
  });

  it('übernimmt die Kennzeichnung externer Ziele', () => {
    component.buildMenu([entry('Wiki', 'https://wiki.example.org', true)]);

    expect(component.menuItems()[0].isExternal).toBe(true);
    expect(component.menuItems()[0].route).toBe('https://wiki.example.org');
  });

  it('klappt einen Ordner auf und wieder zu', () => {
    component.buildMenu([folder('CorpTools', ['Mining'])]);
    const opened = component.menuItems()[0];

    component.toggleMenu(opened);
    expect(opened.expanded).toBe(true);

    component.toggleMenu(opened);
    expect(opened.expanded).toBe(false);
  });

  it('lässt einen einfachen Menüpunkt beim Klick unverändert', () => {
    const item = { name: 'Dashboard', icon: '', route: '/dashboard' };

    component.toggleMenu(item);

    expect(item).not.toHaveProperty('expanded', true);
  });

  it('meldet einen Fehlschlag, statt eine leere Leiste zu zeigen', () => {
    component.ngOnInit();

    httpMock.expectOne(navUrl).flush(null, { status: 500, statusText: 'Server Error' });

    expect(component.menuItems()).toEqual([]);
    expect(toastService.error).toHaveBeenCalled();
  });
});

describe('ServicesComponent', () => {
  let component: ServicesComponent;
  let discordService: { getStatus: ReturnType<typeof vi.fn>; disconnect: ReturnType<typeof vi.fn> };
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let toastService: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let router: { navigate: ReturnType<typeof vi.fn> };
  let queryParams: Subject<Record<string, string | null>>;

  beforeEach(() => {
    queryParams = new Subject();
    discordService = {
      getStatus: vi.fn().mockReturnValue(of({ connected: false })),
      disconnect: vi.fn().mockReturnValue(of(null)),
    };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };
    toastService = { success: vi.fn(), error: vi.fn() };
    router = { navigate: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: DiscordService, useValue: discordService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: ToastService, useValue: toastService },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { queryParams } },
      ],
    });
    component = build(() => new ServicesComponent());
  });

  it('übernimmt den Verbindungsstatus beim Start', () => {
    discordService.getStatus.mockReturnValue(of({ connected: true }));

    component.ngOnInit();

    expect(component.isDiscordConnected()).toBe(true);
    expect(component.isLoading()).toBe(false);
  });

  it('räumt den Erfolgs-Parameter aus der Adresse', () => {
    // Sonst bliebe ?discord=success beim Neuladen stehen.
    component.ngOnInit();
    queryParams.next({ discord: 'success' });

    expect(router.navigate).toHaveBeenCalled();
  });

  it('meldet einen Fehlschlag der Verknüpfung', () => {
    component.ngOnInit();
    queryParams.next({ discord: 'error' });

    expect(toastService.error).toHaveBeenCalled();
  });

  it('meldet einen Fehlschlag beim Laden des Status', () => {
    discordService.getStatus.mockReturnValue(throwError(() => new Error('kaputt')));

    component.ngOnInit();

    expect(component.isLoading()).toBe(false);
    expect(toastService.error).toHaveBeenCalled();
  });

  it('trennt die Verbindung erst nach Rückfrage', async () => {
    component.isDiscordConnected.set(true);

    await component.disconnectDiscord();

    expect(confirmService.ask).toHaveBeenCalled();
    expect(discordService.disconnect).toHaveBeenCalled();
    expect(component.isDiscordConnected()).toBe(false);
    expect(toastService.success).toHaveBeenCalled();
  });

  it('trennt nichts, wenn die Rückfrage verneint wird', async () => {
    confirmService.ask.mockResolvedValue(false);
    component.isDiscordConnected.set(true);

    await component.disconnectDiscord();

    expect(discordService.disconnect).not.toHaveBeenCalled();
    expect(component.isDiscordConnected()).toBe(true);
  });

  it('meldet einen Fehlschlag beim Trennen', async () => {
    discordService.disconnect.mockReturnValue(throwError(() => new Error('kaputt')));

    await component.disconnectDiscord();

    expect(toastService.error).toHaveBeenCalled();
    expect(component.isLoading()).toBe(false);
  });
});

describe('CharlinkComponent', () => {
  let component: CharlinkComponent;
  let charService: {
    getMyAlts: ReturnType<typeof vi.fn>;
    setMainCharacter: ReturnType<typeof vi.fn>;
  };
  let authService: { hasAnyRole: ReturnType<typeof vi.fn>; login: ReturnType<typeof vi.fn> };
  let confirmService: { ask: ReturnType<typeof vi.fn> };
  let toastService: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  const alt = { id: 1001, name: 'Alt', portraitUrl: '', isMain: false };
  const main = { id: 1000, name: 'Main', portraitUrl: '', isMain: true };

  beforeEach(() => {
    charService = {
      getMyAlts: vi.fn().mockReturnValue(of([alt, main])),
      setMainCharacter: vi.fn().mockReturnValue(of(null)),
    };
    authService = { hasAnyRole: vi.fn().mockReturnValue(false), login: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };
    toastService = { success: vi.fn(), error: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: CharacterService, useValue: charService },
        { provide: AuthService, useValue: authService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: ToastService, useValue: toastService },
      ],
    });
    component = build(() => new CharlinkComponent());
  });

  it('stellt den Main an die erste Stelle', () => {
    component.ngOnInit();

    expect(component.characters()[0].isMain).toBe(true);
    expect(component.loading()).toBe(false);
  });

  it('bleibt bei einem Fehler bedienbar', () => {
    charService.getMyAlts.mockReturnValue(throwError(() => new Error('kaputt')));

    component.ngOnInit();

    expect(component.loading()).toBe(false);
  });

  it('meldet die Führungsrolle für die Oberfläche', () => {
    authService.hasAnyRole.mockReturnValue(true);

    expect(component.isLeadership).toBe(true);
    expect(authService.hasAnyRole).toHaveBeenCalledWith([
      'ROLE_CEO',
      'ROLE_DIRECTOR',
      'ROLE_IT_ADMIN',
    ]);
  });

  it('startet für einen weiteren Charakter die Anmeldung', () => {
    component.addAlt();

    expect(authService.login).toHaveBeenCalled();
  });

  it('wechselt den Main erst nach Rückfrage', async () => {
    await component.makeMain(alt);

    expect(confirmService.ask).toHaveBeenCalled();
    expect(charService.setMainCharacter).toHaveBeenCalledWith(1001);
    expect(toastService.success).toHaveBeenCalled();
  });

  it('wechselt nichts, wenn die Rückfrage verneint wird', async () => {
    confirmService.ask.mockResolvedValue(false);

    await component.makeMain(alt);

    expect(charService.setMainCharacter).not.toHaveBeenCalled();
  });

  it('zeigt die Meldung des Servers, wenn der Wechsel scheitert', async () => {
    charService.setMainCharacter.mockReturnValue(
      throwError(() => ({ error: { message: 'Gehört nicht zu dir' } })),
    );

    await component.makeMain(alt);

    expect(toastService.error).toHaveBeenCalledWith('Gehört nicht zu dir');
    expect(component.loading()).toBe(false);
  });
});

// Die RolesComponent steht in einer eigenen Spec - sie verwaltet inzwischen
// auch Rollen und ist für diese Sammlung zu umfangreich geworden.

describe('DiscordAdminComponent', () => {
  let component: DiscordAdminComponent;
  let discordService: {
    getMappings: ReturnType<typeof vi.fn>;
    saveMapping: ReturnType<typeof vi.fn>;
    getAudit: ReturnType<typeof vi.fn>;
    getCharacterAudit: ReturnType<typeof vi.fn>;
    stosseAbgleichAn: ReturnType<typeof vi.fn>;
  };
  let toastService: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  const mapping = { authRole: 'ROLE_USER', discordRoleId: '123', description: 'Mitglied' };

  /** Eine Zeile der Gegenüberstellung - voreingestellt eine Rolle, die sitzt. */
  function rollenZeile(overrides: Partial<DiscordRollenBefund> = {}): DiscordRollenBefund {
    return {
      authRolle: 'ROLE_USER',
      discordRoleId: '123',
      discordRoleName: 'Member',
      zustand: 'VORHANDEN',
      ursache: null,
      grund: null,
      ...overrides,
    };
  }

  /** Ein Prüfergebnis, wie das Backend es liefert - alles in Ordnung. */
  function befund(overrides: Partial<DiscordRoleAudit> = {}): DiscordRoleAudit {
    return {
      discordUserId: '1424800550347735184',
      mainCharacterId: 2118431553,
      mainCharacterName: 'Comander-Video',
      charaktere: [{ characterId: 2118431553, name: 'Comander-Video', sollRollen: ['123'] }],
      rollen: [rollenZeile()],
      weitereDiscordRollen: [],
      fehlendeRollen: [],
      ueberzaehligeRollen: [],
      pruefbar: true,
      hinweis: null,
      sollUneinig: false,
      ...overrides,
    };
  }

  /** Was der angestoßene Abgleich zurückmeldet. */
  function ergebnis(overrides: Partial<DiscordSyncErgebnis> = {}): DiscordSyncErgebnis {
    return {
      characterId: 2118431553,
      characterName: 'Comander-Video',
      mainCharacterId: 2118431553,
      mainCharacterName: 'Comander-Video',
      discordUserId: '1424800550347735184',
      ausgefuehrt: true,
      hinweis: null,
      rollen: [],
      ...overrides,
    };
  }

  /** Der Stand eines einzelnen Charakters, wie ihn die Rücksicht nach dem Abgleich liefert. */
  function charakterStand(overrides: Partial<DiscordCharacterAudit> = {}): DiscordCharacterAudit {
    return {
      characterId: 2118431553,
      characterName: 'Comander-Video',
      mainCharacterId: 2118431553,
      mainCharacterName: 'Comander-Video',
      discordUserId: '1424800550347735184',
      verknuepft: true,
      pruefbar: true,
      hinweis: null,
      rollen: [rollenZeile()],
      weitereDiscordRollen: [],
      sollUneinig: false,
      ...overrides,
    };
  }

  beforeEach(() => {
    discordService = {
      getMappings: vi.fn().mockReturnValue(of([mapping])),
      saveMapping: vi.fn().mockReturnValue(of(null)),
      getAudit: vi.fn().mockReturnValue(of([])),
      getCharacterAudit: vi.fn().mockReturnValue(of(charakterStand())),
      stosseAbgleichAn: vi.fn().mockReturnValue(of(ergebnis())),
    };
    toastService = { success: vi.fn(), error: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        { provide: DiscordService, useValue: discordService },
        { provide: ToastService, useValue: toastService },
      ],
    });
    component = build(() => new DiscordAdminComponent());
  });

  it('lädt die Zuordnungen beim Start', () => {
    component.ngOnInit();

    expect(component.mappings()).toHaveLength(1);
    expect(component.loading()).toBe(false);
  });

  it('meldet fehlende Rechte statt still leer zu bleiben', () => {
    discordService.getMappings.mockReturnValue(throwError(() => new Error('403')));

    component.ngOnInit();

    expect(toastService.error).toHaveBeenCalled();
    expect(component.loading()).toBe(false);
  });

  it('bestätigt eine gespeicherte Zuordnung', () => {
    component.saveMapping(mapping);

    expect(discordService.saveMapping).toHaveBeenCalledWith(mapping);
    expect(toastService.success).toHaveBeenCalled();
  });

  it('meldet einen Fehlschlag beim Speichern', () => {
    discordService.saveMapping.mockReturnValue(throwError(() => new Error('kaputt')));

    component.saveMapping(mapping);

    expect(toastService.error).toHaveBeenCalled();
  });

  // ========================================================
  // Soll-Ist-Prüfung
  // ========================================================

  it('prüft nicht von selbst - ungeprüft ist nicht dasselbe wie fehlerfrei', () => {
    component.ngOnInit();

    expect(discordService.getAudit).not.toHaveBeenCalled();
    // null statt leerer Liste: Sonst sähe die Seite beim Öffnen so aus, als
    // wäre geprüft worden und alles in Ordnung.
    expect(component.audit()).toBeNull();
    expect(component.ohneBefund()).toBe(false);
    // Ungeprüft behauptet keine der Listen etwas - weder einen Befund noch
    // einen unauffälligen Charakter.
    expect(component.konflikte()).toEqual([]);
    expect(component.charaktere()).toEqual([]);
    expect(component.auffaellige()).toEqual([]);
    expect(component.unauffaellige()).toBe(0);
  });

  // Der Fall, den der Nutzer beschreibt: Member und Support FC sitzen, Cap
  // Azubi fehlt, und daneben trägt das Konto eine handvergebene Rolle.
  it('stellt je Charakter Auth-Rolle und Discord-Rolle gegenüber', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          rollen: [
            rollenZeile(),
            rollenZeile({ authRolle: 'A38', discordRoleId: '456', discordRoleName: 'Support FC' }),
            rollenZeile({
              authRolle: 'CAP_AZUBI',
              discordRoleId: '789',
              discordRoleName: 'Cap Azubi',
              zustand: 'FEHLT',
              ursache: 'ABGLEICH_STEHT_AUS',
              grund: 'Der Abgleich hat dieses Konto noch nicht angefasst.',
            }),
          ],
          weitereDiscordRollen: [
            { discordRoleId: '999', name: 'Marauders Associated', verwaltet: false },
          ],
        }),
      ]),
    );

    component.pruefen();

    const [tom] = component.charaktere();
    expect(tom.characterName).toBe('Comander-Video');
    expect(tom.rollen).toHaveLength(3);
    expect(tom.rollen[2].zustand).toBe('FEHLT');
    expect(tom.rollen[2].discordRoleId).toBe('789');
    // Die Ursache steht an derselben Zeile - "fehlt" allein ist die halbe
    // Auskunft, gehandelt wird nach dem Grund.
    expect(component.ursacheKurz(tom.rollen[2].ursache)).toBe('Abgleich steht aus');
    expect(component.hatBefund(tom)).toBe(true);
  });

  // Eine von Hand vergebene Rolle ohne Zuordnung ist NIE ein Fehler. Genau
  // diese Verwechslung hat den Abgleich schon einmal dazu gebracht,
  // handvergebene Rollen abzuräumen.
  it('führt eine Rolle ohne Zuordnung als vorhanden, nicht als Befund', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          weitereDiscordRollen: [
            { discordRoleId: '999', name: 'Marauders Associated', verwaltet: false },
          ],
        }),
      ]),
    );

    component.pruefen();

    const [zeile] = component.charaktere();
    expect(zeile.weitereDiscordRollen).toHaveLength(1);
    expect(component.hatUeberzaehlige(zeile)).toBe(false);
    expect(component.hatBefund(zeile)).toBe(false);
    expect(component.ohneBefund()).toBe(true);
  });

  // Die andere Richtung: eine Rolle, die das Auth sehr wohl verwaltet, die
  // dieser Charakter aber nicht haben soll - hier stehen Rechte offen.
  it('meldet eine verwaltete Rolle, die dem Charakter nicht zusteht', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          weitereDiscordRollen: [{ discordRoleId: '456', name: 'Support FC', verwaltet: true }],
        }),
      ]),
    );

    component.pruefen();

    const [zeile] = component.charaktere();
    expect(component.hatUeberzaehlige(zeile)).toBe(true);
    expect(component.hatBefund(zeile)).toBe(true);
    expect(component.auffaellige()).toHaveLength(1);
  });

  it('führt unauffällige Charaktere nicht auf, sondern zählt sie', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund(),
        befund({
          discordUserId: '77',
          mainCharacterId: 90,
          mainCharacterName: 'Zweiter',
          charaktere: [{ characterId: 90, name: 'Zweiter', sollRollen: ['123'] }],
        }),
      ]),
    );

    component.pruefen();

    expect(component.charaktere()).toHaveLength(2);
    expect(component.auffaellige()).toHaveLength(0);
    expect(component.sichtbareCharaktere()).toHaveLength(0);
    expect(component.unauffaellige()).toBe(2);
    expect(component.ohneBefund()).toBe(true);
  });

  it('zeigt auf Wunsch auch die unauffälligen Charaktere', () => {
    discordService.getAudit.mockReturnValue(of([befund()]));
    component.pruefen();

    component.nurAuffaellige.set(false);

    expect(component.sichtbareCharaktere()).toHaveLength(1);
  });

  // Ein Konto, zwei Charaktere: Beide bekommen dieselben Zeilen, weil das Soll
  // am Main hängt. Zweimal zu rechnen hieße, zwei Wahrheiten über dieselbe
  // Person zu haben.
  it('gibt jedem Charakter eines Kontos dieselbe Gegenüberstellung', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          charaktere: [
            { characterId: 2118431553, name: 'Comander-Video', sollRollen: ['123'] },
            { characterId: 2123933054, name: 'Morpheus Revenant', sollRollen: ['123'] },
          ],
        }),
      ]),
    );

    component.pruefen();

    const zeilen = component.charaktere();
    expect(zeilen).toHaveLength(2);
    expect(zeilen.map((z) => z.characterName)).toEqual(['Comander-Video', 'Morpheus Revenant']);
    expect(zeilen[1].rollen).toEqual(zeilen[0].rollen);
    // Das Soll hängt am Main - ohne dessen Namen wäre die zweite Zeile nicht
    // zu verstehen.
    expect(zeilen[1].mainCharacterName).toBe('Comander-Video');
  });

  // Der wichtigste Fall der Seite: zwei Charaktere auf einem Discord-Konto mit
  // verschiedenen Soll-Rollen. Ohne eigene Hervorhebung liest sich das wie
  // eine gewöhnliche fehlende Rolle - und wer sie von Hand nachträgt, hat sie
  // bis zum nächsten Abgleich.
  it('hebt mehrere Charaktere mit verschiedenen Rollen an einem Konto hervor', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          sollUneinig: true,
          charaktere: [
            { characterId: 2118431553, name: 'Comander-Video', sollRollen: ['123'] },
            { characterId: 2123933054, name: 'Morpheus Revenant', sollRollen: [] },
          ],
        }),
      ]),
    );

    component.pruefen();

    expect(component.konflikte()).toHaveLength(1);
    expect(component.ohneBefund()).toBe(false);
    // Der Widerspruch gilt für beide Charaktere des Kontos, nicht nur für den Main.
    expect(component.auffaellige()).toHaveLength(2);
  });

  // 403 heißt "nicht feststellbar", niemals "fehlt". Am Server-Owner scheitert
  // das Lesen dauerhaft; als Rollenbefund gelesen wäre das der lauteste
  // Fehlalarm der Seite.
  it('trennt "nicht feststellbar" von "fehlt"', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          pruefbar: false,
          hinweis: 'Discord verweigert die Auskunft (403).',
          rollen: [
            rollenZeile({
              zustand: 'NICHT_FESTSTELLBAR',
              ursache: 'ZUGRIFF_VERWEIGERT',
              grund: 'Discord verweigert die Auskunft (403).',
            }),
          ],
        }),
      ]),
    );

    component.pruefen();

    const [zeile] = component.charaktere();
    expect(zeile.pruefbar).toBe(false);
    expect(component.zugriffVerweigert(zeile)).toBe(true);
    // Kein Befund über Rollen - aber auch nicht dieselbe Ruhe wie eine
    // bestandene Prüfung: Die Karte steht in der Liste.
    expect(component.hatBefund(zeile)).toBe(false);
    expect(component.auffaellige()).toHaveLength(1);
    expect(component.unauffaellige()).toBe(0);
    expect(component.ohneBefund()).toBe(false);
  });

  // Beim 403 gibt es genau zwei Ursachen, und beide kann der Leser prüfen. Bei
  // einem 404 sind es andere - der Text dazu gehört nicht an dieselbe Stelle.
  it('nennt den 403-Hinweis nicht bei einem Konto, das gar nicht auf dem Server ist', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          pruefbar: false,
          hinweis: 'Das Konto ist kein Mitglied des Servers mehr (404).',
          rollen: [
            rollenZeile({
              zustand: 'NICHT_FESTSTELLBAR',
              ursache: 'KONTO_NICHT_AUF_SERVER',
              grund: 'Das Konto ist kein Mitglied des Servers mehr (404).',
            }),
          ],
        }),
      ]),
    );

    component.pruefen();

    const [zeile] = component.charaktere();
    expect(component.zugriffVerweigert(zeile)).toBe(false);
    expect(component.ursacheKurz(zeile.rollen[0].ursache)).toBe('Nicht auf dem Server (404)');
  });

  it('benennt jede Ursache mit einem eigenen Etikett', () => {
    expect(component.ursacheKurz('KEIN_MAPPING')).toBe('Keine Zuordnung');
    expect(component.ursacheKurz('MAPPING_OHNE_ROLLEN_ID')).toBe('Zuordnung ohne Rollen-ID');
    expect(component.ursacheKurz('KEINE_VERKNUEPFUNG')).toBe('Kein Discord-Konto');
    expect(component.ursacheKurz('ROLLE_AUF_SERVER_UNBEKANNT')).toBe(
      'Rolle auf dem Server unbekannt',
    );
    expect(component.ursacheKurz('UNBEKANNT')).toBe('Ursache unbekannt');
    // Zu einer sitzenden Rolle gibt es keine Ursache - und keinen Text.
    expect(component.ursacheKurz(null)).toBe('');
    // Ein Wert, den diese Anzeige noch nicht kennt, steht roh da, statt zu
    // verschwinden: Eine leere Zelle sähe aus wie "kein Grund".
    expect(component.ursacheKurz('NEU_ERFUNDEN' as never)).toBe('NEU_ERFUNDEN');
  });

  it('zeigt den Namen der Auth-Rolle statt der Discord-Kennung', () => {
    component.ngOnInit();

    expect(component.rollenName('123')).toBe('ROLE_USER');
    // Ohne Zuordnung bleibt die rohe Kennung stehen - lieber eine Zahl, die
    // man in Discord nachschlagen kann, als gar keine Angabe.
    expect(component.rollenName('999')).toBe('999');
  });

  it('nennt den Main des Kontos', () => {
    const eintrag = befund({
      charaktere: [
        { characterId: 2118431553, name: 'Comander-Video', sollRollen: [] },
        { characterId: 2123933054, name: 'Morpheus Revenant', sollRollen: [] },
      ],
    });

    expect(component.istMain(eintrag, 2118431553)).toBe(true);
    expect(component.istMain(eintrag, 2123933054)).toBe(false);
  });

  // Ein Ergebnis von vor der Änderung beantwortet "hat es gewirkt?" falsch -
  // und zwar beruhigend falsch.
  it('weist das Ergebnis nach einer gespeicherten Zuordnung als veraltet aus', () => {
    component.pruefen();
    expect(component.auditVeraltet()).toBe(false);

    component.saveMapping(mapping);

    expect(component.auditVeraltet()).toBe(true);
  });

  it('markiert nichts als veraltet, solange nicht geprüft wurde', () => {
    component.saveMapping(mapping);

    expect(component.auditVeraltet()).toBe(false);
  });

  // Nach einem Fehlschlag steht in Discord noch dasselbe wie zur Zeit der
  // Prüfung - das Ergebnis gilt also weiter.
  it('lässt das Ergebnis nach einem misslungenen Speichern gelten', () => {
    component.pruefen();
    discordService.saveMapping.mockReturnValue(throwError(() => new Error('kaputt')));

    component.saveMapping(mapping);

    expect(component.auditVeraltet()).toBe(false);
  });

  it('behält das alte Ergebnis, wenn die Prüfung fehlschlägt', () => {
    discordService.getAudit.mockReturnValue(
      of([befund({ rollen: [rollenZeile({ zustand: 'FEHLT', ursache: 'KEIN_MAPPING' })] })]),
    );
    component.pruefen();
    component.saveMapping(mapping);

    discordService.getAudit.mockReturnValue(throwError(() => new Error('500')));
    component.pruefen();

    expect(toastService.error).toHaveBeenCalled();
    expect(component.auditLoading()).toBe(false);
    // Weiterhin sichtbar und weiterhin als veraltet markiert: Auf null gesetzt
    // sähe der Fehlschlag aus wie "nichts gefunden".
    expect(component.auffaellige()).toHaveLength(1);
    expect(component.auditVeraltet()).toBe(true);
  });

  it('löst keinen zweiten Aufruf aus, solange die Prüfung läuft', () => {
    discordService.getAudit.mockReturnValue(new Subject());

    component.pruefen();
    component.pruefen();

    // Jede Prüfung kostet einen Aufruf je Konto an eine Schnittstelle mit
    // Rate Limit - ein zweiter Klick darf sie nicht verdoppeln.
    expect(discordService.getAudit).toHaveBeenCalledTimes(1);
    expect(component.auditLoading()).toBe(true);
  });

  // ========================================================
  // Der angestoßene Abgleich
  // ========================================================

  it('meldet je Rolle, was der Abgleich bewirkt hat', () => {
    discordService.getAudit.mockReturnValue(of([befund()]));
    component.pruefen();
    discordService.stosseAbgleichAn.mockReturnValue(
      of(
        ergebnis({
          rollen: [
            { authRolle: 'CAP_AZUBI', discordRoleId: '789', aktion: 'VERGEBEN', erfolg: true, grund: null },
            {
              authRolle: null,
              discordRoleId: '456',
              aktion: 'ENTZOGEN',
              erfolg: false,
              grund: 'Discord verweigert (403): Die Bot-Rolle steht zu tief.',
            },
          ],
        }),
      ),
    );

    component.stosseAn(2118431553);

    const antwort = component.ergebnisFuer(2118431553);
    expect(antwort?.rollen).toHaveLength(2);
    // Der Grund gehört zur gescheiterten Rolle - "gescheitert" allein wäre
    // dieselbe Auskunft wie das Schweigen davor.
    expect(antwort?.rollen[1].grund).toContain('403');
    expect(component.aktionText(antwort!.rollen[0])).toBe('gesetzt');
    expect(component.aktionText(antwort!.rollen[1])).toBe('konnte nicht entzogen werden');
    expect(component.syncLaeuft()).toBeNull();
  });

  it('unterscheidet die vier Ausgänge einer angefassten Rolle', () => {
    const zeilen = [
      { authRolle: 'A', discordRoleId: '1', aktion: 'VERGEBEN', erfolg: true, grund: null },
      { authRolle: 'A', discordRoleId: '1', aktion: 'VERGEBEN', erfolg: false, grund: '403' },
      { authRolle: 'A', discordRoleId: '1', aktion: 'ENTZOGEN', erfolg: true, grund: null },
      { authRolle: 'A', discordRoleId: '1', aktion: 'ENTZOGEN', erfolg: false, grund: '403' },
    ] as const;

    expect(zeilen.map((z) => component.aktionText(z))).toEqual([
      'gesetzt',
      'konnte nicht gesetzt werden',
      'entzogen',
      'konnte nicht entzogen werden',
    ]);
  });

  // Nach einem wirksamen Abgleich zeigte die Zeile sonst dieselbe fehlende
  // Rolle wie vorher - das liest sich wie ein Fehlschlag, obwohl er gewirkt hat.
  it('holt den Stand des abgeglichenen Charakters nach', () => {
    discordService.getAudit.mockReturnValue(
      of([befund({ rollen: [rollenZeile({ zustand: 'FEHLT', ursache: 'ABGLEICH_STEHT_AUS' })] })]),
    );
    component.pruefen();
    expect(component.auffaellige()).toHaveLength(1);

    component.stosseAn(2118431553);

    expect(discordService.getCharacterAudit).toHaveBeenCalledWith(2118431553);
    // Der nachgeholte Stand überstimmt die Zeile aus der Prüfung.
    expect(component.charaktere()[0].rollen[0].zustand).toBe('VORHANDEN');
    expect(component.auffaellige()).toHaveLength(0);
  });

  it('holt nichts nach, wenn der Abgleich gar nicht erst hinausging', () => {
    discordService.stosseAbgleichAn.mockReturnValue(
      of(ergebnis({ ausgefuehrt: false, hinweis: 'Kein Discord-Konto verknüpft.' })),
    );

    component.stosseAn(2118431553);

    // In Discord hat sich nichts geändert - ein zweiter Aufruf brächte
    // dieselben Zahlen zurück.
    expect(discordService.getCharacterAudit).not.toHaveBeenCalled();
    expect(component.ergebnisFuer(2118431553)?.hinweis).toBe('Kein Discord-Konto verknüpft.');
  });

  // Der Abgleich hat unter den Geschwistern desselben Kontos weggeschrieben -
  // ihre Zeilen stammen noch aus der Prüfung von vorher.
  it('markiert das Ergebnis als veraltet, wenn mehrere Charaktere am Konto hängen', () => {
    discordService.getAudit.mockReturnValue(
      of([
        befund({
          charaktere: [
            { characterId: 2118431553, name: 'Comander-Video', sollRollen: ['123'] },
            { characterId: 2123933054, name: 'Morpheus Revenant', sollRollen: ['123'] },
          ],
        }),
      ]),
    );
    component.pruefen();

    component.stosseAn(2118431553);

    expect(component.auditVeraltet()).toBe(true);
  });

  it('lässt das Ergebnis gelten, wenn nur ein Charakter am Konto hängt', () => {
    discordService.getAudit.mockReturnValue(of([befund()]));
    component.pruefen();

    component.stosseAn(2118431553);

    expect(component.auditVeraltet()).toBe(false);
  });

  // Ohne Konto gibt es keine Geschwister, unter denen der Abgleich
  // weggeschrieben haben könnte - dann ist auch nichts veraltet.
  it('markiert nichts als veraltet, wenn zum Ergebnis kein Konto gehört', () => {
    discordService.getAudit.mockReturnValue(of([befund()]));
    component.pruefen();
    discordService.stosseAbgleichAn.mockReturnValue(of(ergebnis({ discordUserId: null })));

    component.stosseAn(2118431553);

    expect(component.auditVeraltet()).toBe(false);
  });

  it('weist die Zeile als veraltet aus, wenn der Stand nicht nachzuholen war', () => {
    discordService.getAudit.mockReturnValue(of([befund()]));
    component.pruefen();
    discordService.getCharacterAudit.mockReturnValue(throwError(() => new Error('500')));

    component.stosseAn(2118431553);

    // Das Ergebnis des Abgleichs bleibt die verlässliche Auskunft; die Zeile
    // darunter stammt weiterhin aus der Prüfung.
    expect(component.ergebnisFuer(2118431553)).not.toBeNull();
    expect(component.auditVeraltet()).toBe(true);
  });

  // Scheitert schon der Aufruf, gibt es kein Ergebnis - aber sehr wohl eine
  // Auskunft, und sie gehört an dieselbe Stelle.
  it('begründet einen Abgleich, der nicht einmal angestoßen werden konnte', () => {
    discordService.stosseAbgleichAn.mockReturnValue(throwError(() => ({ status: 403 })));

    component.stosseAn(2118431553);

    expect(component.fehlerFuer(2118431553)).toContain('403');
    expect(component.ergebnisFuer(2118431553)).toBeNull();
    expect(component.syncLaeuft()).toBeNull();
  });

  it('nennt einen unbekannten Charakter beim Namen statt einen Fehlercode zu zeigen', () => {
    discordService.stosseAbgleichAn.mockReturnValue(throwError(() => ({ status: 404 })));

    component.stosseAn(2118431553);

    expect(component.fehlerFuer(2118431553)).toContain('kennt das Auth nicht');
  });

  it('nennt auch einen Aufruf ohne erkennbaren Status', () => {
    discordService.stosseAbgleichAn.mockReturnValue(throwError(() => new Error('offline')));

    component.stosseAn(2118431553);

    expect(component.fehlerFuer(2118431553)).toContain('HTTP ?');
  });

  it('räumt einen alten Fehler weg, sobald der Abgleich erneut läuft', () => {
    discordService.stosseAbgleichAn.mockReturnValue(throwError(() => ({ status: 403 })));
    component.stosseAn(2118431553);
    expect(component.fehlerFuer(2118431553)).not.toBeNull();

    discordService.stosseAbgleichAn.mockReturnValue(of(ergebnis()));
    component.stosseAn(2118431553);

    expect(component.fehlerFuer(2118431553)).toBeNull();
  });

  it('stößt keinen zweiten Abgleich an, solange einer läuft', () => {
    discordService.stosseAbgleichAn.mockReturnValue(new Subject());

    component.stosseAn(2118431553);
    component.stosseAn(2123933054);

    // Der Abgleich schreibt in Discord. Zwei gleichzeitige Läufe über dasselbe
    // Konto überschrieben einander.
    expect(discordService.stosseAbgleichAn).toHaveBeenCalledTimes(1);
    expect(component.syncLaeuft()).toBe(2118431553);
  });

  // Neben frischen Zahlen behauptete ein altes Abgleichsergebnis einen Stand,
  // den gerade niemand mehr geprüft hat.
  it('räumt Abgleichsergebnisse und nachgeholte Stände bei einer neuen Prüfung weg', () => {
    discordService.getAudit.mockReturnValue(of([befund()]));
    component.pruefen();
    component.stosseAn(2118431553);
    expect(component.ergebnisFuer(2118431553)).not.toBeNull();

    component.pruefen();

    expect(component.ergebnisFuer(2118431553)).toBeNull();
    expect(component.fehlerFuer(2118431553)).toBeNull();
    expect(component.auditVeraltet()).toBe(false);
  });
});

describe('FleetJoinComponent', () => {
  let fleetService: { joinFleet: ReturnType<typeof vi.fn> };

  function buildWith(code: string | null): FleetJoinComponent {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: FleetService, useValue: fleetService },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => code } } },
        },
      ],
    });
    return build(() => new FleetJoinComponent());
  }

  beforeEach(() => {
    fleetService = { joinFleet: vi.fn().mockReturnValue(of(null)) };
  });

  it('meldet die erfolgreiche Eintragung', () => {
    const component = buildWith('abc-123');

    component.ngOnInit();

    expect(fleetService.joinFleet).toHaveBeenCalledWith('abc-123');
    expect(component.status()).toBe('success');
  });

  it('weist einen Aufruf ohne Code ab', () => {
    const component = buildWith(null);

    component.ngOnInit();

    expect(component.status()).toBe('error');
    expect(component.errorMessage()).toContain('Tracking Code');
    expect(fleetService.joinFleet).not.toHaveBeenCalled();
  });

  it('zeigt die Meldung des Servers', () => {
    fleetService.joinFleet.mockReturnValue(
      throwError(() => ({ error: { message: 'FAT-Link ist abgelaufen.' } })),
    );
    const component = buildWith('abc-123');

    component.ngOnInit();

    expect(component.errorMessage()).toBe('FAT-Link ist abgelaufen.');
  });

  it('nennt einen Ersatztext, wenn der Server nichts mitschickt', () => {
    fleetService.joinFleet.mockReturnValue(throwError(() => ({})));
    const component = buildWith('abc-123');

    component.ngOnInit();

    expect(component.errorMessage()).toContain('abgelaufen oder ungültig');
  });
});

describe('NavbarComponent', () => {
  let component: NavbarComponent;
  let authService: { login: ReturnType<typeof vi.fn> };
  let themeService: { choice: ReturnType<typeof vi.fn>; set: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    authService = { login: vi.fn() };
    themeService = { choice: vi.fn().mockReturnValue('system'), set: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authService },
        { provide: ThemeService, useValue: themeService },
      ],
    });
    component = build(() => new NavbarComponent());
  });

  it('klappt das Menü auf und wieder zu', () => {
    expect(component.isMenuOpen()).toBe(false);

    component.toggleMenu();
    expect(component.isMenuOpen()).toBe(true);

    component.toggleMenu();
    expect(component.isMenuOpen()).toBe(false);
  });

  describe('Darstellung', () => {
    it('bietet System, Gedämpft, Dunkel und MA an', () => {
      expect(component.themeOptions.map((option) => option.choice)).toEqual([
        'system',
        'dim',
        'dark',
        'ma',
      ]);
    });

    it('übergibt die Wahl an den Dienst und schließt das Menü', () => {
      component.toggleSettings();

      component.chooseTheme('dark');

      expect(themeService.set).toHaveBeenCalledWith('dark');
      expect(component.isSettingsOpen()).toBe(false);
    });

    it('schließt das Benutzermenü, wenn das Zahnrad aufgeht', () => {
      // Zwei offene Menüs nebeneinander überlagern sich.
      component.toggleMenu();

      component.toggleSettings();

      expect(component.isMenuOpen()).toBe(false);
      expect(component.isSettingsOpen()).toBe(true);
    });

    it('schließt das Zahnrad, wenn das Benutzermenü aufgeht', () => {
      component.toggleSettings();

      component.toggleMenu();

      expect(component.isSettingsOpen()).toBe(false);
      expect(component.isMenuOpen()).toBe(true);
    });
  });

  it('startet für einen weiteren Charakter die Anmeldung', () => {
    component.addAltCharacter();

    expect(authService.login).toHaveBeenCalled();
  });
});

describe('Anzeige-Komponenten', () => {
  it('reicht den Anmeldedienst an die Startseite durch', () => {
    const authService = { login: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
    });

    const component = build(() => new HomeComponent());

    expect(component).toBeTruthy();
  });

  it('reicht die Meldungen an die Toast-Anzeige durch', () => {
    TestBed.configureTestingModule({ providers: [ToastService] });
    const service = TestBed.inject(ToastService);
    const component = build(() => new ToastComponent());

    service.success('Fertig');

    expect(component.toastService.toasts()).toHaveLength(1);
  });

  it('reicht den Dialogzustand an die Bestätigung durch', () => {
    TestBed.configureTestingModule({ providers: [ConfirmService] });
    const service = TestBed.inject(ConfirmService);
    const component = build(() => new ConfirmComponent());

    void service.ask('Titel', 'Text');

    expect(component.confirmService.state().isOpen).toBe(true);
  });
});
