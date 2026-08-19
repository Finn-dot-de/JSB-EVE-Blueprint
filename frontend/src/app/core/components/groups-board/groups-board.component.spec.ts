import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GroupsBoardComponent } from './groups-board.component';
import { AuthGroupService, GroupDto, GroupMemberDto } from '../../services/auth-group.service';
import { AuthRoleDto, GroupService } from '../../services/group.service';
import { AuthService } from '../../services/auth.service';
import { ToastService } from '../../services/toast.service';
import { ConfirmService } from '../../services/confirm.service';

/**
 * Der Gruppen-Beitritt und der Austritt.
 *
 * <p>Zu prüfen ist hier nicht, ob die Oberfläche Rechte durchsetzt - das tut
 * ausschließlich das Backend. Zu prüfen ist, dass sie nichts anbietet, was der
 * Server ohnehin ablehnen würde: ein sichtbarer Knopf, der in eine Fehlermeldung
 * läuft, ist schlechter als kein Knopf.</p>
 *
 * <p>Geprüft wird durchweg der Zustand, nicht das Aussehen: dass eine Zeile als
 * Mitgliedschaft gilt, entscheidet {@link GroupsBoardComponent.rowState} - die
 * grüne Färbung hängt nur daran und ist keine zweite Quelle.</p>
 */
