import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { AuthGroupService, SaveGroupDto } from './auth-group.service';

/**
 * Der Dienst ist eine dünne Hülle um je eine Adresse. Geprüft wird deshalb
 * genau das, was beim Umbenennen still kaputtgeht: Adresse und Methode.
 *
 * <p>Zwei Adressen sind besonders leicht zu verwechseln - die Pflege liegt
 * unter `/api/admin/groups`, weil `/api/groups` mit `/titles` und `/roles`
 * bereits belegt ist.</p>
 */
describe('AuthGroupService', () => {
  const apiUrl = `${environment.apiUrl}/groups`;
  const adminUrl = `${environment.apiUrl}/admin/groups`;

  let service: AuthGroupService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AuthGroupService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthGroupService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('holt Gruppen und offene Anfragen', () => {
    service.getGroups().subscribe();
    expect(httpMock.expectOne(apiUrl).request.method).toBe('GET');

    service.getOpenRequests().subscribe();
    expect(httpMock.expectOne(`${apiUrl}/requests`).request.method).toBe('GET');

    httpMock.match(() => true).forEach((r) => r.flush([]));
  });

  it('trennt Beitritt und Austritt sauber je Gruppe', () => {
    // Der Charakter steht in keiner der beiden Adressen: der Server nimmt den
    // angemeldeten. Stünde er im Pfad, wäre der Austritt ein Hebel, Fremde
    // hinauszuwerfen.
    service.applyForGroup(7).subscribe();
    const apply = httpMock.expectOne(`${apiUrl}/7/apply`);
    expect(apply.request.method).toBe('POST');
    apply.flush({});

    service.leaveGroup(7).subscribe();
    const leave = httpMock.expectOne(`${apiUrl}/7/leave`);
    expect(leave.request.method).toBe('POST');
    leave.flush(null);
  });

  it('schickt die Entscheidung als Teil der Adresse', () => {
    service.decideRequest(3, 'approve').subscribe();
    httpMock.expectOne(`${apiUrl}/requests/3/approve`).flush(null);

    service.decideRequest(3, 'reject').subscribe();
    httpMock.expectOne(`${apiUrl}/requests/3/reject`).flush(null);
  });

  it('pflegt Gruppen unter dem eigenen Verwaltungs-Präfix', () => {
    service.getAdminGroups().subscribe();
    httpMock.expectOne(adminUrl).flush([]);

    // Die Leitung ist eine Liste: eine Gruppe kann zwei zuständige Kreise haben,
    // und wer eine der Rollen trägt, entscheidet. Als einzelnes Feld verschickt
    // käme im Backend eine leere Menge an - niemand wäre mehr zuständig.
    const neu: SaveGroupDto = {
      id: null,
      name: 'Blops-SIG',
      description: null,
      roleName: 'ROLE_BLOPS',
      leaderRoleNames: ['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH'],
    };
    service.saveGroup(neu).subscribe();
    const save = httpMock.expectOne(adminUrl);
    expect(save.request.method).toBe('POST');
    expect(save.request.body.leaderRoleNames).toEqual(['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH']);
    save.flush({});

    // Ohne Leitung geht die leere Liste hinaus und nicht etwa gar kein Feld:
    // der Server nähme sonst keine Änderung an der Leitung an.
    service
      .saveGroup({ ...neu, id: 9, leaderRoleNames: [] })
      .subscribe();
    const ohneLeitung = httpMock.expectOne(adminUrl);
    expect(ohneLeitung.request.body.leaderRoleNames).toEqual([]);
    ohneLeitung.flush({});

    // Der Rollenname darf beim Anlegen leer bleiben - das Backend leitet ihn
    // dann aus dem Gruppennamen ab; der Dienst schneidet ihn nicht weg.
    service.saveGroup({ ...neu, roleName: '' }).subscribe();
    const ohneRolle = httpMock.expectOne(adminUrl);
    expect(ohneRolle.request.body.roleName).toBe('');
    ohneRolle.flush({});

    service.deleteGroup(4).subscribe();
    const remove = httpMock.expectOne(`${adminUrl}/4`);
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });
});
