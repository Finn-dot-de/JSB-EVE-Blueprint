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
import { DiscordService } from '../services/discord.service';
import { FleetService } from '../services/fleet.service';
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
  let discordService: { getMappings: ReturnType<typeof vi.fn>; saveMapping: ReturnType<typeof vi.fn> };
  let toastService: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  const mapping = { authRole: 'ROLE_USER', discordRoleId: '123', description: 'Mitglied' };

  beforeEach(() => {
    discordService = {
      getMappings: vi.fn().mockReturnValue(of([mapping])),
      saveMapping: vi.fn().mockReturnValue(of(null)),
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

  beforeEach(() => {
    authService = { login: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: authService }],
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