describe('GroupsBoardComponent', () => {
  const gruppe = (over: Partial<GroupDto> = {}): GroupDto => ({
    id: 1,
    name: 'Wurmloch-SIG',
    description: 'Für Löcher',
    roleName: 'ROLE_WH',
    leaderRoleNames: ['ROLE_FC_STRAT'],
    memberCount: 3,
    // Zwei Felder und nicht eines: die Attrappe darf beide getrennt setzen,
    // sonst liesse sich gar nicht prüfen, welchem von beiden die Oberfläche
    // folgt. Der Normalfall setzt sie gleichsinnig - so füllt der Server sie.
    canViewMembers: true,
    isMember: false,
    hasPendingRequest: false,
    isLeader: false,
    ...over,
  });

  const mitglied = (characterId: number, characterName: string): GroupMemberDto => ({
    characterId,
    characterName,
    portraitUrl: `https://images.evetech.net/characters/${characterId}/portrait?size=64`,
  });

  const rolle = (name: string): AuthRoleDto => ({
    name,
    description: '',
    source: 'CUSTOM',
    special: false,
    grantingTitles: [],
  });

  /** Ein Fehler, wie ihn der Interceptor durchreicht - mit und ohne Meldung. */
  const fehler = (message?: string) =>
    throwError(() => (message ? { error: { message } } : { error: null }));

  let groupsService: {
    getGroups: ReturnType<typeof vi.fn>;
    getOpenRequests: ReturnType<typeof vi.fn>;
    applyForGroup: ReturnType<typeof vi.fn>;
    leaveGroup: ReturnType<typeof vi.fn>;
    getMembers: ReturnType<typeof vi.fn>;
    removeMember: ReturnType<typeof vi.fn>;
    decideRequest: ReturnType<typeof vi.fn>;
    saveGroup: ReturnType<typeof vi.fn>;
    deleteGroup: ReturnType<typeof vi.fn>;
  };
  let groupService: { getRoles: ReturnType<typeof vi.fn> };
  let auth: { hasAnyRole: ReturnType<typeof vi.fn> };
  let toast: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
  let confirm: { ask: ReturnType<typeof vi.fn> };

  function build(gruppen: GroupDto[] = [gruppe()], admin = false) {
    groupsService = {
      getGroups: vi.fn().mockReturnValue(of(gruppen)),
      getOpenRequests: vi.fn().mockReturnValue(of([])),
      applyForGroup: vi.fn().mockReturnValue(of(void 0)),
      leaveGroup: vi.fn().mockReturnValue(of(void 0)),
      getMembers: vi.fn().mockReturnValue(of([mitglied(1, 'Alpha'), mitglied(2, 'Beta')])),
      removeMember: vi.fn().mockReturnValue(of(void 0)),
      decideRequest: vi.fn().mockReturnValue(of(void 0)),
      saveGroup: vi.fn().mockReturnValue(of(void 0)),
      deleteGroup: vi.fn().mockReturnValue(of(void 0)),
    };
    groupService = { getRoles: vi.fn().mockReturnValue(of([])) };
    auth = { hasAnyRole: vi.fn().mockReturnValue(admin) };
    toast = { success: vi.fn(), error: vi.fn() };
    confirm = { ask: vi.fn().mockResolvedValue(true) };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthGroupService, useValue: groupsService },
        { provide: GroupService, useValue: groupService },
        { provide: AuthService, useValue: auth },
        { provide: ToastService, useValue: { ...toast, info: vi.fn() } },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    return TestBed.runInInjectionContext(() => new GroupsBoardComponent());
  }

  beforeEach(() => vi.clearAllMocks());

  // ================= Reiter und Zuständigkeit =================

  it('zeigt den Verwaltungs-Reiter nicht, wer nichts zu verwalten hat', () => {
    // Der Reiter darf nicht an einer geratenen Rollenliste hängen: welche Rollen
    // jemand wirklich trägt, steht nur in den gelieferten Daten.
    const c = build([gruppe({ isLeader: false })], false);
    c.ngOnInit();

    expect(c.canManage()).toBe(false);
  });

  it('zeigt ihn dem Träger der Leitungsrolle einer einzigen Gruppe', () => {
    const c = build([gruppe({ isLeader: false }), gruppe({ id: 2, isLeader: true })], false);
    c.ngOnInit();

    expect(c.canManage()).toBe(true);
  });

  it('zeigt ihn Admins auch ohne eigene Gruppe', () => {
    const c = build([gruppe({ isLeader: false })], true);
    c.ngOnInit();

    expect(c.canManage()).toBe(true);
  });

  it('holt die Anfragen nur, wenn jemand entscheiden darf', () => {
    // Sonst läuft für jedes Mitglied bei jedem Seitenaufruf ein Abruf, den der
    // Server mit 403 beantwortet.
    const ohne = build([gruppe({ isLeader: false })], false);
    ohne.ngOnInit();
    expect(groupsService.getOpenRequests).not.toHaveBeenCalled();
    expect(ohne.requests()).toEqual([]);

    const mit = build([gruppe({ isLeader: true })], false);
    mit.ngOnInit();
    expect(groupsService.getOpenRequests).toHaveBeenCalled();
  });

  it('wechselt den Reiter und lädt die Anfragen nur für Zuständige nach', () => {
    const c = build([gruppe({ isLeader: true })], false);
    c.ngOnInit();
    groupsService.getOpenRequests.mockClear();

    c.setTab('MANAGE');
    expect(c.activeTab()).toBe('MANAGE');
    expect(groupsService.getOpenRequests).toHaveBeenCalledTimes(1);

    const fremd = build([gruppe({ isLeader: false })], false);
    fremd.ngOnInit();
    fremd.setTab('MANAGE');
    expect(groupsService.getOpenRequests).not.toHaveBeenCalled();
  });

  it('zählt die offenen Anfragen für die Zahl am Reiter', () => {
    const c = build([gruppe({ isLeader: true })], false);
    groupsService.getOpenRequests.mockReturnValue(
      of([{ requestId: 1 }, { requestId: 2 }] as never),
    );
    c.ngOnInit();

    expect(c.pendingCount()).toBe(2);
  });

  // ================= Zustand einer Zeile =================

  it('erkennt die Zeile eines Mitglieds als Mitgliedschaft', () => {
    // Der Zustand entscheidet über den Knopf UND über die grüne Zeile. Würde
    // hier die CSS-Klasse geprüft, ginge dieselbe Frage zweimal verschieden aus.
    const c = build([gruppe({ isMember: true })], false);
    c.ngOnInit();

    expect(c.rowState(c.groups()[0])).toBe('MEMBER');
  });

  it('lässt die Mitgliedschaft über eine gleichzeitig offene Anfrage siegen', () => {
    // Kommt vor, wenn die Rolle auf anderem Weg kam, während der Antrag lief -
    // "Anfrage ausstehend" hülfe dem Mitglied dann nicht weiter.
    const c = build([gruppe({ isMember: true, hasPendingRequest: true })], false);
    c.ngOnInit();

    expect(c.rowState(c.groups()[0])).toBe('MEMBER');
  });

  it('unterscheidet offene Anfrage und freien Beitritt', () => {
    const c = build([gruppe({ hasPendingRequest: true }), gruppe({ id: 2 })], false);
    c.ngOnInit();

    expect(c.rowState(c.groups()[0])).toBe('PENDING');
    expect(c.rowState(c.groups()[1])).toBe('OPEN');
  });

  // ================= Beitreten =================

  it('beantragt eine Gruppe und merkt sich den offenen Antrag sofort', () => {
    // Ohne die sofortige Markierung bliebe der Knopf bis zum nächsten Laden
    // aktiv - und ein zweiter Klick liefe in den Doppelantrag-Schutz des
    // Servers, also in eine Fehlermeldung für etwas, das geklappt hat.
    const c = build([gruppe()], false);
    c.ngOnInit();

    c.apply(c.groups()[0]);

    expect(groupsService.applyForGroup).toHaveBeenCalledWith(1);
    expect(c.rowState(c.groups()[0])).toBe('PENDING');
  });

  it('holt nach einem eigenen Antrag die Anfragenliste nach, wenn man zuständig ist', () => {
    const c = build([gruppe({ isLeader: true })], false);
    c.ngOnInit();
    groupsService.getOpenRequests.mockClear();

    c.apply(c.groups()[0]);

    expect(groupsService.getOpenRequests).toHaveBeenCalledTimes(1);
  });

  it('beantragt nichts, wo schon eine Mitgliedschaft oder ein Antrag steht', () => {
    const c = build([gruppe({ isMember: true }), gruppe({ id: 2, hasPendingRequest: true })], false);
    c.ngOnInit();

    c.apply(c.groups()[0]);
    c.apply(c.groups()[1]);

    expect(groupsService.applyForGroup).not.toHaveBeenCalled();
  });

  it('meldet einen abgelehnten Antrag, ohne die Zeile umzustellen', () => {
    const c = build([gruppe()], false);
    c.ngOnInit();
    groupsService.applyForGroup.mockReturnValue(fehler('Schon Mitglied.'));

    c.apply(c.groups()[0]);

    expect(toast.error).toHaveBeenCalledWith('Schon Mitglied.');
    expect(c.rowState(c.groups()[0])).toBe('OPEN');
  });

  // ================= Austreten =================

  it('fragt vor dem Verlassen nach und ruft dann den Austritt auf', async () => {
    const c = build([gruppe({ isMember: true })], false);
    c.ngOnInit();

    await c.leave(c.groups()[0]);

    expect(confirm.ask).toHaveBeenCalled();
    expect(groupsService.leaveGroup).toHaveBeenCalledWith(1);
    expect(toast.success).toHaveBeenCalledWith('Gruppe verlassen.');
  });

  it('lädt nach dem Austritt neu, weil Mitgliederzahl und Zuständigkeit mitfallen', async () => {
    const c = build([gruppe({ isMember: true })], false);
    c.ngOnInit();
    groupsService.getGroups.mockClear();

    await c.leave(c.groups()[0]);

    expect(groupsService.getGroups).toHaveBeenCalledTimes(1);
  });

  it('verlässt nichts, wenn die Rückfrage verneint wird', async () => {
    const c = build([gruppe({ isMember: true })], false);
    c.ngOnInit();
    confirm.ask.mockResolvedValue(false);

    await c.leave(c.groups()[0]);

    expect(groupsService.leaveGroup).not.toHaveBeenCalled();
  });

  it('bietet den Austritt nicht an, wo keine Mitgliedschaft besteht', async () => {
    const c = build([gruppe({ isMember: false })], false);
    c.ngOnInit();

    await c.leave(c.groups()[0]);

    expect(confirm.ask).not.toHaveBeenCalled();
    expect(groupsService.leaveGroup).not.toHaveBeenCalled();
  });

  it('meldet einen gescheiterten Austritt mit dem Standardtext', async () => {
    const c = build([gruppe({ isMember: true })], false);
    c.ngOnInit();
    groupsService.leaveGroup.mockReturnValue(fehler());

    await c.leave(c.groups()[0]);

    expect(toast.error).toHaveBeenCalledWith('Austritt fehlgeschlagen.');
  });

  // ================= Mitglieder einer Gruppe =================

  it('holt beim Seitenaufbau keine einzige Mitgliederliste', () => {
    // Der ganze Grund für das Aufklappen: eine Corporation mit zwanzig SIGs
    // würde sonst zwanzig Listen holen, von denen keine jemand ansieht.
    const c = build([gruppe(), gruppe({ id: 2 }), gruppe({ id: 3 })], true);
    c.ngOnInit();

    expect(groupsService.getMembers).not.toHaveBeenCalled();
    expect(c.expandedGroupId()).toBeNull();
  });

  it('holt die Mitglieder beim Aufklappen genau einmal, nicht bei jedem Klick', async () => {
    // Auf, zu, wieder auf: der zweite Blick auf dieselbe Gruppe darf den Server
    // nicht erneut fragen - sonst löst jedes Auf und Zu einen Abruf aus.
    const c = build([gruppe()], true);
    c.ngOnInit();

    c.toggleMembers(c.groups()[0]);
    expect(groupsService.getMembers).toHaveBeenCalledWith(1);
    expect(c.isExpanded(c.groups()[0])).toBe(true);
    expect(c.membersFor(c.groups()[0]).map((m) => m.characterName)).toEqual(['Alpha', 'Beta']);

    c.toggleMembers(c.groups()[0]);
    expect(c.isExpanded(c.groups()[0])).toBe(false);

    c.toggleMembers(c.groups()[0]);
    expect(c.isExpanded(c.groups()[0])).toBe(true);
    expect(groupsService.getMembers).toHaveBeenCalledTimes(1);
  });

  it('zeigt höchstens eine Mitgliederliste und holt die zweite nur einmal', () => {
    const c = build([gruppe(), gruppe({ id: 2 })], true);
    c.ngOnInit();

    c.toggleMembers(c.groups()[0]);
    c.toggleMembers(c.groups()[1]);

    expect(c.isExpanded(c.groups()[0])).toBe(false);
    expect(c.isExpanded(c.groups()[1])).toBe(true);
    expect(groupsService.getMembers).toHaveBeenCalledTimes(2);

    // Zurück zur ersten: die Liste liegt schon vor, kein dritter Abruf.
    c.toggleMembers(c.groups()[0]);
    expect(groupsService.getMembers).toHaveBeenCalledTimes(2);
  });

  it('hält eine Gruppe ohne Mitglieder aus', () => {
    // Eine frisch angelegte SIG hat null Mitglieder - das ist kein Fehler.
    const c = build([gruppe({ memberCount: 0 })], true);
    groupsService.getMembers.mockReturnValue(of([]));
    c.ngOnInit();

    c.toggleMembers(c.groups()[0]);

    expect(c.membersFor(c.groups()[0])).toEqual([]);
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('klappt bei einem Fehlschlag wieder zu und merkt sich keine leere Liste', () => {
    // Eine gespeicherte leere Liste läse sich für den Rest der Sitzung als
    // "keine Mitglieder" - obwohl niemand das weiß.
    const c = build([gruppe()], true);
    groupsService.getMembers.mockReturnValue(fehler());
    c.ngOnInit();

    c.toggleMembers(c.groups()[0]);

    expect(toast.error).toHaveBeenCalledWith('Mitglieder konnten nicht geladen werden.');
    expect(c.isExpanded(c.groups()[0])).toBe(false);
    expect(c.loadingMembersFor()).toBeNull();

    // Der nächste Versuch fragt wieder - nichts Falsches ist hängen geblieben.
    groupsService.getMembers.mockReturnValue(of([mitglied(1, 'Alpha')]));
    c.toggleMembers(c.groups()[0]);
    expect(c.membersFor(c.groups()[0]).length).toBe(1);
  });

  // ================= Sichtkreis: wer WEM zusieht =================

  it('gibt dem gewöhnlichen Mitglied keinen Aufklapp-Punkt und holt nichts', () => {
    // Der Kern der Änderung: wer in einer Gruppe ist, sieht nur die Führung,
    // die IT und A38. Für alle anderen liefert der Server 403 - ein Pfeil, der
    // zuverlässig in eine Fehlermeldung läuft, wäre schlimmer als kein Pfeil.
    // So kommt es vom Server: kein Sichtkreis, also auch keine Zahl.
    const c = build(
      [gruppe({ canViewMembers: false, memberCount: null, isMember: true })],
      false,
    );
    c.ngOnInit();

    expect(c.canViewMembers(c.groups()[0])).toBe(false);

    c.toggleMembers(c.groups()[0]);

    expect(groupsService.getMembers).not.toHaveBeenCalled();
    expect(c.expandedGroupId()).toBeNull();
    expect(c.isExpanded(c.groups()[0])).toBe(false);
    // Auch die Zahl selbst bleibt weg: sie ist dieselbe Auskunft eine Stufe
    // gröber. "0 Mitglieder" wäre obendrein gelogen.
    expect(c.memberLabel(c.groups()[0])).toBe('');
    // Ohne Fehlermeldung: es ist nichts schiefgegangen, es steht nur weniger da.
    expect(toast.error).not.toHaveBeenCalled();
  });

  it('klappt mit Berechtigung auf wie bisher', () => {
    // Die Gegenprobe zum Test darüber - das Feld sagt "diese Liste bekommst du".
    const c = build([gruppe({ canViewMembers: true, memberCount: 2 })], false);
    c.ngOnInit();

    expect(c.canViewMembers(c.groups()[0])).toBe(true);

    c.toggleMembers(c.groups()[0]);

    expect(groupsService.getMembers).toHaveBeenCalledWith(1);
    expect(c.isExpanded(c.groups()[0])).toBe(true);
    expect(c.membersFor(c.groups()[0]).map((m) => m.characterName)).toEqual(['Alpha', 'Beta']);
  });

  it('liest den Sichtkreis aus den gelieferten Daten und nicht aus einer Rollenliste', () => {
    // Der Grund für das gelieferte Kennzeichen: der Kreis soll sich im Backend
    // mit einer Zeile erweitern lassen. Eine hier nachgebaute Liste (DIRECTOR,
    // CEO, IT_ADMIN, A38) wäre eine zweite Wahrheit - deshalb entscheidet das
    // Feld und nicht hasAnyRole.
    const ohneKreis = build([gruppe({ canViewMembers: false, memberCount: null })], true);
    ohneKreis.ngOnInit();
    expect(ohneKreis.isAdmin).toBe(true);
    expect(ohneKreis.canViewMembers(ohneKreis.groups()[0])).toBe(false);

    // Und umgekehrt: A38 ist kein Admin dieser Oberfläche, sieht die Liste aber.
    const imKreis = build([gruppe({ canViewMembers: true, memberCount: 0 })], false);
    imKreis.ngOnInit();
    expect(imKreis.isAdmin).toBe(false);
    expect(imKreis.canViewMembers(imKreis.groups()[0])).toBe(true);
  });

  it('folgt dem Kennzeichen und nicht der Zahl, wenn beide sich widersprechen', () => {
    // Der Test, der die aufgelöste Ableitung überhaupt erst nachweisbar macht.
    // Solange Server beides gleichsinnig füllt, verhielte sich "memberCount
    // !== null" identisch - der Widerspruch ist die einzige Lage, in der sich
    // die beiden Quellen unterscheiden lassen. Deshalb steht er hier künstlich
    // im Datensatz und nicht, weil der Server ihn heute erzeugte.

    // Richtung 1 - die gefährliche: eine Zahl steht da, gesehen werden darf
    // trotzdem nichts. Aus der Zahl abgelesen stünde hier ein Pfeil, der
    // zuverlässig 403 liefert.
    const zahlOhneRecht = build([gruppe({ canViewMembers: false, memberCount: 42 })], false);
    zahlOhneRecht.ngOnInit();
    expect(zahlOhneRecht.canViewMembers(zahlOhneRecht.groups()[0])).toBe(false);

    zahlOhneRecht.toggleMembers(zahlOhneRecht.groups()[0]);
    expect(groupsService.getMembers).not.toHaveBeenCalled();
    expect(zahlOhneRecht.expandedGroupId()).toBeNull();

    // Richtung 2 - die stille: kein Zahl, aber sehen darf man. Aus der Zahl
    // abgelesen fiele der Aufklapp-Punkt weg, obwohl der Server die Liste
    // herausgäbe; niemand merkte es, weil nichts kaputtgeht, nur fehlt.
    const rechtOhneZahl = build([gruppe({ canViewMembers: true, memberCount: null })], false);
    rechtOhneZahl.ngOnInit();
    expect(rechtOhneZahl.canViewMembers(rechtOhneZahl.groups()[0])).toBe(true);

    rechtOhneZahl.toggleMembers(rechtOhneZahl.groups()[0]);
    expect(groupsService.getMembers).toHaveBeenCalledWith(1);
    expect(rechtOhneZahl.isExpanded(rechtOhneZahl.groups()[0])).toBe(true);

    // Die Zahl bleibt dabei genau das, was sie ist - eine Beschriftung. Sie
    // gewinnt keine Aussage über Rechte zurück, in keine der beiden Richtungen.
    expect(zahlOhneRecht.memberLabel(zahlOhneRecht.groups()[0])).toBe('42 Mitglieder');
    expect(rechtOhneZahl.memberLabel(rechtOhneZahl.groups()[0])).toBe('');
  });

  it('trennt Sehen und Entfernen - ein A38 sieht, wirft aber niemanden hinaus', () => {
    // Die beiden Kreise sind im Backend verschieden und müssen es bleiben. Eine
    // Leitung ohne Sichtkreis darf entfernen, sieht die Namen dazu aber nicht;
    // ein A38 sieht sie und hat keinen Entfernen-Knopf.
    const a38 = build([gruppe({ canViewMembers: true, memberCount: 2, isLeader: false })], false);
    a38.ngOnInit();
    expect(a38.canViewMembers(a38.groups()[0])).toBe(true);
    expect(a38.canRemoveFrom(a38.groups()[0])).toBe(false);

    const leitung = build(
      [gruppe({ canViewMembers: false, memberCount: null, isLeader: true })],
      false,
    );
    leitung.ngOnInit();
    expect(leitung.canViewMembers(leitung.groups()[0])).toBe(false);
    expect(leitung.canRemoveFrom(leitung.groups()[0])).toBe(true);
  });

  // ================= Mitglieder entfernen =================

  it('bietet das Entfernen nicht an, wer für die Gruppe nicht zuständig ist', () => {
    // Der gefährlichste Knopf des Features: er trägt eine FREMDE Charakter-Id.
    // Abgeleitet wird die Zuständigkeit aus isLeader vom Server, nicht aus einer
    // hier geratenen Rollenliste.
    const c = build([gruppe({ isLeader: false, isMember: true })], false);
    c.ngOnInit();

    expect(c.canRemoveFrom(c.groups()[0])).toBe(false);
  });

  it('bietet es der Leitung dieser Gruppe und den Admins an', () => {
    const leitung = build([gruppe({ isLeader: true })], false);
    leitung.ngOnInit();
    expect(leitung.canRemoveFrom(leitung.groups()[0])).toBe(true);

    const admin = build([gruppe({ isLeader: false })], true);
    admin.ngOnInit();
    expect(admin.canRemoveFrom(admin.groups()[0])).toBe(true);
  });

  it('trennt die Zuständigkeit je Gruppe - eine Leitungsrolle gilt nicht überall', () => {
    // isLeader hängt an der einzelnen Gruppe. Würde die Antwort einmal für die
    // ganze Seite berechnet, stünde der Knopf auch in fremden SIGs.
    const c = build([gruppe({ isLeader: true }), gruppe({ id: 2, isLeader: false })], false);
    c.ngOnInit();

    expect(c.canRemoveFrom(c.groups()[0])).toBe(true);
    expect(c.canRemoveFrom(c.groups()[1])).toBe(false);
  });

  it('fragt mit dem NAMEN nach und entfernt danach genau dieses Mitglied', async () => {
    const c = build([gruppe({ isLeader: true })], false);
    c.ngOnInit();
    c.toggleMembers(c.groups()[0]);

    await c.removeMember(c.groups()[0], mitglied(2, 'Beta'));

    // Der Name muss in der Rückfrage stehen: die Zeilen sehen einander gleich,
    // und ein Fehlklick nimmt der falschen Person den Discord-Zugang.
    expect(confirm.ask).toHaveBeenCalledWith(
      expect.any(String),
      expect.stringContaining('Beta'),
      'Entfernen',
    );
    expect(groupsService.removeMember).toHaveBeenCalledWith(1, 2);
  });

  it('entfernt nichts, wenn die Rückfrage verneint wird', async () => {
    const c = build([gruppe({ isLeader: true })], false);
    c.ngOnInit();
    confirm.ask.mockResolvedValue(false);

    await c.removeMember(c.groups()[0], mitglied(2, 'Beta'));

    expect(confirm.ask).toHaveBeenCalled();
    expect(groupsService.removeMember).not.toHaveBeenCalled();
  });

  it('fragt gar nicht erst, wer nicht zuständig ist', async () => {
    // Die Sperre sitzt vor der Rückfrage: ein Dialog für etwas, das der Server
    // ohnehin mit 403 beantwortet, ist ein Versprechen, das niemand hält.
    const c = build([gruppe({ isLeader: false })], false);
    c.ngOnInit();

    await c.removeMember(c.groups()[0], mitglied(2, 'Beta'));

    expect(confirm.ask).not.toHaveBeenCalled();
    expect(groupsService.removeMember).not.toHaveBeenCalled();
  });

  it('frischt danach Mitgliederliste und Mitgliederzahl auf', async () => {
    // Beides: die Liste darunter und die Zahl in der Zeile darüber. Die Zahl
    // bloß herunterzuzählen ginge daneben, sobald jemand zeitgleich beitritt.
    const c = build([gruppe({ isLeader: true, memberCount: 2 })], false);
    c.ngOnInit();
    c.toggleMembers(c.groups()[0]);
    groupsService.getMembers.mockClear();
    groupsService.getGroups.mockClear();
    groupsService.getMembers.mockReturnValue(of([mitglied(1, 'Alpha')]));
    groupsService.getGroups.mockReturnValue(of([gruppe({ isLeader: true, memberCount: 1 })]));

    await c.removeMember(c.groups()[0], mitglied(2, 'Beta'));

    expect(groupsService.getMembers).toHaveBeenCalledTimes(1);
    expect(groupsService.getGroups).toHaveBeenCalledTimes(1);
    expect(c.membersFor(c.groups()[0]).map((m) => m.characterName)).toEqual(['Alpha']);
    expect(c.memberLabel(c.groups()[0])).toBe('1 Mitglied');
    expect(toast.success).toHaveBeenCalledWith('Beta entfernt.');
  });

  it('meldet ein abgelehntes Entfernen mit dem Text des Servers', async () => {
    // Der Server verbietet der Leitung, einen Admin zu entfernen. Dieser Satz
    // ist die einzige Erklärung, die der Betrachter dafür bekommt.
    const c = build([gruppe({ isLeader: true })], false);
    c.ngOnInit();
    groupsService.removeMember.mockReturnValue(
      fehler('Mitglieder der Fuehrung entfernt nur die Fuehrung selbst.'),
    );

    await c.removeMember(c.groups()[0], mitglied(9, 'Direktor'));

    expect(toast.error).toHaveBeenCalledWith(
      'Mitglieder der Fuehrung entfernt nur die Fuehrung selbst.',
    );
  });

  it('meldet ein gescheitertes Entfernen auch ohne Text mit dem Standardsatz', async () => {
    const c = build([gruppe({ isLeader: true })], false);
    c.ngOnInit();
    groupsService.removeMember.mockReturnValue(fehler());

    await c.removeMember(c.groups()[0], mitglied(2, 'Beta'));

    expect(toast.error).toHaveBeenCalledWith('Mitglied konnte nicht entfernt werden.');
  });

  // ================= Entscheidungen =================

  it('nimmt eine Anfrage an und lädt die Gruppen neu', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.requests.set([{ requestId: 5 }] as never);
    groupsService.getGroups.mockClear();

    c.approve({ requestId: 5 } as never);

    expect(groupsService.decideRequest).toHaveBeenCalledWith(5, 'approve');
    expect(c.requests()).toEqual([]);
    expect(groupsService.getGroups).toHaveBeenCalledTimes(1);
  });

  it('fragt vor dem Ablehnen nach', async () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    groupsService.getGroups.mockClear();

    await c.reject({ requestId: 5 } as never);

    expect(confirm.ask).toHaveBeenCalled();
    expect(groupsService.decideRequest).toHaveBeenCalledWith(5, 'reject');
    // Eine Ablehnung ändert keine Mitgliedschaft - kein zweiter Abruf.
    expect(groupsService.getGroups).not.toHaveBeenCalled();
  });

  it('lehnt nichts ab, wenn die Rückfrage verneint wird', async () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    confirm.ask.mockResolvedValue(false);

    await c.reject({ requestId: 5 } as never);

    expect(groupsService.decideRequest).not.toHaveBeenCalled();
  });

  it('meldet eine gescheiterte Entscheidung', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    groupsService.decideRequest.mockReturnValue(fehler('Nicht zuständig.'));

    c.approve({ requestId: 5 } as never);

    expect(toast.error).toHaveBeenCalledWith('Nicht zuständig.');
  });

  // ================= Darstellung =================

  it('nennt eine Gruppe ohne Leitungsrolle beim gedämpften Ersatztext', () => {
    // Die leere Menge ist ein gültiger Zustand - dann entscheiden nur Admins.
    // Das muss dastehen, sonst hält man die leere Zelle für einen Datenfehler.
    const c = build([gruppe({ leaderRoleNames: [] })], false);
    c.ngOnInit();

    expect(c.leaderLabels(c.groups()[0])).toEqual(['Ohne Leitung']);
  });

  it('zeigt eine hinterlegte Leitungsrolle als Etikett', () => {
    const c = build([gruppe({ leaderRoleNames: ['ROLE_FC_STRAT'] })], false);
    c.ngOnInit();

    expect(c.leaderLabels(c.groups()[0])).toEqual(['ROLE_FC_STRAT']);
  });

  it('gibt zwei Leitungsrollen zwei eigene Etiketten', () => {
    // "Blops -> FC_Strat UND FC_Skirmish": beide Kreise sind gleichrangig
    // zuständig, eine Rolle genügt zum Entscheiden. Zusammengezogen in ein
    // Etikett läse sich das wie eine Rolle mit einem langen Namen.
    const c = build([gruppe({ leaderRoleNames: ['ROLE_FC_SKIRMISH', 'ROLE_FC_STRAT'] })], false);
    c.ngOnInit();

    expect(c.leaderLabels(c.groups()[0])).toEqual(['ROLE_FC_SKIRMISH', 'ROLE_FC_STRAT']);
  });

  it('behält die Reihenfolge des Servers bei den Etiketten', () => {
    // Sortiert wird im Backend, weil die Rollen dort in einer Menge liegen.
    // Würde hier noch einmal sortiert, gäbe es zwei Ordnungen für dieselbe Liste.
    const c = build([gruppe({ leaderRoleNames: ['ROLE_A', 'ROLE_B', 'ROLE_C'] })], false);
    c.ngOnInit();

    expect(c.leaderLabels(c.groups()[0])).toEqual(['ROLE_A', 'ROLE_B', 'ROLE_C']);
  });

  it('zählt Mitglieder und nicht offene Anfragen', () => {
    // Am Bildschirm stand hier eine 0, während ein Antrag lief. Beides sind
    // verschiedene Zahlen; die Beschriftung muss das aushalten.
    const c = build(
      [
        gruppe({ memberCount: 0, hasPendingRequest: true }),
        gruppe({ id: 2, memberCount: 1 }),
        gruppe({ id: 3, memberCount: 7 }),
      ],
      false,
    );
    c.ngOnInit();

    expect(c.memberLabel(c.groups()[0])).toBe('0 Mitglieder');
    expect(c.memberLabel(c.groups()[1])).toBe('1 Mitglied');
    expect(c.memberLabel(c.groups()[2])).toBe('7 Mitglieder');
  });

  it('macht aus einer fehlenden Zahl keine Null', () => {
    // Die Null wäre eine Aussage ("niemand ist drin"), die ohne Zahl niemand
    // treffen kann. Leerer Text - ob die Zeile überhaupt erscheint, entscheidet
    // ohnehin das Kennzeichen und nicht diese Beschriftung.
    const c = build([gruppe({ canViewMembers: false, memberCount: null })], false);
    c.ngOnInit();

    expect(c.memberLabel(c.groups()[0])).toBe('');
  });

  // ================= Pflege =================

  it('öffnet und schließt das Anlege-Fenster', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();

    c.newGroup();
    expect(c.editingGroup()?.leaderRoleNames).toEqual([]);

    c.closeModal();
    expect(c.editingGroup()).toBeNull();
  });

  it('übernimmt alle Leitungsrollen beim Bearbeiten', () => {
    const c = build([gruppe({ leaderRoleNames: ['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH'] })], true);
    c.ngOnInit();

    c.editGroup(c.groups()[0]);

    expect(c.editingGroup()?.leaderRoleNames).toEqual(['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH']);
  });

  it('lässt die Tabelle unberührt, solange das Fenster offen ist', () => {
    // Ohne Kopie zeigte die Zeile hinter dem Modal die Auswahl schon vor dem
    // Speichern - und ein Abbrechen nähme sie nicht zurück.
    const c = build([gruppe({ leaderRoleNames: ['ROLE_FC_STRAT'] })], true);
    c.ngOnInit();
    c.editGroup(c.groups()[0]);

    c.toggleLeaderRole('ROLE_FC_SKIRMISH');

    expect(c.editingGroup()?.leaderRoleNames).toEqual(['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH']);
    expect(c.groups()[0].leaderRoleNames).toEqual(['ROLE_FC_STRAT']);
  });

  it('hakt eine Leitungsrolle an und wieder ab', () => {
    // Die Mehrfachauswahl ist der ganze Punkt der Umstellung: zwei Kreise
    // nebeneinander ("Direktoren UND CEOs") gehen mit einem Auswahlfeld nicht.
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    c.toggleLeaderRole('ROLE_FC_STRAT');
    expect(c.isLeaderRole('ROLE_FC_STRAT')).toBe(true);

    c.toggleLeaderRole('ROLE_FC_SKIRMISH');
    expect(c.editingGroup()?.leaderRoleNames).toEqual(['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH']);

    c.toggleLeaderRole('ROLE_FC_STRAT');
    expect(c.editingGroup()?.leaderRoleNames).toEqual(['ROLE_FC_SKIRMISH']);
    expect(c.isLeaderRole('ROLE_FC_STRAT')).toBe(false);
  });

  it('hakt nichts an, solange kein Fenster offen ist', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();

    c.toggleLeaderRole('ROLE_FC_STRAT');

    expect(c.editingGroup()).toBeNull();
    expect(c.isLeaderRole('ROLE_FC_STRAT')).toBe(false);
  });

  it('stellt auch eine Leitungsrolle zur Wahl, die der Katalog nicht kennt', () => {
    // Der Katalog führt system_roles, die eingebauten Rollen und die
    // Titel-Zuordnungen zusammen, deckt die FC-Rollen also normalerweise ab.
    // Fällt die Titel-Zuordnung weg, bleibt die Rolle in der Gruppe stehen und
    // fehlt im Katalog - ohne Kästchen fiele sie beim nächsten Speichern still
    // heraus und die Gruppe verlöre ihre Leitung ungefragt.
    const c = build([gruppe({ leaderRoleNames: ['ROLE_FC_STRAT'] })], true);
    groupService.getRoles.mockReturnValue(of([rolle('ROLE_WH')]));
    c.ngOnInit();

    c.editGroup(c.groups()[0]);

    expect(c.leaderRoleChoices()).toEqual(['ROLE_WH', 'ROLE_FC_STRAT']);
    expect(c.isLeaderRole('ROLE_FC_STRAT')).toBe(true);
  });

  it('führt eine Rolle nicht doppelt auf, die im Katalog steht und angehakt ist', () => {
    const c = build([gruppe({ leaderRoleNames: ['ROLE_FC_STRAT'] })], true);
    groupService.getRoles.mockReturnValue(of([rolle('ROLE_FC_STRAT')]));
    c.ngOnInit();

    c.editGroup(c.groups()[0]);

    expect(c.leaderRoleChoices()).toEqual(['ROLE_FC_STRAT']);
  });

  // ================= Vorschlag für die Mitglieds-Rolle =================

  it('schlägt den Rollennamen aus dem Gruppennamen vor', () => {
    // Zeichengenau wie SystemRoles.normalize im Backend: Grossbuchstaben,
    // Trennzeichen zu Unterstrich, Praefix ROLE_. Weicht der Vorschlag ab,
    // steht im Feld ein anderer Name als danach in der Tabelle.
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    c.updateGroupName('Wurmloch SIG');
    expect(c.editingGroup()?.roleName).toBe('ROLE_WURMLOCH_SIG');

    c.updateGroupName('Recruiter (Trial)');
    expect(c.editingGroup()?.roleName).toBe('ROLE_RECRUITER_TRIAL');
  });

  it('verdoppelt ein bereits getipptes ROLE_ nicht', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    c.updateGroupName('ROLE_Blops');

    expect(c.editingGroup()?.roleName).toBe('ROLE_BLOPS');
  });

  it('hört mit dem Vorschlag auf, sobald der Rollenname von Hand steht', () => {
    // Sonst überschriebe ein nachgetragenes Wort im Gruppennamen die Eingabe.
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    c.updateGroupName('Blops');
    c.updateGroupRoleName('ROLE_BLACK_OPS');
    c.updateGroupName('Blops SIG');

    expect(c.editingGroup()?.name).toBe('Blops SIG');
    expect(c.editingGroup()?.roleName).toBe('ROLE_BLACK_OPS');
  });

  it('schlägt beim Bearbeiten nichts vor', () => {
    // Die Rolle ist vergeben: ein mitlaufender Vorschlag benennte sie um, und
    // ihre Träger wären ihre Mitgliedschaft los, ohne ausgetreten zu sein.
    const c = build([gruppe({ name: 'Wurmloch-SIG', roleName: 'ROLE_WH' })], true);
    c.ngOnInit();
    c.editGroup(c.groups()[0]);

    c.updateGroupName('Wurmloch-SIG (alt)');

    expect(c.editingGroup()?.roleName).toBe('ROLE_WH');
  });

  it('sagt, ob die Rolle entsteht oder wiederverwendet wird', () => {
    const c = build([gruppe()], true);
    groupService.getRoles.mockReturnValue(of([rolle('ROLE_WH')]));
    c.ngOnInit();
    c.newGroup();

    c.updateGroupRoleName('ROLE_WH');
    expect(c.effectiveRoleName()).toBe('ROLE_WH');
    expect(c.createsRole()).toBe(false);

    c.updateGroupRoleName('ROLE_BLOPS');
    expect(c.createsRole()).toBe(true);
  });

  it('rechnet ohne Rollennamen mit dem Gruppennamen - wie der Server', () => {
    // Ein leeres Feld heisst nicht "keine Rolle", sondern "leite sie ab".
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    c.updateGroupRoleName('');
    c.updateGroupName('Blops SIG');

    expect(c.effectiveRoleName()).toBe('ROLE_BLOPS_SIG');
    expect(c.createsRole()).toBe(true);
  });

  it('behauptet ohne jede Eingabe keinen Rollennamen', () => {
    // Aus "---" bleibt nach dem Saeubern nichts uebrig; das Backend wirft dort.
    // Hier faellt der Hinweis nur weg, statt einen Namen zu erfinden.
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    expect(c.effectiveRoleName()).toBe('');
    expect(c.createsRole()).toBe(false);

    c.updateGroupName('---');
    expect(c.effectiveRoleName()).toBe('');
  });

  it('rechnet ohne offenes Fenster mit nichts', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();

    expect(c.effectiveRoleName()).toBe('');
    expect(c.createsRole()).toBe(false);
  });

  it('ändert nichts, solange kein Fenster offen ist', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();

    c.updateGroup({ name: 'egal' });

    expect(c.editingGroup()).toBeNull();
  });

  it('lässt die eingebauten Rollen nicht als Leitung zur Wahl stehen', () => {
    // ROLE_USER trägt jeder - als Leitungsrolle dürfte damit jeder über jede
    // Anfrage entscheiden. Der Server weist sie ab, also gar nicht erst zeigen.
    const c = build([gruppe()], true);
    groupService.getRoles.mockReturnValue(of([rolle('ROLE_USER'), rolle('ROLE_FC_STRAT')]));
    c.ngOnInit();

    c.newGroup();

    expect(c.roles().map((r) => r.name)).toEqual(['ROLE_USER', 'ROLE_FC_STRAT']);
    expect(c.leaderRoles().map((r) => r.name)).toEqual(['ROLE_FC_STRAT']);
  });

  it('bietet auch die drei Führungsrollen nicht als Leitung an', () => {
    // Der Server weist sie als eingebaute Rollen ab; sie entscheiden über jede
    // Gruppe ohnehin schon. Angeboten liefe das Speichern in eine Fehlermeldung
    // für eine Auswahl, die die Oberfläche selbst hingestellt hat.
    const c = build([gruppe()], true);
    groupService.getRoles.mockReturnValue(
      of([rolle('ROLE_CEO'), rolle('ROLE_DIRECTOR'), rolle('ROLE_IT_ADMIN'), rolle('ROLE_WH')]),
    );
    c.ngOnInit();

    c.newGroup();

    expect(c.leaderRoles().map((r) => r.name)).toEqual(['ROLE_WH']);
  });

  it('holt den Rollenkatalog nur einmal', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();

    c.newGroup();
    c.editGroup(c.groups()[0]);

    expect(groupService.getRoles).toHaveBeenCalledTimes(1);
  });

  it('probiert den Rollenkatalog beim nächsten Öffnen erneut, wenn er fehlschlug', () => {
    const c = build([gruppe()], true);
    groupService.getRoles.mockReturnValue(fehler());
    c.ngOnInit();

    c.newGroup();
    c.newGroup();

    expect(groupService.getRoles).toHaveBeenCalledTimes(2);
    expect(c.roles()).toEqual([]);
  });

  it('speichert nicht ohne Namen', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();

    c.updateGroup({ name: '   ', roleName: 'ROLE_WH' });
    c.saveGroup();

    expect(groupsService.saveGroup).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('Die Gruppe braucht einen Namen.');
  });

  it('speichert ohne Rollennamen - den leitet der Server ab', () => {
    // Der Knopf darf hier nicht sperren: leer heisst "aus dem Gruppennamen",
    // und genau das steht als Hinweis über den Knöpfen.
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();
    c.updateGroup({ name: 'Blops SIG', roleName: '' });

    c.saveGroup();

    expect(groupsService.saveGroup).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Blops SIG', roleName: '' }),
    );
  });

  it('speichert nichts, solange kein Fenster offen ist', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();

    c.saveGroup();

    expect(groupsService.saveGroup).not.toHaveBeenCalled();
  });

  it('speichert die Gruppe samt allen Leitungsrollen und schließt das Fenster', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();
    c.updateGroupName('Blops SIG');
    c.toggleLeaderRole('ROLE_FC_STRAT');
    c.toggleLeaderRole('ROLE_FC_SKIRMISH');

    c.saveGroup();

    expect(groupsService.saveGroup).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'Blops SIG',
        roleName: 'ROLE_BLOPS_SIG',
        leaderRoleNames: ['ROLE_FC_STRAT', 'ROLE_FC_SKIRMISH'],
      }),
    );
    expect(c.editingGroup()).toBeNull();
    expect(c.saving()).toBe(false);
  });

  it('lässt das Fenster nach einem Fehler offen, damit die Eingabe nicht verloren geht', () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    c.newGroup();
    c.updateGroup({ name: 'Neu', roleName: 'ROLE_WH' });
    groupsService.saveGroup.mockReturnValue(fehler('Name schon vergeben.'));

    c.saveGroup();

    expect(c.editingGroup()).not.toBeNull();
    expect(c.saving()).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('Name schon vergeben.');
  });

  it('fragt vor dem Löschen nach und lädt danach neu', async () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    groupsService.getGroups.mockClear();

    await c.deleteGroup(c.groups()[0]);

    expect(confirm.ask).toHaveBeenCalled();
    expect(groupsService.deleteGroup).toHaveBeenCalledWith(1);
    expect(groupsService.getGroups).toHaveBeenCalledTimes(1);
  });

  it('löscht nichts, wenn die Rückfrage verneint wird', async () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    confirm.ask.mockResolvedValue(false);

    await c.deleteGroup(c.groups()[0]);

    expect(groupsService.deleteGroup).not.toHaveBeenCalled();
  });

  it('meldet ein gescheitertes Löschen', async () => {
    const c = build([gruppe()], true);
    c.ngOnInit();
    groupsService.deleteGroup.mockReturnValue(fehler());

    await c.deleteGroup(c.groups()[0]);

    expect(toast.error).toHaveBeenCalledWith('Gruppe konnte nicht gelöscht werden.');
  });

  // ================= Fehlerpfade beim Laden =================

  it('meldet, wenn die Gruppen nicht kommen, und bleibt bedienbar', () => {
    const c = build([gruppe()], false);
    groupsService.getGroups.mockReturnValue(fehler('Datenbank weg.'));

    c.ngOnInit();

    expect(toast.error).toHaveBeenCalledWith('Datenbank weg.');
    expect(c.loading()).toBe(false);
  });

  it('meldet, wenn die Anfragen nicht kommen', () => {
    const c = build([gruppe({ isLeader: true })], false);
    groupsService.getOpenRequests.mockReturnValue(fehler());

    c.ngOnInit();

    expect(toast.error).toHaveBeenCalledWith('Anfragen konnten nicht geladen werden.');
    expect(c.loadingRequests()).toBe(false);
  });
});
