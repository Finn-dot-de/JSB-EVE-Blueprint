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

/**
 * Der volle Betrag mit Tausenderpunkten, auf ganze ISK gerundet.
 *
 * <p>Für <b>geschätzte</b> Werte: die Bewertung von Besitz, die aus
 * `market_prices` stammt und dort bewusst als `double` geführt wird. Zwei
 * Nachkommastellen wären hier keine Genauigkeit, sondern die Behauptung einer
 * Genauigkeit, die der Wert nicht hat - der Preis von morgen ist ohnehin ein
 * anderer.</p>
 *
 * <p>Für Beträge, die jemand schuldet, zahlt oder gutgeschrieben bekommt, ist
 * das die falsche Wahl - dafür gibt es {@link formatIskCents}.</p>
 */
export function formatIskFull(value: Numeric): string {
  if (isMissing(value)) return '0 ISK';
  return value.toLocaleString(LOCALE, { maximumFractionDigits: 0 }) + ' ISK';
}

/**
 * Der Betrag mit beiden Nachkommastellen, etwa `1.250.000,50 ISK`.
 *
 * <p>Für jeden Betrag, der <b>genau</b> geführt wird - in der Datenbank als
 * `numeric(20,2)`, im Server als `BigDecimal`: Steuerschuld, geleistete
 * Zahlung, Gutschrift, Saldo und der Preis, mit dem gerechnet wurde. Hier hieß
 * es einmal "für Gutschriften und nur für die", weil Steuer und Zahlung damals
 * `double` waren und ihre letzte Stelle wirklich nur Rauschen. Das gilt nicht
 * mehr. Eine Steuer über 1.842.491.900,97 als "1.842.491.901" anzuzeigen wirft
 * genau die Stelle weg, für die der Server auf `BigDecimal` umgestellt wurde -
 * und wer den angezeigten Betrag überweist, trifft die Schuld nie.</p>
 *
 * <p>Der Wert kommt als JSON-<em>Zahl</em> an und ist im Browser damit ein
 * `double`. Für die Anzeige ist das unschädlich: bis 10^12 ISK liegt dessen
 * Fehler bei rund 0,0002 ISK und damit weit unter dem halben Cent, ab dem die
 * zweite Nachkommastelle kippen würde. Beim <em>Senden</em> gilt das nicht -
 * siehe `MiningService.grantCredit`.</p>
 */
export function formatIskCents(value: Numeric): string {
  if (isMissing(value)) return '0,00 ISK';
  return value.toLocaleString(LOCALE, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }) + ' ISK';
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
