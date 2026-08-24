import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MiningTaxComponent } from './mining-tax.component';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { MiningService } from '../../services/mining.service';
import { ToastService } from '../../services/toast.service';
import { formatIskCents } from '../../shared/eve-format.util';

/**
 * Ein Abrechnungsmonat, wie ihn der Server liefert - vollständig überwiesen.
 *
 * <p>`creditApplied`, `amountDue` und `isPaid` kommen fertig gerechnet vom
 * Server; die Tests setzen sie deshalb von Hand statt sie aus den anderen
 * Feldern abzuleiten. Täten sie das, prüften sie am Ende ihre eigene Ableitung
 * und nicht mehr das, was die Oberfläche vom Server bekommt.</p>
 */
function month(name: string, totalTax = 1000) {
  return {
    month: name,
    totalTax,
    taxPaid: totalTax,
    isPaid: true,
    creditApplied: 0,
    appliedCredits: [] as ReturnType<typeof appliedCredit>[],
    amountDue: 0,
    details: [
      { typeId: 1230, typeName: 'Veldspar', category: 'ORE', quantity: 100, volume: 10, jitaPrice: 5, taxToPay: 50 },
      { typeId: 1228, typeName: 'Scordite', category: 'ORE', quantity: 50, volume: 5, jitaPrice: 8, taxToPay: 40 },
    ],
  };
}

/**
 * Der Anteil einer Gutschrift an einem Monat - der Beleg hinter einem
 * nachgetragenen Monat.
 *
 * <p>`applied` und `amount` sind getrennt einstellbar, weil eine Buchung über
 * mehrere Monate reicht: nur so lässt sich prüfen, dass die Oberfläche einen
 * Teilanteil auch als Teilanteil ausweist.</p>
 */
