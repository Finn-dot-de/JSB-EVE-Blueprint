import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { CharacterService } from './character.service';
import { DiscordService } from './discord.service';
import { DoctrineService } from './doctrine.service';
import { FleetService } from './fleet.service';
import { GroupService } from './group.service';
import { RoleAssignmentService } from './role-assignment.service';
import { SkillPlanService } from './skill-plan.service';
import { NavigationService } from './navigation.service';
import { MyAssetService } from './my-asset.service';
import { ReadinessService } from './readiness.service';
import { IndustryService } from './industry.service';

/**
 * Die übrigen HTTP-Dienste sind dünne Hüllen um je eine Adresse. Geprüft wird,
 * dass sie die richtige Adresse, die richtige Methode und die richtigen
 * Parameter verwenden - genau das kann beim Umbenennen still kaputtgehen.
 */
describe('HTTP-Dienste', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        CharacterService,
        DiscordService,
        DoctrineService,
        FleetService,
        GroupService,
        RoleAssignmentService,
        SkillPlanService,
        NavigationService,
        MyAssetService,
        ReadinessService,
        IndustryService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  describe('MyAssetService', () => {
    const apiUrl = `${environment.apiUrl}/my/assets`;
    let service: MyAssetService;

    beforeEach(() => (service = TestBed.inject(MyAssetService)));

    it('schickt nur gesetzte Filter mit', () => {
      service.search({ q: 'Nestor', typeId: null, regionName: '' }).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(request.request.params.get('q')).toBe('Nestor');
      expect(request.request.params.has('typeId')).toBe(false);
      expect(request.request.params.has('regionName')).toBe(false);
      request.flush({});
    });

    it('setzt den Gruppierungs-Schalter je nach Aufruf', () => {
      service.search({}).subscribe();
      const flat = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(flat.request.params.get('grouped')).toBe('false');
      flat.flush({});

      service.searchGrouped({}).subscribe();
      const grouped = httpMock.expectOne((req) => req.url === `${apiUrl}/search`);
      expect(grouped.request.params.get('grouped')).toBe('true');
      grouped.flush({});
    });

    it('fragt Übersicht, Vorschläge und Filter an den eigenen Adressen ab', () => {
      service.summary().subscribe();
      httpMock.expectOne(`${apiUrl}/summary`).flush({});

      service.suggestTypes('nes').subscribe();
      const suggest = httpMock.expectOne((req) => req.url === `${apiUrl}/types/suggest`);
      expect(suggest.request.params.get('q')).toBe('nes');
      suggest.flush([]);

      service.filters(6).subscribe();
      const filters = httpMock.expectOne((req) => req.url === `${apiUrl}/filters`);
      expect(filters.request.params.get('categoryId')).toBe('6');
      filters.flush({});
    });

    it('lässt den Kategorie-Filter weg, wenn keiner gesetzt ist', () => {
      service.filters(null).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/filters`);
      expect(request.request.params.has('categoryId')).toBe(false);
      request.flush({});
    });

    it('fragt die Standorte mit denselben Filtern ab', () => {
      service.placements({ typeId: 587, regionName: 'The Forge' }).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/placements`);
      expect(request.request.params.get('typeId')).toBe('587');
      expect(request.request.params.get('regionName')).toBe('The Forge');
      request.flush([]);
    });

    it('lädt den Export als Blob', () => {
      service.exportCsv({ grouped: true }).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/export`);
      expect(request.request.responseType).toBe('blob');
      request.flush(new Blob(['csv']));
    });
  });

  describe('FleetService', () => {
    const apiUrl = `${environment.apiUrl}/fleets`;
    let service: FleetService;

    beforeEach(() => (service = TestBed.inject(FleetService)));

    it('legt eine Flotte per POST an', () => {
      const dto = {
        fleetName: 'Roam',
        doctrine: 'Armor',
        linkExpiryMinutes: 60,
        trackingType: 'LIVE' as const,
      };
      service.createFleet(dto).subscribe();

      const request = httpMock.expectOne(`${apiUrl}/create`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual(dto);
      request.flush({});
    });

    it('trägt einen Teilnehmer über den Link ein', () => {
      service.joinFleet('abc-123').subscribe();

      const request = httpMock.expectOne(`${apiUrl}/join/abc-123`);
      expect(request.request.method).toBe('POST');
      request.flush(null);
    });

    it('stößt den ESI-Abgleich an und beendet eine Flotte', () => {
      service.syncFleetViaEsi(55).subscribe();
      httpMock.expectOne(`${apiUrl}/55/sync-esi`).flush(3);

      service.closeFleet(55).subscribe();
      httpMock.expectOne(`${apiUrl}/55/close`).flush(null);
    });

    it('lädt Anwesenheitsliste und jüngste Flotten', () => {
      service.getFleetAttendance(55).subscribe();
      httpMock.expectOne(`${apiUrl}/55/attendance`).flush([]);

      service.getRecentFleets().subscribe();
      httpMock.expectOne(`${apiUrl}/recent`).flush([]);
    });
  });

  describe('ReadinessService', () => {
    const apiUrl = `${environment.apiUrl}/fleet/readiness`;
    let service: ReadinessService;

    beforeEach(() => (service = TestBed.inject(ReadinessService)));

    it('lädt die Doktrin-Namen', () => {
      service.doctrines().subscribe();

      httpMock.expectOne(`${apiUrl}/doctrines`).flush([]);
    });

    it('fragt das Board mit dem Doktrin-Namen ab', () => {
      service.checkBoard('Armor').subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/board`);
      expect(request.request.params.get('doctrineName')).toBe('Armor');
      request.flush({});
    });

    it('fragt das Board ohne Namen für alle Doktrinen ab', () => {
      service.checkBoard(null).subscribe();

      const request = httpMock.expectOne((req) => req.url === `${apiUrl}/board`);
      expect(request.request.params.has('doctrineName')).toBe(false);
      request.flush({});
    });

    it('schickt ein eingefügtes Fitting an die Sandbox', () => {
      service.sandbox('[Nestor, Fit]').subscribe();

      const request = httpMock.expectOne(`${apiUrl}/sandbox`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ eftString: '[Nestor, Fit]' });
      request.flush({});
    });
  });

  describe('CharacterService', () => {
    const apiUrl = `${environment.apiUrl}/characters`;
    let service: CharacterService;

    beforeEach(() => (service = TestBed.inject(CharacterService)));

    it('bedient alle Charakter-Endpunkte', () => {
      service.getMyAlts().subscribe();
      httpMock.expectOne(`${apiUrl}/alts`).flush([]);

      service.getCorpStats().subscribe();
      httpMock.expectOne(`${apiUrl}/corp-stats`).flush([]);

      service.getAllAccounts().subscribe();
      httpMock.expectOne(`${apiUrl}/admin/accounts`).flush([]);
    });

    it('setzt den Main per POST', () => {
      service.setMainCharacter(1001).subscribe();

      const request = httpMock.expectOne(`${apiUrl}/set-main/1001`);
      expect(request.request.method).toBe('POST');
      request.flush(null);
    });

    it('holt die Alt-Vorschlaege per GET', () => {
      service.getAltSuggestions().subscribe();

      const request = httpMock.expectOne(`${apiUrl}/alt-suggestions`);
      expect(request.request.method).toBe('GET');
      request.flush([]);
    });

    it('bestaetigt einen Vorschlag per POST mit beiden IDs im Rumpf', () => {
      // Beide IDs gehoeren in den Rumpf und nicht in den Pfad: der Server
      // rechnet den Vorschlag daraus neu und lehnt ein Paar ab, das die
      // Erkennung nie vorgeschlagen hat. Vertauschte Felder faenden hier
      // niemanden mehr - der Server wuerde stillschweigend das falsche Konto
      // vormerken.
      service.confirmAltSuggestion(2002, 1001).subscribe();

      const request = httpMock.expectOne(`${apiUrl}/alt-suggestions/confirm`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ unauthedCharId: 2002, mainId: 1001 });
      request.flush({});
    });
  });

  describe('DoctrineService', () => {
    const apiUrl = `${environment.apiUrl}/doctrines`;
    let service: DoctrineService;

    beforeEach(() => (service = TestBed.inject(DoctrineService)));

    it('legt ein Fitting an, ändert und löscht es', () => {
      const dto = { doctrineName: 'Armor', shipType: 'Nestor', name: 'Fit', eftString: '[]' };

      service.createDoctrine(dto).subscribe();
      const create = httpMock.expectOne(apiUrl);
      expect(create.request.method).toBe('POST');
      create.flush({});

      service.updateDoctrine(5, dto).subscribe();
      const update = httpMock.expectOne(`${apiUrl}/5`);
      expect(update.request.method).toBe('PUT');
      update.flush({});

      service.deleteDoctrine(5).subscribe();
      const remove = httpMock.expectOne(`${apiUrl}/5`);
      expect(remove.request.method).toBe('DELETE');
      remove.flush(null);
    });

    it('lädt die vorhandenen Fittings', () => {
      service.getDoctrines().subscribe();

      httpMock.expectOne(apiUrl).flush([]);
    });
  });

  describe('DiscordService', () => {
    const apiUrl = `${environment.apiUrl}/discord`;
    let service: DiscordService;

    beforeEach(() => (service = TestBed.inject(DiscordService)));

    it('bedient Status, Zuordnungen und Trennung', () => {
      service.getStatus().subscribe();
      httpMock.expectOne(`${apiUrl}/status`).flush({ connected: true });

      service.getMappings().subscribe();
      httpMock.expectOne(`${apiUrl}/mappings`).flush([]);

      service
        .saveMapping({ authRole: 'ROLE_USER', discordRoleId: '123', description: 'Mitglied' })
        .subscribe();
      const save = httpMock.expectOne(`${apiUrl}/mappings`);
      expect(save.request.method).toBe('POST');
      save.flush(null);

      service.disconnect().subscribe();
      const remove = httpMock.expectOne(`${apiUrl}/disconnect`);
      expect(remove.request.method).toBe('DELETE');
      remove.flush(null);
    });

    // Die Prüfung darf nichts ändern. Ein POST oder DELETE an dieser Stelle
    // wäre genau das Werkzeug, das beim Prüfen repariert - deshalb steht die
    // Methode hier ausdrücklich im Test.
    it('liest die Prüfung und schreibt dabei nichts', () => {
      service.getAudit().subscribe();

      const audit = httpMock.expectOne(`${apiUrl}/audit`);
      expect(audit.request.method).toBe('GET');
      audit.flush([]);
    });

    // Einen Charakter nachzusehen darf nicht die ganze Übersicht kosten: Die
    // holt für eine Zeile jedes verknüpfte Konto erneut von Discord.
    it('liest den Stand eines einzelnen Charakters', () => {
      service.getCharacterAudit(2118431553).subscribe();

      const stand = httpMock.expectOne(`${apiUrl}/audit/characters/2118431553`);
      expect(stand.request.method).toBe('GET');
      stand.flush(null);
    });

    // Die einzige Stelle dieser Seite, die in Discord etwas ändert - deshalb
    // POST. Als GET ließe ihn früher oder später jemand aus einem Browser-Tab
    // heraus wiederholen.
    it('stößt den Abgleich für einen Charakter an', () => {
      service.stosseAbgleichAn(2118431553).subscribe();

      const sync = httpMock.expectOne(`${apiUrl}/sync/2118431553`);
      expect(sync.request.method).toBe('POST');
      sync.flush(null);
    });
  });

  describe('NavigationService', () => {
    const base = `${environment.apiUrl}/admin/navigation`;

    it('lädt das eigene Menü', () => {
      TestBed.inject(NavigationService).menu().subscribe();

      httpMock.expectOne(`${environment.apiUrl}/navigation`).flush([]);
    });

    it('lädt die Übersicht der Verwaltung', () => {
      TestBed.inject(NavigationService).overview().subscribe();

      httpMock.expectOne(base).flush({ categories: [], links: [] });
    });

    it('speichert ein Register', () => {
      const category = { id: null, name: 'Tools', icon: 'fa-solid fa-wrench' };
      TestBed.inject(NavigationService).saveCategory(category).subscribe();

      const request = httpMock.expectOne(`${base}/categories`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual(category);
      request.flush(null);
    });

    it('löscht ein Register', () => {
      TestBed.inject(NavigationService).deleteCategory(10).subscribe();

      const request = httpMock.expectOne(`${base}/categories/10`);
      expect(request.request.method).toBe('DELETE');
      request.flush(null);
    });

    it('speichert einen Menüpunkt', () => {
      const link = {
        id: null, label: 'Neu', url: '/neu', icon: null,
        categoryId: null, requiredRole: null, active: true,
      };
      TestBed.inject(NavigationService).saveLink(link).subscribe();

      const request = httpMock.expectOne(`${base}/links`);
      expect(request.request.body).toEqual(link);
      request.flush(null);
    });

    it('löscht einen Menüpunkt', () => {
      TestBed.inject(NavigationService).deleteLink(7).subscribe();

      const request = httpMock.expectOne(`${base}/links/7`);
      expect(request.request.method).toBe('DELETE');
      request.flush(null);
    });

    it('schickt Art, ID und Richtung beim Verschieben', () => {
      TestBed.inject(NavigationService).move('CATEGORY', 10, 'DOWN').subscribe();

      const request = httpMock.expectOne(`${base}/move`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ kind: 'CATEGORY', id: 10, direction: 'DOWN' });
      request.flush(null);
    });
  });

  describe('SkillPlanService', () => {
    const base = `${environment.apiUrl}/skill-plans`;

    it('lädt die Pläne', () => {
      TestBed.inject(SkillPlanService).list().subscribe();

      httpMock.expectOne(base).flush([]);
    });

    it('sucht Skills über den Suchbegriff', () => {
      TestBed.inject(SkillPlanService).searchSkills('power').subscribe();

      httpMock.expectOne(`${base}/skills?q=power`).flush([]);
    });

    it('speichert einen Plan', () => {
      const plan = { id: null, name: 'Magic 14', description: null, skills: [] };
      TestBed.inject(SkillPlanService).save(plan).subscribe();

      const request = httpMock.expectOne(base);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual(plan);
      request.flush(null);
    });

    it('löscht einen Plan', () => {
      TestBed.inject(SkillPlanService).delete(10).subscribe();

      const request = httpMock.expectOne(`${base}/10`);
      expect(request.request.method).toBe('DELETE');
      request.flush(null);
    });

    it('schickt einen Plantext zum Einlesen', () => {
      TestBed.inject(SkillPlanService).importPlanText('Hull Upgrades V').subscribe();

      const request = httpMock.expectOne(`${base}/import`);
      expect(request.request.body).toEqual({ planText: 'Hull Upgrades V' });
      request.flush({ skills: [], unresolved: [] });
    });

    it('ordnet Pläne einem Fitting zu', () => {
      TestBed.inject(SkillPlanService).assign(5, [1, 2]).subscribe();

      const request = httpMock.expectOne(`${base}/assign/5`);
      expect(request.request.method).toBe('PUT');
      expect(request.request.body).toEqual({ planIds: [1, 2] });
      request.flush(null);
    });
  });

  describe('ReadinessService – Selbstauskunft', () => {
    it('holt den eigenen Stand', () => {
      TestBed.inject(ReadinessService).myReadiness().subscribe();

      httpMock.expectOne(`${environment.apiUrl}/fleet/readiness/mine`).flush([]);
    });
  });

  describe('GroupService', () => {
    it('lädt die Corp-Titel', () => {
      TestBed.inject(GroupService).getCorporationTitles().subscribe();

      httpMock.expectOne(`${environment.apiUrl}/groups/titles`).flush([]);
    });

    it('speichert die Zuordnung eines Titels', () => {
      TestBed.inject(GroupService).saveTitleMapping(7, 'ROLE_RECRUITER').subscribe();

      const request = httpMock.expectOne(`${environment.apiUrl}/groups/titles/mapping`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ titleId: 7, roleName: 'ROLE_RECRUITER' });
      request.flush(null);
    });

    it('schickt einen leeren Rollennamen, um die Zuordnung zu lösen', () => {
      TestBed.inject(GroupService).saveTitleMapping(7, '').subscribe();

      const request = httpMock.expectOne(`${environment.apiUrl}/groups/titles/mapping`);
      expect(request.request.body).toEqual({ titleId: 7, roleName: '' });
      request.flush(null);
    });

    it('lädt den Rollenkatalog', () => {
      TestBed.inject(GroupService).getRoles().subscribe();

      httpMock.expectOne(`${environment.apiUrl}/groups/roles`).flush([]);
    });

    it('legt eine eigene Rolle an', () => {
      const role = { name: 'Recruiter', description: 'Wirbt an', special: false };
      TestBed.inject(GroupService).saveRole(role).subscribe();

      const request = httpMock.expectOne(`${environment.apiUrl}/groups/roles`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual(role);
      request.flush(null);
    });

    it('löscht eine Rolle über ihren Namen', () => {
      TestBed.inject(GroupService).deleteRole('ROLE_RECRUITER').subscribe();

      const request = httpMock.expectOne(`${environment.apiUrl}/groups/roles/ROLE_RECRUITER`);
      expect(request.request.method).toBe('DELETE');
      request.flush(null);
    });
  });

  describe('RoleAssignmentService', () => {
    const apiUrl = `${environment.apiUrl}/roles`;

    it('holt die Rollen eines Charakters samt Bewertung', () => {
      TestBed.inject(RoleAssignmentService).rolesOf(42).subscribe();

      httpMock.expectOne(`${apiUrl}/characters/42`).flush(null);
    });

    it('weist eine Rolle zu und schickt den Grund im Rumpf', () => {
      TestBed.inject(RoleAssignmentService).grant(42, 'ROLE_RECRUITER', 'wirbt an').subscribe();

      const request = httpMock.expectOne(`${apiUrl}/characters/42/grant`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ roleName: 'ROLE_RECRUITER', reason: 'wirbt an' });
      request.flush(null);
    });

    it('entzieht über POST, damit der Grund nicht in der Adresszeile landet', () => {
      // In der Adresszeile stünde er in jedem Zugriffsprotokoll.
      TestBed.inject(RoleAssignmentService).revoke(42, 'ROLE_RECRUITER', '').subscribe();

      const request = httpMock.expectOne(`${apiUrl}/characters/42/revoke`);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ roleName: 'ROLE_RECRUITER', reason: '' });
      request.flush(null);
    });

    it('holt den Verlauf eines Charakters', () => {
      TestBed.inject(RoleAssignmentService).auditFor(42).subscribe();

      httpMock.expectOne(`${apiUrl}/characters/42/audit`).flush([]);
    });

    it('holt den Verlauf über alle Charaktere', () => {
      TestBed.inject(RoleAssignmentService).recentAudit().subscribe();

      httpMock.expectOne(`${apiUrl}/audit`).flush([]);
    });
  });

  describe('IndustryService', () => {
    const apiUrl = `${environment.apiUrl}/industry`;

    it('sucht baubare Dinge unter der richtigen Adresse', () => {
      // Genau dieser Test hätte den doppelten Pfad /api/api/industry gefunden.
      // Der Komponententest konnte es nicht: er mockt den Dienst und sieht keine
      // einzige Adresse.
      TestBed.inject(IndustryService).search('raven').subscribe();

      const req = httpMock.expectOne((r) => r.url === `${apiUrl}/search`);
      expect(req.request.method).toBe('GET');
      expect(req.request.params.get('q')).toBe('raven');
      req.flush([]);
    });

    it('rechnet einen Bauwunsch mit Menge und Tiefe durch', () => {
      TestBed.inject(IndustryService).preview(638, 50).subscribe();

      const req = httpMock.expectOne((r) => r.url === `${apiUrl}/preview`);
      expect(req.request.params.get('productTypeId')).toBe('638');
      expect(req.request.params.get('quantity')).toBe('50');
      expect(req.request.params.get('depth')).toBe('1');
      req.flush({});
    });

    it('legt einen Auftrag per POST an', () => {
      TestBed.inject(IndustryService).create(638, 50).subscribe();

      const req = httpMock.expectOne(`${apiUrl}/orders`);
      expect(req.request.method).toBe('POST');
      // Der Bauort geht mit - ohne ihn kann der Server Transport und Fracht
      // nicht rechnen und müsste den teuersten Fall annehmen.
      expect(req.request.body).toEqual({
        productTypeId: 638,
        quantity: 50,
        buildSystemId: null,
        buildLocationName: null,
      });
      req.flush({});
    });

    it('stellt eine Kaufen/Bauen-Entscheidung per PUT um', () => {
      TestBed.inject(IndustryService).decide(7, 34, 'BUILD').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/orders/7/decision`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ typeId: 34, decision: 'BUILD' });
      req.flush({});
    });

    it('setzt den Bauort eines bestehenden Auftrags per PUT', () => {
      TestBed.inject(IndustryService).setBuildLocation(7, 30000142, null, 'Jita').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/orders/7/location`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({
        buildSystemId: 30000142,
        buildLocationId: null,
        buildLocationName: 'Jita',
      });
      req.flush({});
    });

    it('gibt das Bausystem an die Vorschau weiter', () => {
      // Ohne das rechnet ausgerechnet die Vorschau noch EVE-weit und widerspricht
      // dem angelegten Auftrag in der Sekunde danach.
      TestBed.inject(IndustryService).preview(638, 1, 1, 30000142).subscribe();

      const req = httpMock.expectOne((r) => r.url === `${apiUrl}/preview`);
      expect(req.request.params.get('buildSystemId')).toBe('30000142');
      req.flush({});
    });

    it('rechnet einen bestehenden Auftrag per PUT neu', () => {
      TestBed.inject(IndustryService).recalculate(7).subscribe();

      const req = httpMock.expectOne(`${apiUrl}/orders/7/recalculate`);
      expect(req.request.method).toBe('PUT');
      req.flush({});
    });

    it('holt die Auftragsliste und einen einzelnen Auftrag', () => {
      const service = TestBed.inject(IndustryService);

      service.orders().subscribe();
      httpMock.expectOne(`${apiUrl}/orders`).flush([]);

      service.order(7).subscribe();
      httpMock.expectOne(`${apiUrl}/orders/7`).flush({});
    });

    it('reicht den gewählten Bauort beim Anlegen durch', () => {
      TestBed.inject(IndustryService).create(638, 50, 30000142, 'Jita IV-4').subscribe();

      const req = httpMock.expectOne(`${apiUrl}/orders`);
      expect(req.request.body).toEqual({
        productTypeId: 638,
        quantity: 50,
        buildSystemId: 30000142,
        buildLocationName: 'Jita IV-4',
      });
      req.flush({});
    });

    it('wendet eine Voreinstellung per PUT an', () => {
      TestBed.inject(IndustryService).applyStrategy(7, 'COST_EFFICIENT').subscribe();

      const req = httpMock.expectOne((r) => r.url === `${apiUrl}/orders/7/strategy`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.params.get('strategy')).toBe('COST_EFFICIENT');
      req.flush({});
    });

    it('holt die Blaupausen-Prüfung', () => {
      TestBed.inject(IndustryService).blueprints(7).subscribe();

      httpMock.expectOne(`${apiUrl}/orders/7/blueprints`).flush([]);
    });

    it('holt die Einkaufsliste zu einem Auftrag', () => {
      TestBed.inject(IndustryService).procurement(7).subscribe();

      const req = httpMock.expectOne(`${apiUrl}/orders/7/procurement`);
      expect(req.request.method).toBe('GET');
      req.flush({});
    });

    it('sucht Bauorte', () => {
      TestBed.inject(IndustryService).locations('jita').subscribe();

      const req = httpMock.expectOne((r) => r.url === `${apiUrl}/locations`);
      expect(req.request.params.get('q')).toBe('jita');
      req.flush([]);
    });

    it('bricht einen Auftrag ab und löscht ihn', () => {
      const service = TestBed.inject(IndustryService);

      service.cancel(7).subscribe();
      const abbruch = httpMock.expectOne(`${apiUrl}/orders/7/cancel`);
      expect(abbruch.request.method).toBe('PUT');
      abbruch.flush(null);

      service.remove(7).subscribe();
      const loeschen = httpMock.expectOne(`${apiUrl}/orders/7`);
      expect(loeschen.request.method).toBe('DELETE');
      loeschen.flush(null);
    });
  });
});
