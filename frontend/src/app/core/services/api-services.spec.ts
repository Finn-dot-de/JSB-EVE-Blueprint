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
import { MyAssetService } from './my-asset.service';
import { ReadinessService } from './readiness.service';

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
        MyAssetService,
        ReadinessService,
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
});
