import { describe, expect, it } from 'vitest';
import {
  barWidth,
  formatCompact,
  formatIsk,
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
    it('zeigt den exakten Betrag mit Tausenderpunkten', () => {
      expect(formatIskFull(1_250_000)).toBe('1.250.000 ISK');
    });

    it('rundet auf ganze ISK', () => {
      expect(formatIskFull(1234.56)).toBe('1.235 ISK');
    });

    it('behandelt fehlende Werte als null', () => {
      expect(formatIskFull(null)).toBe('0 ISK');
      expect(formatIskFull(NaN)).toBe('0 ISK');
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
