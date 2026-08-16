/**
 * Zahlenformatierung für die Oberfläche.
 *
 * <p>Diese Funktionen lagen zuvor als Methoden in vier Komponenten - teils
 * wortgleich, teils mit abweichenden Namen für dasselbe Ergebnis. Hier steht
 * jede Darstellung genau einmal; die Komponenten reichen sie nur noch als Feld
 * an ihr Template durch.</p>
 */

const LOCALE = 'de-DE';

const THOUSAND = 1_000;
const MILLION = 1_000_000;
const BILLION = 1_000_000_000;
const TRILLION = 1_000_000_000_000;

/** Balken dürfen nie ganz verschwinden, sonst wirkt die Zeile leer. */
const MIN_BAR_PERCENT = 2;

type Numeric = number | null | undefined;

function isMissing(value: Numeric): value is null | undefined {
  return value === null || value === undefined || isNaN(value);
}

/**
 * Kompakte ISK-Angabe, etwa `1,25 B ISK`.
 *
 * <p>Für Tabellen und Balken gedacht: der volle Betrag hätte dort bis zu
 * fünfzehn Stellen und würde jede Spaltenbreite sprengen.</p>
 */
export function formatIsk(value: Numeric): string {
  if (isMissing(value)) return '0 ISK';
  return formatCompact(value) + ' ISK';
}

/** Der exakte Betrag mit Tausenderpunkten - für Summen, auf die es ankommt. */
export function formatIskFull(value: Numeric): string {
  if (isMissing(value)) return '0 ISK';
  return value.toLocaleString(LOCALE, { maximumFractionDigits: 0 }) + ' ISK';
}

export function formatNumber(value: Numeric): string {
  if (isMissing(value)) return '0';
  return value.toLocaleString(LOCALE);
}

/** Volumen in m³, exakt. */
export function formatVolume(value: Numeric): string {
  if (isMissing(value)) return '0 m³';
  return value.toLocaleString(LOCALE, { maximumFractionDigits: 0 }) + ' m³';
}

/** Volumen in m³, kompakt - für Ranglisten und Balken. */
export function formatVolumeCompact(value: Numeric): string {
  if (isMissing(value)) return '0 m³';
  const abs = Math.abs(value);
  if (abs >= MILLION) return (value / MILLION).toFixed(2) + ' Mio m³';
  if (abs >= THOUSAND) return (value / THOUSAND).toFixed(1) + ' k m³';
  return value.toFixed(0) + ' m³';
}

/** Reine Kurzzahl ohne Einheit, etwa `1,25 B`. */
export function formatCompact(value: Numeric): string {
  if (isMissing(value)) return '0';
  const abs = Math.abs(value);
  if (abs >= TRILLION) return (value / TRILLION).toFixed(2) + ' T';
  if (abs >= BILLION) return (value / BILLION).toFixed(2) + ' B';
  if (abs >= MILLION) return (value / MILLION).toFixed(2) + ' M';
  if (abs >= THOUSAND) return (value / THOUSAND).toFixed(1) + ' k';
  return value.toFixed(0);
}

/** Breite eines Balkens im Verhältnis zum größten Wert der Reihe. */
export function barWidth(value: number, max: number): string {
  if (!max || max <= 0) return '0%';
  return Math.max(MIN_BAR_PERCENT, (value / max) * 100).toFixed(1) + '%';
}

/** Der größte Wert einer Reihe - die Bezugsgröße für {@link barWidth}. */
export function maxValue(items: { value: number }[] | undefined): number {
  if (!items || items.length === 0) return 0;
  return Math.max(...items.map(item => item.value));
}

const MONTH_NAMES = [
  'Januar', 'Februar', 'März', 'April', 'Mai', 'Juni',
  'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'
];

/** Wandelt einen Monatsschlüssel `YYYY-MM` in `Monat Jahr`. */
export function formatMonthLabel(month: string): string {
  if (!month || month === 'ALL') return 'Gesamter Zeitraum';
  const [year, monthPart] = month.split('-');
  return (MONTH_NAMES[Number(monthPart) - 1] ?? month) + ' ' + year;
}
