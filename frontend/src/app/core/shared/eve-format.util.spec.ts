import { describe, expect, it } from 'vitest';
import {
  barWidth,
  formatCompact,
  formatIsk,
  formatIskCents,
  formatIskFull,
  formatMonthLabel,
  formatNumber,
  formatVolume,
  formatVolumeCompact,
  maxValue,
} from './eve-format.util';

describe('Zahlenformatierung', () => {
  describe('formatIsk (kompakt)', () => {
    it('kürzt große Beträge auf zwei Nachkommastellen', () => {
      expect(formatIsk(1_250_000_000_000)).toBe('1.25 T ISK');
      expect(formatIsk(1_250_000_000)).toBe('1.25 B ISK');
      expect(formatIsk(1_250_000)).toBe('1.25 M ISK');
      expect(formatIsk(1_250)).toBe('1.3 k ISK');
    });

    it('zeigt kleine Beträge ohne Einheit-Präfix', () => {
      expect(formatIsk(999)).toBe('999 ISK');
      expect(formatIsk(0)).toBe('0 ISK');
    });

    it('kürzt negative Beträge genauso', () => {
      // Ein Minus im Kontostand ist der Normalfall bei offenen Steuern.
      expect(formatIsk(-1_250_000_000)).toBe('-1.25 B ISK');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatIsk(null)).toBe('0 ISK');
      expect(formatIsk(undefined)).toBe('0 ISK');
      expect(formatIsk(NaN)).toBe('0 ISK');
    });
  });

  describe('formatIskFull', () => {
    it('zeigt den vollen Betrag mit Tausenderpunkten', () => {
      expect(formatIskFull(1_250_000)).toBe('1.250.000 ISK');
    });

    it('rundet auf ganze ISK', () => {
      // Absicht, und deshalb nur noch für geschätzte Besitzwerte: die stammen
      // aus market_prices und sind dort double. Zwei Nachkommastellen wären
      // dort behauptete Genauigkeit.
      expect(formatIskFull(1234.56)).toBe('1.235 ISK');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatIskFull(null)).toBe('0 ISK');
      expect(formatIskFull(NaN)).toBe('0 ISK');
    });
  });

  describe('formatIskCents', () => {
    it('behält beide Nachkommastellen', () => {
      // Ohne diese Funktion machte formatIskFull aus einer Zusage über 5,50
      // eine über 6 - eine andere Zahl als die, die jemand versprochen hat.
      expect(formatIskCents(1_250_000.5)).toBe('1.250.000,50 ISK');
      expect(formatIskCents(5.5)).toBe('5,50 ISK');
    });

    it('trägt eine gerechnete Steuer bis in die letzte Stelle', () => {
      // Der Betrag stammt aus MiningLedgerServiceTest.billionsStayExactToTheIsk:
      // 87.654 x 210.200,55 x 10 %. Der Server führt ihn als BigDecimal, damit
      // genau diese 97 Cent nicht verlorengehen - würde die Oberfläche sie hier
      // wieder wegrunden, wäre die ganze Umstellung im Server folgenlos.
      expect(formatIskCents(1_842_491_900.97)).toBe('1.842.491.900,97 ISK');
    });

    it('zeigt eine offene Restschuld unter einer ISK als offen an', () => {
      // Auf ganze ISK gerundet stünde hier "0 ISK" - der Bildschirm meldete
      // beglichen, während der Server den Monat offen führt.
      expect(formatIskCents(0.4)).toBe('0,40 ISK');
    });

    it('ergänzt fehlende Nachkommastellen', () => {
      expect(formatIskCents(250_000_000)).toBe('250.000.000,00 ISK');
    });

    it('zeigt eine Gegenbuchung als negativen Betrag', () => {
      expect(formatIskCents(-12_500.25)).toBe('-12.500,25 ISK');
    });

    it('trägt die Obergrenze des Servers ohne Stellenverlust', () => {
      // 10^12 ist die Grenze je Buchung. Ein double trägt dort noch jeden Cent -
      // sein Fehler liegt bei rund 0,0002 ISK und damit weit unter dem halben
      // Cent, ab dem die zweite Nachkommastelle kippen würde.
      expect(formatIskCents(999_999_999_999.99)).toBe('999.999.999.999,99 ISK');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatIskCents(null)).toBe('0,00 ISK');
      expect(formatIskCents(undefined)).toBe('0,00 ISK');
      expect(formatIskCents(NaN)).toBe('0,00 ISK');
    });
  });

  describe('formatNumber', () => {
    it('setzt Tausenderpunkte', () => {
      expect(formatNumber(1_234_567)).toBe('1.234.567');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatNumber(undefined)).toBe('0');
    });
  });

  describe('Volumen', () => {
    it('zeigt das exakte Volumen in Kubikmetern', () => {
      expect(formatVolume(1_234)).toBe('1.234 m³');
    });

    it('kürzt große Volumina', () => {
      expect(formatVolumeCompact(1_500_000)).toBe('1.50 Mio m³');
      expect(formatVolumeCompact(1_500)).toBe('1.5 k m³');
      expect(formatVolumeCompact(150)).toBe('150 m³');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatVolume(null)).toBe('0 m³');
      expect(formatVolumeCompact(null)).toBe('0 m³');
    });
  });

  describe('formatCompact', () => {
    it('kürzt ohne Einheit', () => {
      expect(formatCompact(2_500_000_000)).toBe('2.50 B');
      expect(formatCompact(42)).toBe('42');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatCompact(null)).toBe('0');
    });
  });

  describe('barWidth', () => {
    it('rechnet den Anteil am größten Wert in Prozent', () => {
      expect(barWidth(50, 100)).toBe('50.0%');
      expect(barWidth(100, 100)).toBe('100.0%');
    });

    it('lässt einen Balken nie ganz verschwinden', () => {
      // Sonst wirkt die Zeile leer, obwohl ein Wert vorhanden ist.
      expect(barWidth(1, 1_000_000)).toBe('2.0%');
    });

    it('gibt ohne Bezugsgröße keine Breite aus', () => {
      expect(barWidth(50, 0)).toBe('0%');
    });
  });

  describe('maxValue', () => {
    it('findet den größten Wert einer Reihe', () => {
      expect(maxValue([{ value: 5 }, { value: 42 }, { value: 13 }])).toBe(42);
    });

    it('gibt für eine leere Reihe null zurück', () => {
      expect(maxValue([])).toBe(0);
      expect(maxValue(undefined)).toBe(0);
    });
  });

  describe('formatMonthLabel', () => {
    it('schreibt den Monat aus', () => {
      expect(formatMonthLabel('2026-08')).toBe('August 2026');
      expect(formatMonthLabel('2026-01')).toBe('Januar 2026');
    });

    it('benennt den Gesamtzeitraum', () => {
      expect(formatMonthLabel('ALL')).toBe('Gesamter Zeitraum');
      expect(formatMonthLabel('')).toBe('Gesamter Zeitraum');
    });

    it('gibt einen unbekannten Schlüssel unverändert zurück', () => {
      expect(formatMonthLabel('2026-99')).toBe('2026-99 2026');
    });
  });
});