function appliedCredit(overrides: Record<string, unknown> = {}) {
  return {
    creditId: 7,
    applied: 1000,
    amount: 1000,
    actorCharacterId: 99,
    actorName: 'Director One',
    reason: 'hatte am 03.01. überwiesen, nicht erkannt',
    occurredAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

function leaderRow(mainName: string, volume: number, value: number) {
  return { rank: 1, mainId: 1, mainName, portraitUrl: '', volume, value, units: 1, isMe: false };
}

/** Eine Buchung aus dem Gutschriftenverlauf. */
function credit(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    accountId: 42,
    accountName: 'Miner Prime',
    portraitUrl: '',
    amount: 250_000_000,
    status: 'ACTIVE',
    reversalOfCreditId: null,
    actorCharacterId: 99,
    actorName: 'Director One',
    selfGranted: false,
    reason: 'Moon-Anteil',
    occurredAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

/** Die Steuerakte, wie sie der Server für eine angeklickte Bilanzzeile liefert. */
function memberLedger(overrides: Record<string, unknown> = {}) {
  return {
    accountId: 42,
    accountName: 'Miner Prime',
    portraitUrl: '',
    totalTax: 2000,
    totalPaid: 500,
    totalCredited: 250_000_000,
    currentBalance: -1500,
    months: [month('2026-08'), month('2026-07')],
    credits: [credit()],
    ...overrides,
  };
}

describe('MiningTaxComponent', () => {
  let component: MiningTaxComponent;
  let miningService: Record<string, ReturnType<typeof vi.fn>>;
  let toastService: Record<string, ReturnType<typeof vi.fn>>;
  let confirmService: { ask: ReturnType<typeof vi.fn> };

  const rate = {
    typeId: 1230,
    typeName: 'Veldspar',
    category: 'ORE',
    taxPercentage: 10,
    currentJitaBuy: 5,
  };

  beforeEach(() => {
    miningService = {
      getMyLedger: vi.fn().mockReturnValue(
        of({ totalDebt: 2000, totalPaid: 2000, currentBalance: 0, months: [month('2026-08'), month('2026-07')] }),
      ),
      getLeaderboard: vi.fn().mockReturnValue(
        of({
          month: '2026-08',
          availableMonths: ['2026-08', '2026-07'],
          totalVolume: 300,
          totalValue: 3000,
          rows: [leaderRow('Alpha', 200, 2000), leaderRow('Beta', 100, 1000)],
        }),
      ),
      getTaxRates: vi.fn().mockReturnValue(of([rate])),
      getAdminLedgers: vi.fn().mockReturnValue(of([{ mainId: 1, mainName: 'A', currentBalance: -1 }])),
      saveTaxRate: vi.fn().mockReturnValue(of(rate)),
      saveBulkTax: vi.fn().mockReturnValue(of(null)),
      deleteTaxRate: vi.fn().mockReturnValue(of(null)),
      getMemberLedger: vi.fn().mockReturnValue(of(memberLedger())),
      grantCredit: vi.fn().mockReturnValue(of(credit())),
      reverseCredit: vi.fn().mockReturnValue(of(credit({ id: 8, amount: -250_000_000, status: 'REVERSAL' }))),
    };
    toastService = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    confirmService = { ask: vi.fn().mockResolvedValue(true) };

    TestBed.configureTestingModule({
      providers: [
        { provide: MiningService, useValue: miningService },
        { provide: ToastService, useValue: toastService },
        { provide: ConfirmService, useValue: confirmService },
        { provide: AuthService, useValue: { hasAnyRole: vi.fn().mockReturnValue(true) } },
      ],
    });
    component = TestBed.runInInjectionContext(() => new MiningTaxComponent());
  });

  describe('Eigene Bilanz', () => {
    it('lädt die Bilanz beim Start und zeigt den neuesten Monat', () => {
      component.ngOnInit();

      expect(component.myLedger()).toHaveLength(2);
      expect(component.currentMonthLedger()?.month).toBe('2026-08');
      expect(component.loadingLedger()).toBe(false);
    });

    it('blättert zwischen den Monaten, ohne über die Grenzen zu laufen', () => {
      component.ngOnInit();

      component.olderMonth();
      expect(component.currentMonthLedger()?.month).toBe('2026-07');

      component.olderMonth();
      expect(component.currentMonthLedger()?.month).toBe('2026-07');

      component.newerMonth();
      expect(component.currentMonthLedger()?.month).toBe('2026-08');

      component.newerMonth();
      expect(component.selectedMonthIndex()).toBe(0);
    });

    it('summiert das Volumen eines Monats', () => {
      expect(component.getTotalVolume(month('2026-08').details)).toBe(15);
    });

    it('nennt den fälligen Rest auf den Cent genau, so wie der Server ihn schickt', () => {
      // Ohne die Nachkommastellen sähe das Mitglied "5.138.868 ISK" und
      // überwiese das - die Rechnung bliebe mit 32 Cent offen stehen, und
      // niemand auf dem Bildschirm könnte erklären, warum.
      const teilweiseBezahlt = {
        ...month('2026-08', 6_138_868.42),
        taxPaid: 1_000_000.1,
        isPaid: false,
        creditApplied: 0,
        amountDue: 5_138_868.32,
      };

      expect(component.needsTransfer(teilweiseBezahlt)).toBe(true);
      expect(formatIskCents(teilweiseBezahlt.amountDue)).toBe('5.138.868,32 ISK');
    });

    it('fordert nichts ein, wenn eine Gutschrift den Monat nachträgt', () => {
      // Der gemeldete Widerspruch: 461 Mio. Guthaben oben, darunter die
      // Aufforderung, 28,9 Mio. zu überweisen.
      //
      // OHNE DIESE REGEL: Früher stand hier die Zusicherung "der Status bleibt
      // OFFEN". Die galt einer Gutschrift als Zuwendung. Eine Gutschrift ist
      // aber eine KORREKTUR - das Mitglied hat überwiesen, das Werkzeug hat es
      // nicht erkannt, und die Buchung trägt genau das nach. Einen so
      // gedeckten Monat weiter als offen zu führen hiesse, auf einer Schuld zu
      // bestehen, die jemand ausdrücklich für beglichen erklärt hat.
      const nachgetragen = {
        ...month('2026-08', 28_931_067),
        taxPaid: 0,
        isPaid: true,
        creditApplied: 28_931_067,
        appliedCredits: [appliedCredit({ applied: 28_931_067, amount: 28_931_067 })],
        amountDue: 0,
      };

      expect(component.needsTransfer(nachgetragen)).toBe(false);
      expect(component.hasBackfill(nachgetragen)).toBe(true);
      expect(nachgetragen.isPaid).toBe(true);
    });

    it('hält erkannte Überweisung und Nachtrag auseinander', () => {
      // OHNE DIESE REGEL wäre am Status nicht mehr zu erkennen, ob wirklich
      // Geld geflossen ist oder ob jemand den Monat per Eintrag geschlossen
      // hat - beide zeigen BEZAHLT. Die Aufschlüsselung ist die einzige
      // Stelle, an der die Herkunft noch steht, und der Beleg gehört dazu:
      // wer hat wann und mit welcher Begründung nachgetragen.
      const gemischt = {
        ...month('2026-08', 3000),
        taxPaid: 1000,
        isPaid: true,
        creditApplied: 2000,
        appliedCredits: [appliedCredit({ applied: 2000, amount: 2000 })],
        amountDue: 0,
      };

      expect(component.hasBackfill(gemischt)).toBe(true);
      expect(formatIskCents(gemischt.taxPaid)).toBe('1.000,00 ISK');
      expect(formatIskCents(gemischt.creditApplied)).toBe('2.000,00 ISK');

      const beleg = gemischt.appliedCredits[0];
      expect(beleg.actorName).toBe('Director One');
      expect(beleg.reason).toBe('hatte am 03.01. überwiesen, nicht erkannt');
      expect(beleg.occurredAt).toBe('2026-08-20T10:00:00Z');
    });

    it('fordert bei teilweisem Nachtrag nur den Rest ein, nicht den Monatsbetrag', () => {
      // 1.000 nachgetragen auf 3.000 Steuer: fällig sind 2.000. Stünde hier
      // der volle Monatsbetrag, überwiese das Mitglied 1.000 zu viel. Der
      // Monat bleibt offen, weil die Deckung nicht reicht.
      const teilweiseNachgetragen = {
        ...month('2026-08', 3000),
        taxPaid: 0,
        isPaid: false,
        creditApplied: 1000,
        appliedCredits: [appliedCredit({ applied: 1000, amount: 1000 })],
        amountDue: 2000,
      };

      expect(component.needsTransfer(teilweiseNachgetragen)).toBe(true);
      expect(component.hasBackfill(teilweiseNachgetragen)).toBe(true);
      expect(formatIskCents(teilweiseNachgetragen.amountDue)).toBe('2.000,00 ISK');
    });

    it('weist eine über mehrere Monate verteilte Buchung als Teilanteil aus', () => {
      // OHNE DIESE REGEL läse sich derselbe Beleg in zwei Monaten wie zwei
      // getrennte Nachträge, und die Summe der Gutschriften schiene doppelt so
      // hoch wie die eine Buchung, die tatsächlich vorliegt.
      expect(component.isPartialCredit(appliedCredit({ applied: 400, amount: 1000 }))).toBe(true);
    });

    it('wiederholt bei einer voll verbrauchten Buchung nicht denselben Betrag', () => {
      expect(component.isPartialCredit(appliedCredit({ applied: 1000, amount: 1000 }))).toBe(false);
    });

    it('nennt keinen Nachtrag, wo keiner gebucht wurde', () => {
      // Sonst stünde bei jedem gewöhnlichen Monat eine Zeile "Nachgetragen:
      // 0,00 ISK", die nichts erklärt.
      const ohneNachtrag = { ...month('2026-08', 1000), taxPaid: 0, isPaid: false, amountDue: 1000 };

      expect(component.hasBackfill(ohneNachtrag)).toBe(false);
      expect(component.needsTransfer(ohneNachtrag)).toBe(true);
    });

    it('fordert nichts ein, wenn der Monat überwiesen ist', () => {
      // Der Normalfall: erkannte Überweisung, kein Nachtrag, nichts offen.
      expect(component.needsTransfer(month('2026-08'))).toBe(false);
      expect(component.hasBackfill(month('2026-08'))).toBe(false);
    });

    it('bleibt bei einem Fehler bedienbar', () => {
      miningService['getMyLedger'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadUserLedger();

      expect(component.loadingLedger()).toBe(false);
    });
  });

  describe('Rangliste', () => {
    it('lädt die Rangliste erst beim Aufklappen', () => {
      // Sie soll die Seite nicht ausbremsen.
      expect(miningService['getLeaderboard']).not.toHaveBeenCalled();

      component.toggleLeaderboard();

      expect(component.showLeaderboard()).toBe(true);
      expect(miningService['getLeaderboard']).toHaveBeenCalled();
    });

    it('lädt sie beim erneuten Aufklappen nicht noch einmal', () => {
      component.toggleLeaderboard();
      component.toggleLeaderboard();
      miningService['getLeaderboard'].mockClear();

      component.toggleLeaderboard();

      expect(miningService['getLeaderboard']).not.toHaveBeenCalled();
    });

    it('übernimmt die Monatswahl des Servers beim ersten Laden', () => {
      component.loadLeaderboard();

      expect(component.selectedLeaderMonth).toBe('2026-08');
      expect(component.loadingLeaderboard()).toBe(false);
    });

    it('lädt beim Monatswechsel neu', () => {
      component.selectedLeaderMonth = '2026-07';

      component.onLeaderMonthChange();

      expect(miningService['getLeaderboard']).toHaveBeenCalledWith('2026-07');
    });

    it('meldet einen Fehlschlag der Rangliste', () => {
      miningService['getLeaderboard'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadLeaderboard();

      expect(toastService['error']).toHaveBeenCalled();
      expect(component.loadingLeaderboard()).toBe(false);
    });

    it('schaltet zwischen Volumen und Wert um', () => {
      component.loadLeaderboard();

      expect(component.leaderValue(200, 2000)).toBe(200);

      component.setLeaderMetric('VALUE');
      expect(component.leaderValue(200, 2000)).toBe(2000);
    });

    it('bemisst den Balken am größten Wert der Liste', () => {
      component.loadLeaderboard();

      expect(component.leaderBarWidth(200, 2000)).toBe('100.0%');
      expect(component.leaderBarWidth(100, 1000)).toBe('50.0%');
    });

    it('lässt einen Balken nie ganz verschwinden', () => {
      component.loadLeaderboard();

      expect(component.leaderBarWidth(0.01, 0.1)).toBe('1.5%');
    });

    it('rechnet den Anteil an der Gesamtmenge aus', () => {
      component.loadLeaderboard();

      expect(component.leaderShare(150, 1500)).toBe('50.0 %');
    });

    it('kommt ohne Daten ohne Division durch null aus', () => {
      expect(component.leaderBarWidth(1, 1)).toBe('0%');
      expect(component.leaderShare(1, 1)).toBe('0 %');
    });

    it('formatiert den Wert je nach aktiver Metrik', () => {
      expect(component.formatLeaderValue(1500, 2_000_000)).toContain('m³');

      component.setLeaderMetric('VALUE');
      expect(component.formatLeaderValue(1500, 2_000_000)).toContain('ISK');
    });
  });

  describe('Verwaltung der Steuersätze', () => {
    it('lädt Sätze und Bilanzen je nach Reiter', () => {
      component.setTab('ADMIN');
      expect(miningService['getTaxRates']).toHaveBeenCalled();

      component.setTab('LEDGERS');
      expect(miningService['getAdminLedgers']).toHaveBeenCalled();

      component.setTab('USER');
      expect(miningService['getMyLedger']).toHaveBeenCalled();
    });

    it('bleibt bei Fehlern bedienbar', () => {
      miningService['getTaxRates'].mockReturnValue(throwError(() => new Error('kaputt')));
      miningService['getAdminLedgers'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.loadTaxRates();
      component.loadAdminLedgers();

      expect(component.loadingTaxes()).toBe(false);
      expect(component.loadingAdminLedgers()).toBe(false);
    });

    it('speichert den Satz des gewählten Erzes', () => {
      component.loadTaxRates();
      component.selectedTypeId = 1230 as never;
      component.newTaxPercentage = 12.5;

      component.saveTaxRateFromDropdown();

      expect(miningService['saveTaxRate']).toHaveBeenCalledWith(
        expect.objectContaining({ typeId: 1230, taxPercentage: 12.5 }),
      );
      expect(component.selectedTypeId).toBeNull();
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('speichert nichts ohne Auswahl', () => {
      component.selectedTypeId = null as never;

      component.saveTaxRateFromDropdown();

      expect(miningService['saveTaxRate']).not.toHaveBeenCalled();
    });

    it('speichert nichts für ein unbekanntes Erz', () => {
      component.loadTaxRates();
      component.selectedTypeId = 99999 as never;

      component.saveTaxRateFromDropdown();

      expect(miningService['saveTaxRate']).not.toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag beim Speichern', () => {
      miningService['saveTaxRate'].mockReturnValue(throwError(() => new Error('kaputt')));
      component.loadTaxRates();
      component.selectedTypeId = 1230 as never;

      component.saveTaxRateFromDropdown();

      expect(toastService['error']).toHaveBeenCalled();
    });

    it('setzt einen Satz für eine ganze Klasse', () => {
      component.bulkCategory = 'ICE';
      component.bulkTaxPercentage = 8;

      component.saveBulkTax();

      expect(miningService['saveBulkTax']).toHaveBeenCalledWith('ICE', 8);
      expect(component.bulkTaxPercentage).toBe(0);
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('weist einen negativen Satz ab', () => {
      component.bulkTaxPercentage = -5;

      component.saveBulkTax();

      expect(miningService['saveBulkTax']).not.toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag beim Massen-Update', () => {
      miningService['saveBulkTax'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.saveBulkTax();

      expect(toastService['error']).toHaveBeenCalled();
    });

    it('löscht einen Satz erst nach Rückfrage', async () => {
      await component.deleteTaxRate(1230);

      expect(confirmService.ask).toHaveBeenCalled();
      expect(miningService['deleteTaxRate']).toHaveBeenCalledWith(1230);
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('löscht nichts, wenn die Rückfrage verneint wird', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.deleteTaxRate(1230);

      expect(miningService['deleteTaxRate']).not.toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag beim Löschen', async () => {
      miningService['deleteTaxRate'].mockReturnValue(throwError(() => new Error('kaputt')));

      await component.deleteTaxRate(1230);

      expect(toastService['error']).toHaveBeenCalled();
    });

    it('meldet die Rechte für die Oberfläche', () => {
      expect(component.isLeadership).toBe(true);
    });
  });

  describe('Steuerakte eines Members', () => {
    it('öffnet die Akte beim Klick auf eine Bilanzzeile', () => {
      component.openMember(42);

      expect(miningService['getMemberLedger']).toHaveBeenCalledWith(42);
      expect(component.selectedMember()?.accountName).toBe('Miner Prime');
      expect(component.memberMonthIndex()).toBe(0);
      expect(component.loadingMember()).toBe(false);
    });

    it('leert das Betragsfeld beim Wechsel des Members', () => {
      // Ein stehengebliebener Betrag stünde beim nächsten Klick vor einem
      // anderen Namen - die Rückfrage nennt zwar den richtigen, aber der
      // Finger ist schneller als das Lesen.
      component.openMember(42);
      component.creditAmount = '999';
      component.creditReason = 'alter Grund';

      component.openMember(7);

      expect(component.creditAmount).toBe('');
      expect(component.creditReason).toBe('');
    });

    it('meldet einen Fehlschlag mit der Begründung des Servers', () => {
      miningService['getMemberLedger'].mockReturnValue(
        throwError(() => ({ error: { message: 'Account unbekannt' } })),
      );

      component.openMember(42);

      expect(toastService['error']).toHaveBeenCalledWith('Account unbekannt');
      expect(component.loadingMember()).toBe(false);
    });

    it('meldet einen Fehlschlag auch ohne Begründung', () => {
      miningService['getMemberLedger'].mockReturnValue(throwError(() => new Error('kaputt')));

      component.openMember(42);

      expect(toastService['error']).toHaveBeenCalledWith(
        'Die Steuerakte konnte nicht geladen werden.',
      );
    });

    it('schließt die Akte wieder', () => {
      component.openMember(42);

      component.closeMember();

      expect(component.selectedMember()).toBeNull();
      expect(component.memberMonth()).toBeNull();
    });

    it('markiert nur die geöffnete Zeile', () => {
      expect(component.isSelectedMember(42)).toBe(false);

      component.openMember(42);

      expect(component.isSelectedMember(42)).toBe(true);
      expect(component.isSelectedMember(7)).toBe(false);
    });

    it('blättert durch die Monate der Akte, ohne über die Grenzen zu laufen', () => {
      component.openMember(42);

      component.olderMemberMonth();
      expect(component.memberMonth()?.month).toBe('2026-07');

      component.olderMemberMonth();
      expect(component.memberMonth()?.month).toBe('2026-07');

      component.newerMemberMonth();
      expect(component.memberMonth()?.month).toBe('2026-08');

      component.newerMemberMonth();
      expect(component.memberMonthIndex()).toBe(0);
    });

    it('blättert ins Leere, solange keine Akte offen ist', () => {
      component.olderMemberMonth();
      component.newerMemberMonth();

      expect(component.memberMonthIndex()).toBe(0);
      expect(component.memberMonth()).toBeNull();
    });

    it('kommt mit einer Akte ohne Abrechnungsmonat aus', () => {
      miningService['getMemberLedger'].mockReturnValue(of(memberLedger({ months: [] })));

      component.openMember(42);

      expect(component.memberMonth()).toBeNull();
    });

    it('rechnet den Anteil eines Erzes an der Monatssteuer aus', () => {
      const details = month('2026-08').details;

      // 50 von 90 ISK Steuer - das ist die "Zusammensetzung", nach der gefragt war.
      expect(component.getTotalTaxOfMonth(details)).toBe(90);
      expect(component.taxShare(details[0], details)).toBe('55.6 %');
    });

    it('kommt ohne Steuer ohne Division durch null aus', () => {
      const details = [{ ...month('2026-08').details[0], taxToPay: 0 }];

      expect(component.taxShare(details[0], details)).toBe('0 %');
    });
  });

  describe('Gutschriften', () => {
    beforeEach(() => component.openMember(42));

    it('fragt vor dem Speichern mit BETRAG und NAME zurück', async () => {
      component.creditAmount = '250000000';

      await component.grantCredit();

      const [, message] = confirmService.ask.mock.calls[0];
      expect(message).toContain('250000000');
      expect(message).toContain('Miner Prime');
      expect(miningService['grantCredit']).toHaveBeenCalledWith(42, '250000000', null);
    });

    it('nennt in der Rückfrage genau den eingetippten Betrag', async () => {
      // Nicht einen hier formatierten: die Regeln des Servers ein zweites Mal
      // aufzuschreiben hiesse, dass der Nutzer irgendwann einen Betrag
      // bestätigt und der Server einen anderen bucht.
      component.creditAmount = '  12.500.000,50  ';

      await component.grantCredit();

      expect(confirmService.ask.mock.calls[0][1]).toContain('12.500.000,50');
      expect(miningService['grantCredit']).toHaveBeenCalledWith(42, '12.500.000,50', null);
    });

    it('reicht den Grund weiter und leert danach beide Felder', async () => {
      component.creditAmount = '500';
      component.creditReason = ' Moon-Anteil ';

      await component.grantCredit();

      expect(miningService['grantCredit']).toHaveBeenCalledWith(42, '500', 'Moon-Anteil');
      expect(component.creditAmount).toBe('');
      expect(component.creditReason).toBe('');
      expect(component.grantingCredit()).toBe(false);
      expect(toastService['success']).toHaveBeenCalled();
    });

    it('lädt Akte und Bilanz nach der Buchung neu', () => {
      // Sonst zeigten zwei Zahlen auf demselben Bildschirm ein
      // unterschiedliches Guthaben für denselben Account.
      miningService['getMemberLedger'].mockClear();
      miningService['getAdminLedgers'].mockClear();
      component.creditAmount = '500';

      return component.grantCredit().then(() => {
        expect(miningService['getMemberLedger']).toHaveBeenCalledWith(42);
        expect(miningService['getAdminLedgers']).toHaveBeenCalled();
      });
    });

    it('bucht nichts ohne Betrag', async () => {
      component.creditAmount = '   ';

      await component.grantCredit();

      expect(confirmService.ask).not.toHaveBeenCalled();
      expect(miningService['grantCredit']).not.toHaveBeenCalled();
      expect(toastService['error']).toHaveBeenCalled();
    });

    it('bucht nichts ohne geöffnete Akte', async () => {
      component.closeMember();
      component.creditAmount = '500';

      await component.grantCredit();

      expect(miningService['grantCredit']).not.toHaveBeenCalled();
    });

    it('bucht nichts, wenn die Rückfrage verneint wird', async () => {
      confirmService.ask.mockResolvedValue(false);
      component.creditAmount = '500';

      await component.grantCredit();

      expect(miningService['grantCredit']).not.toHaveBeenCalled();
      expect(component.creditAmount).toBe('500');
    });

    it('zeigt die Begründung des Servers, wenn der Betrag abgelehnt wird', async () => {
      // Dort steht, WARUM - etwa dass "12.500" mehrdeutig ist. Ohne den Text
      // tippt der Nutzer dasselbe noch einmal.
      miningService['grantCredit'].mockReturnValue(
        throwError(() => ({ error: { message: '"12.500" ist mehrdeutig' } })),
      );
      component.creditAmount = '12.500';

      await component.grantCredit();

      expect(toastService['error']).toHaveBeenCalledWith('"12.500" ist mehrdeutig');
      expect(component.grantingCredit()).toBe(false);
      expect(component.creditAmount).toBe('12.500');
    });

    it('meldet einen Fehlschlag auch ohne Begründung', async () => {
      miningService['grantCredit'].mockReturnValue(throwError(() => new Error('kaputt')));
      component.creditAmount = '500';

      await component.grantCredit();

      expect(toastService['error']).toHaveBeenCalledWith(
        'Die Gutschrift konnte nicht gebucht werden.',
      );
    });

    it('fragt vor der Rücknahme mit BETRAG und NAME zurück', async () => {
      await component.reverseCredit(credit());

      const [, message] = confirmService.ask.mock.calls[0];
      expect(message).toContain('250.000.000,00 ISK');
      expect(message).toContain('Miner Prime');
      expect(message).toContain('Es wird kein Grund festgehalten.');
      expect(miningService['reverseCredit']).toHaveBeenCalledWith(7, null);
      expect(toastService['info']).toHaveBeenCalled();
    });

    it('nennt einen stehengebliebenen Grund wörtlich in der Rückfrage', async () => {
      // Sonst klebte ein Text aus dem Vergabeformular unbemerkt an der Rücknahme.
      component.creditReason = 'doppelt gebucht';

      await component.reverseCredit(credit());

      expect(confirmService.ask.mock.calls[0][1]).toContain('"doppelt gebucht"');
      expect(miningService['reverseCredit']).toHaveBeenCalledWith(7, 'doppelt gebucht');
    });

    it('nimmt nichts zurück, wenn die Rückfrage verneint wird', async () => {
      confirmService.ask.mockResolvedValue(false);

      await component.reverseCredit(credit());

      expect(miningService['reverseCredit']).not.toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag der Rücknahme', async () => {
      miningService['reverseCredit'].mockReturnValue(
        throwError(() => ({ error: { message: 'bereits zurückgenommen' } })),
      );

      await component.reverseCredit(credit());

      expect(toastService['error']).toHaveBeenCalledWith('bereits zurückgenommen');
    });

    it('meldet einen Fehlschlag der Rücknahme auch ohne Begründung', async () => {
      miningService['reverseCredit'].mockReturnValue(throwError(() => new Error('kaputt')));

      await component.reverseCredit(credit());

      expect(toastService['error']).toHaveBeenCalledWith(
        'Die Gutschrift konnte nicht zurückgenommen werden.',
      );
    });

    it('bietet die Rücknahme nur für gültige Buchungen an', () => {
      // Eine Gegenbuchung gegenzubuchen wäre eine Gutschrift, die niemand
      // beziffert hat; eine bereits zurückgenommene ist erledigt.
      expect(component.isReversible(credit())).toBe(true);
      expect(component.isReversible(credit({ status: 'REVERSED' }))).toBe(false);
      expect(component.isReversible(credit({ status: 'REVERSAL' }))).toBe(false);
    });

    it('benennt die drei Zustände einer Buchung', () => {
      expect(component.creditStatusLabel('ACTIVE')).toBe('gültig');
      expect(component.creditStatusLabel('REVERSED')).toBe('zurückgenommen');
      expect(component.creditStatusLabel('REVERSAL')).toBe('Gegenbuchung');
    });
  });
});
