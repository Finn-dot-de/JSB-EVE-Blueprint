/**
 * Der Text eines Flotten-Pings, so wie er in Discord landet.
 *
 * <h2>Warum dieser Text zweimal existiert</h2>
 * <p>Gebaut wird die echte Nachricht im Server, in `FleetPingNachricht.java`.
 * Hier steht dieselbe Regel ein zweites Mal - und das ist eine Doppelung mit
 * Ansage. Die Alternative wäre ein Vorschau-Endpunkt gewesen: der Server baut
 * den Text und liefert ihn zurück. Der hätte die Doppelung vermieden und dafür
 * etwas Schlimmeres eingehandelt - eine Vorschau, die bei jedem Tastendruck
 * eine Anfrage kostet und beim ersten Netzhänger leer bleibt. Eine leere
 * Vorschau lässt einen FC unter Zeitdruck trotzdem absenden; genau dann ist die
 * Vorschau wertlos.</p>
 *
 * <p>Der Preis der Doppelung ist bekannt und benannt: Wer den Text im Server
 * ändert, muss ihn hier mitändern, sonst zeigt die Vorschau etwas anderes an,
 * als hinausgeht. Deshalb steht diese Datei allein und nicht verstreut in der
 * Komponente - sie ist die eine Stelle, die man beim Nachziehen sucht.</p>
 */

/** Wen ein Ping benachrichtigen darf - Spiegel von `PingErwaehnung`. */
export type PingErwaehnung = 'STILL' | 'HIER' | 'ROLLE' | 'JEDER';

/**
 * Zero-Width-Space, über den Codepunkt gebildet.
 *
 * Aus demselben Grund wie im Server: ein unsichtbares Zeichen, das man in die
 * Quelldatei tippt, überlebt kein falsch geratenes Encoding - und sein
 * Verschwinden fällt niemandem auf, weil man es nicht sieht.
 */
const UNSICHTBAR = String.fromCharCode(0x200b);

/** Discords Obergrenze. Darüber lehnt Discord die ganze Nachricht ab. */
export const DISCORD_HOECHSTLAENGE = 2000;

/** Die Angaben, aus denen der Text entsteht. */
export interface PingFelder {
  fleetType: string;
  doctrine: string | null;
  formupLocation: string;
  /** ISO-8601 mit Versatz, oder `null` für "form up now". */
  formupTime: string | null;
  comms: string | null;
  srpCovered: boolean | null;
  notes: string | null;
  fcCharacterName: string;
}

/**
 * Entschärft fremden Text, damit von ihm keine Erwähnung ausgehen kann.
 *
 * Wortgleich mit `DiscordErwaehnungen.entschaerfe`. Die Vorschau muss das
 * mitmachen, sonst zeigt sie ein `@everyone`, das im Kanal keines mehr ist -
 * und ein FC glaubt, er hätte die halbe Corp geweckt, oder umgekehrt.
 */
export function entschaerfe(text: string | null | undefined): string {
  if (text === null || text === undefined) return '';
  return text
    .split('@everyone').join('@' + UNSICHTBAR + 'everyone')
    .split('@here').join('@' + UNSICHTBAR + 'here')
    .split('<@').join('<' + UNSICHTBAR + '@');
}

/** Ob im Text etwas steht, das entschärft wird - für den Hinweis im Formular. */
export function enthaeltErwaehnung(...texte: Array<string | null | undefined>): boolean {
  return texte.some((text) => {
    const t = text ?? '';
    return t.includes('@everyone') || t.includes('@here') || t.includes('<@');
  });
}

/**
 * Ein Zeitpunkt als EVE-Zeit: `yyyy-MM-dd HH:mm`.
 *
 * EVE-Zeit *ist* UTC. Deshalb wird hier nichts umgerechnet, sondern nur
 * abgelesen - `getUTC*` und nicht `getHours`, sonst stünde in der Vorschau die
 * Zeit des Browsers und im Kanal eine andere.
 */
export function eveZeit(iso: string): string {
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`
    + ` ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}`;
}

/** Discords Zeitmarke. Discord ersetzt sie beim Leser durch dessen Ortszeit. */
function marke(iso: string | null, stil: string): string {
  if (!iso) return '-';
  return `<t:${Math.floor(new Date(iso).getTime() / 1000)}:${stil}>`;
}

function wert(eingabe: string | null | undefined): string {
  const t = (eingabe ?? '').trim();
  return t === '' ? '-' : entschaerfe(t);
}

function formupZeit(formup: string | null): string {
  // "form up now" ist die häufigste Ansage überhaupt. Sie mit der aktuellen
  // Uhrzeit auszuschreiben sähe gleich aus, wäre aber eine andere Aussage.
  if (!formup) return '**JETZT**';
  return `${eveZeit(formup)} EVE (${marke(formup, 'R')})`;
}

function srpText(gedeckt: boolean | null): string {
  // Drei Antworten und nicht zwei: "nicht gesagt" darf nicht als "nein"
  // gelesen werden - daran hängt, ob jemand den teuren Rumpf mitbringt.
  if (gedeckt === null || gedeckt === undefined) return 'nicht angegeben';
  return gedeckt ? 'ja' : 'nein';
}

function kuerzen(text: string): string {
  if (text.length <= DISCORD_HOECHSTLAENGE) return text;
  return text.substring(0, DISCORD_HOECHSTLAENGE - 3) + '...';
}

/**
 * Der Nachrichtentext **ohne** die Erwähnung davor.
 *
 * <p>Die Erwähnung fehlt hier mit Absicht. Im Kanal steht bei der Auswahl
 * "Rolle" die maschinenlesbare Form `<@&123456>`, die Discord erst beim Leser
 * zum Rollennamen auflöst - und die Rollen-ID kennt nur der Server. Eine
 * Vorschau, die `<@&123456>` anzeigt, wäre wörtlich richtig und trotzdem
 * unlesbar. Die Oberfläche setzt die Erwähnung deshalb als eigenes,
 * beschriftetes Stück vor die erste Zeile.</p>
 *
 * @param geaendert setzt die Zeile, die eine nachträgliche Korrektur kenntlich
 *     macht. Der Zeitstempel darin ist der des Absendens; in der Vorschau ist
 *     das notgedrungen "jetzt" und damit auf Sekunden ungenau.
 */
export function pingNachricht(felder: PingFelder, geaendert = false): string {
  const zeilen: string[] = [];
  zeilen.push(`**FLOTTEN-PING - ${entschaerfe(felder.fleetType)}**`);
  zeilen.push(`**Doktrin:** ${wert(felder.doctrine)}`);
  zeilen.push(`**Treffpunkt:** ${wert(felder.formupLocation)}`);
  zeilen.push(`**Formup:** ${formupZeit(felder.formupTime)}`);
  zeilen.push(`**Comms:** ${wert(felder.comms)}`);
  zeilen.push(`**SRP:** ${srpText(felder.srpCovered)}`);
  if ((felder.notes ?? '').trim() !== '') {
    zeilen.push(`**Hinweis:** ${entschaerfe((felder.notes ?? '').trim())}`);
  }
  zeilen.push(`*FC: ${entschaerfe(felder.fcCharacterName)}*`);
  if (geaendert) {
    zeilen.push(`*Geaendert: ${marke(new Date().toISOString(), 'f')}*`);
  }
  return kuerzen(zeilen.join('\n'));
}
