import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MiningTaxComponent } from './mining-tax.component';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { MiningService } from '../../services/mining.service';
import { ToastService } from '../../services/toast.service';

/** Ein Abrechnungsmonat, wie ihn der Server liefert. */
function month(name: string, totalTax = 1000) {
  return {
    month: name,
    totalTax,
    taxPaid: totalTax,
    isPaid: true,
    details: [
      { typeId: 1230, typeName: 'Veldspar', category: 'ORE', quantity: 100, volume: 10, jitaPrice: 5, taxToPay: 50 },
      { typeId: 1228, typeName: 'Scordite', category: 'ORE', quantity: 50, volume: 5, jitaPrice: 8, taxToPay: 40 },
    ],
  };
}

function leaderRow(mainName: string, volume: number, value: number) {
  return { rank: 1, mainId: 1, mainName, portraitUrl: '', volume, value, units: 1, isMe: false };
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
});
