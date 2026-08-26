/**
 * Markdown fuer die Academy - als Token-Modell, nicht als HTML-Zeichenkette.
 *
 * <p>Der Kern dieser Datei ist eine Bauweise, kein Filter: {@link parseMarkdown}
 * liefert Bloecke und Spans, und die Anzeigekomponente gibt jeden Text ueber
 * {{ }} aus. Damit ist jeder Text per Konstruktion escapet und rohes HTML hat
 * ueberhaupt keinen Weg in den DOM - es gibt keine HTML-Zeichenkette, die
 * jemand versehentlich weiterreichen koennte. Die erlaubten Elemente sind das,
 * was die Komponente kann, und nicht das, was ein Filter uebrig laesst.</p>
 *
 * <p>Warum das hier haerter gebaut ist als anderswo: Das Projekt hat keine CSP,
 * <code>csrf</code> ist abgeschaltet und der Interceptor setzt
 * <code>withCredentials: true</code>. Ein XSS in einem Lehrplan koennte im Namen
 * des lesenden Betrachters jeden Endpunkt aufrufen, den dessen Rollen hergeben -
 * beim Director sind das Rollenvergabe und Steuerdaten. Und ein Lehrplan ist
 * genau der Ort, an dem ein Director in Ruhe hineinliest.</p>
 *
 * <p>Deshalb gibt es hier weder eine HTML-Zeichenkette noch eine Bindung auf
 * rohes Markup noch irgendeinen Vertrauensvorschuss an der Bereinigung von
 * Angular - die Bezeichner dafuer kommen in dieser Datei und in der Komponente
 * daneben bewusst nicht einmal als Wort vor, damit ein einfaches grep die Regel
 * durchsetzen kann. Sieht in der Vorschau etwas komisch aus, lautet die Antwort
 * "dieses Tag ist nicht vorgesehen" - niemals "Filter aus".</p>
 *
 * <p>Bewusst reduzierter Sprachumfang (Stufe 1): eine Listenebene, keine
 * Tabellen, kein rohes HTML, keine Referenzlinks, keine Fussnoten. Ein
 * Markdown-Parser wird genau an diesen drei Stellen unterschaetzt; wer sie
 * streicht, haelt den Parser klein genug, um ihn vollstaendig zu pruefen.</p>
 *
 * <p>Die Datei haengt bewusst an keinem Angular-Baustein. Sie ist eine reine
 * Funktion und damit ohne TestBed pruefbar - die Sicherheitstests behaupten
 * etwas ueber das MODELL, nicht ueber den gerenderten DOM. Das Modell ist die
 * Grenze; ein Test gegen den DOM pruefte die falsche Sache.</p>
 */

// ================= Modell =================

export type MdSpan =
  | { kind: 'text'; text: string }
  | { kind: 'strong' | 'em' | 'code'; text: string }
  | { kind: 'link'; href: string; text: string };

export type MdBlock =
  | { kind: 'heading'; level: 1 | 2 | 3; spans: MdSpan[] }
  | { kind: 'paragraph'; spans: MdSpan[] }
  | { kind: 'list'; ordered: boolean; items: MdSpan[][] }
  | { kind: 'quote'; spans: MdSpan[] }
  | { kind: 'code'; text: string }
  | { kind: 'image'; src: string; alt: string }
  | { kind: 'blocked'; reason: string }
  | { kind: 'rule' };

// ================= Erlaubte Adressen =================

/**
 * Genau zwei Hosts. Die Liste ist absichtlich kurz: Bei jedem Aufklappen holt
 * jeder Betrachterbrowser das Bild direkt beim fremden Host, und der sieht dabei
 * IP und User-Agent. Wer die Bild-URL setzt, haette sonst einen
 * Anwesenheitsmelder ueber die Corp-Leitung. Imgur und das Discord-CDN stehen
 * bewusst NICHT drauf - Discord-Adressen sterben zusaetzlich planmaessig durch
 * ihre Ablaufparameter.
 */
export const ERLAUBTE_BILD_HOSTS: readonly string[] = ['images.evetech.net', 'wiki.eveuniversity.org'];

/**
 * Fuer Links reicht das Schema; ein Link laedt nichts nach, er wird geklickt.
 * <code>data:</code> und <code>blob:</code> fehlen hier bewusst: Angulars
 * SAFE_URL_PATTERN blockt AUSSCHLIESSLICH <code>javascript:</code>, alles
 * uebrige passiert dort ungehindert. Angular ist der zweite Vorhang, nicht der
 * erste.
 */
const ERLAUBTE_LINK_SCHEMATA: readonly string[] = ['https:', 'http:', 'mailto:'];

/**
 * Ein Schema nach RFC 3986: Buchstabe, dann Buchstaben/Ziffern/+/-/. bis zum
 * Doppelpunkt. Streng buchstabiert, damit <code>%6aavascript:</code> gar nicht
 * erst als Schema durchgeht und in der Allowlist landen koennte.
 */
const SCHEMA_MUSTER = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;

/**
 * Steuerzeichen, unsichtbare Zeichen und Leerraum fliegen aus jeder Adresse,
 * BEVOR geprueft wird - und zwar an allen Stellen, nicht nur am Rand. Der
 * Browser tut beim Aufloesen einer Adresse dasselbe: <code>java&#9;script:</code>
 * ist fuer ihn <code>javascript:</code>. Wer erst prueft und dann dem Browser
 * die ungereinigte Fassung gibt, prueft eine andere Zeichenkette als die, die
 * spaeter ausgefuehrt wird.
 */
function normalisiereAdresse(rohe: string): string {
  return rohe.replace(/[\u0000-\u0020\u007F\u00A0\u200B-\u200D\u2060\uFEFF]/g, '');
}

/** <code>new URL</code> wirft bei allem, was keine absolute Adresse ist. */
function zerlegeAdresse(adresse: string): URL | null {
  try {
    return new URL(adresse);
  } catch {
    return null;
  }
}

/**
 * Erlaubt <code>https:</code>, <code>http:</code>, <code>mailto:</code> und
 * projektinterne Pfade mit fuehrendem "/". Alles andere ergibt
 * <code>null</code>; der Aufrufer macht daraus einen sichtbaren Text-Span mit
 * der Adresse im Klartext. Ein spurlos verschwundener Link ist ein
 * Fehlerbericht, ein sichtbarer Klartext ist eine Information.
 *
 * <p><code>//fremder-host</code> und <code>/\fremder-host</code> sind KEIN
 * interner Pfad: der Browser liest beide als schema-relative Adresse und landet
 * bei einer fremden Herkunft. Ein Test darauf faellt sonst genau durch die
 * Luecke, die "faengt mit / an" offen laesst.</p>
 */
export function safeHref(roheAdresse: string): string | null {
  const adresse = normalisiereAdresse(roheAdresse);
  if (adresse === '') return null;

  if (adresse.startsWith('/')) {
    if (adresse.length > 1 && (adresse[1] === '/' || adresse[1] === '\\')) return null;
    return adresse;
  }

  const schema = SCHEMA_MUSTER.exec(adresse);
  // Ohne erkennbares Schema bleibt nur "relativer Pfad ohne /", und den lassen
  // wir nicht durch: der Wert waere gegen die aktuelle Route aufgeloest und
  // damit von der Adresszeile abhaengig, nicht vom Lehrplan.
  if (schema === null) return null;
  return ERLAUBTE_LINK_SCHEMATA.includes(schema[0].toLowerCase()) ? adresse : null;
}

/**
 * Bilder brauchen mehr als ein Schema: sie werden ohne Zutun des Betrachters
 * geladen. Also <code>https:</code> UND der Host EXAKT auf der Allowlist.
 *
 * <p>Exakt heisst wirklich exakt. <code>endsWith('images.evetech.net')</code>
 * liesse <code>evil-images.evetech.net</code> durch,
 * <code>includes('images.evetech.net')</code> zusaetzlich
 * <code>images.evetech.net.boeser-host.example</code> und sogar
 * <code>boeser.example/x?a=images.evetech.net</code>. Deshalb wird der Host aus
 * <code>URL</code> genommen und mit <code>===</code> verglichen - und nicht per
 * Regex aus der Zeichenkette geschnitten. <code>URL</code> raeumt dabei auch die
 * Benutzerangabe weg: bei
 * <code>https://images.evetech.net&#64;boeser.example/x</code> ist der Host
 * <code>boeser.example</code>, und genau das wollen wir sehen.</p>
 */
export function safeImageSrc(roheAdresse: string): string | null {
  const zerlegt = zerlegeAdresse(normalisiereAdresse(roheAdresse));
  if (zerlegt === null) return null;
  if (zerlegt.protocol !== 'https:') return null;

  const host = zerlegt.hostname.toLowerCase();
  if (!ERLAUBTE_BILD_HOSTS.some((erlaubt) => erlaubt === host)) return null;

  // Die von URL normalisierte Fassung, nicht die eingetippte: geprueft und
  // gebunden werden soll dieselbe Zeichenkette.
  return zerlegt.href;
}

/**
 * Was in der Ablehnung stehen soll. Der Host ist die nuetzliche Auskunft - bei
 * <code>data:</code> und Aehnlichem gibt es keinen, dann nennen wir das Schema.
 * Eine Meldung, die nach dem Doppelpunkt nichts sagt, hilft dem Autor nicht.
 */
function quellenname(roheAdresse: string): string {
  const adresse = normalisiereAdresse(roheAdresse);
  const zerlegt = zerlegeAdresse(adresse);
  if (zerlegt === null) return kuerze(adresse === '' ? '(leer)' : adresse);
  if (zerlegt.hostname !== '') return zerlegt.hostname;
  return zerlegt.protocol;
}

/** Der Grund steht in der Oberflaeche; eine Base64-Zeile mit 2,7 Mio. Zeichen nicht. */
function kuerze(text: string): string {
  return text.length <= 120 ? text : text.slice(0, 117) + '...';
}

/**
 * Ein Bild wird entweder gezeigt oder sichtbar abgewiesen - nie still
 * verschluckt. Ein Autor, dessen Bild einfach fehlt, sucht den Fehler bei sich;
 * einer, der den Hostnamen liest, weiss sofort Bescheid.
 */
export function bildBlock(alt: string, roheAdresse: string): MdBlock {
  const quelle = safeImageSrc(roheAdresse);
  if (quelle === null) {
    return { kind: 'blocked', reason: 'Bildquelle nicht erlaubt: ' + quellenname(roheAdresse) };
  }
  return { kind: 'image', src: quelle, alt };
}

// ================= Spans =================

interface Klammerpaar {
  readonly text: string;
  readonly adresse: string;
  readonly ende: number;
}

/**
 * Liest <code>[text](adresse)</code> ab der oeffnenden eckigen Klammer.
 *
 * <p>Die runden Klammern werden GEZAEHLT, nicht bis zur ersten schliessenden
 * gelesen. Sonst zerfiele <code>javascript:alert(1)</code> in
 * <code>javascript:alert</code> plus den Rest als Text - die Adresse wuerde zwar
 * immer noch abgewiesen, aber der Klartext hinter dem Linktext waere
 * verstuemmelt und der Autor saehe nicht, was er wirklich geschrieben hat.</p>
 */
function leseKlammerpaar(quelle: string, start: number): Klammerpaar | null {
  const schluss = quelle.indexOf(']', start + 1);
  if (schluss < 0 || quelle[schluss + 1] !== '(') return null;

  let tiefe = 0;
  let i = schluss + 1;
  for (; i < quelle.length; i++) {
    if (quelle[i] === '(') tiefe++;
    else if (quelle[i] === ')') {
      tiefe--;
      if (tiefe === 0) break;
    }
  }
  if (tiefe !== 0) return null;

  return {
    text: quelle.slice(start + 1, schluss),
    adresse: quelle.slice(schluss + 2, i),
    ende: i + 1,
  };
}

/**
 * Sucht das schliessende Auszeichnungszeichen.
 *
 * <p>Innen darf weder vorne noch hinten Leerraum stehen. Ohne diese Regel wuerde
 * aus "2 * 3 und * 4" Kursivschrift - genau daran unterscheidet Markdown den
 * Rechenstern vom Auszeichnungsstern, und ein Lehrplan ueber Schadensrechnung
 * ist voll von Rechensternen.</p>
 */
function sucheAbschluss(zeile: string, marke: string, ab: number): number {
  if (ab >= zeile.length || /\s/.test(zeile[ab])) return -1;

  let von = ab;
  for (;;) {
    const treffer = zeile.indexOf(marke, von);
    if (treffer < 0) return -1;
    if (treffer > ab && !/\s/.test(zeile[treffer - 1])) return treffer;
    von = treffer + 1;
  }
}

/**
 * Zerlegt eine Zeile in Spans. Alles, was kein erkanntes Zeichen traegt, bleibt
 * Text - und Text ist in der Vorlage escapet. Deshalb braucht es hier keinen
 * Zweig fuer <code>&lt;script&gt;</code>: ein Tag ist schlicht kein Sonderfall,
 * sondern Buchstaben.
 */
function parseSpans(zeile: string): MdSpan[] {
  const spans: MdSpan[] = [];
  let text = '';

  const abgeben = (): void => {
    if (text !== '') {
      spans.push({ kind: 'text', text });
      text = '';
    }
  };

  let i = 0;
  while (i < zeile.length) {
    const zeichen = zeile[i];

    if (zeichen === '`') {
      const ende = zeile.indexOf('`', i + 1);
      if (ende > i + 1) {
        abgeben();
        spans.push({ kind: 'code', text: zeile.slice(i + 1, ende) });
        i = ende + 1;
        continue;
      }
    } else if (zeichen === '*' || zeichen === '_') {
      const doppelt = zeile[i + 1] === zeichen;
      const marke = doppelt ? zeichen + zeichen : zeichen;
      const inhaltAb = i + marke.length;
      const ende = sucheAbschluss(zeile, marke, inhaltAb);
      // Ein leeres Paar (** oder __) ist keine Auszeichnung, sondern Zeichensalat.
      if (ende > inhaltAb) {
        abgeben();
        spans.push({ kind: doppelt ? 'strong' : 'em', text: zeile.slice(inhaltAb, ende) });
        i = ende + marke.length;
        continue;
      }
    } else if (zeichen === '[') {
      const treffer = leseKlammerpaar(zeile, i);
      if (treffer !== null) {
        const href = safeHref(treffer.adresse);
        if (href !== null) {
          abgeben();
          spans.push({ kind: 'link', href, text: treffer.text });
        } else {
          // Sichtbar statt still: der Linktext, dahinter die Adresse im
          // Klartext. Gezeigt wird die BEREINIGTE Fassung - der Autor soll
          // lesen, wo sein Link wirklich hinginge, nicht was er getippt hat.
          text += treffer.text + ' (' + normalisiereAdresse(treffer.adresse) + ')';
        }
        i = treffer.ende;
        continue;
      }
    }

    text += zeichen;
    i++;
  }

  abgeben();
  return spans;
}

// ================= Bloecke =================

const UEBERSCHRIFT = /^ {0,3}(#{1,6})\s+(.*)$/;
const TRENNLINIE = /^ {0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/;
const ZITAT = /^ {0,3}>\s?(.*)$/;
const ZAUN = /^ {0,3}(`{3,}|~{3,})\s*(.*)$/;
const PUNKTLISTE = /^\s*[-*+]\s+(.*)$/;
const NUMMERNLISTE = /^\s*\d{1,9}[.)]\s+(.*)$/;

/**
 * Hebt einen fertigen Absatz in die Blockliste und schneidet dabei Bilder heraus.
 *
 * <p>Bilder sind im Modell ein Block, kein Span. Ein Bild mitten im Fliesstext
 * teilt den Absatz also in Text davor, Bild, Text danach. Der bewusst
 * hingenommene Preis: <code>`![](x)`</code> in Inline-Code wird trotzdem als
 * Bild gelesen. Gefaehrlich ist das nicht - die Allowlist gilt unveraendert -,
 * es ist nur eine Grenze des reduzierten Sprachumfangs.</p>
 */
function absatzHinzu(roh: string, ziel: MdBlock[]): void {
  let text = '';
  let i = 0;

  const textAbgeben = (): void => {
    if (text.trim() !== '') ziel.push({ kind: 'paragraph', spans: parseSpans(text) });
    text = '';
  };

  while (i < roh.length) {
    if (roh[i] === '!' && roh[i + 1] === '[') {
      const treffer = leseKlammerpaar(roh, i + 1);
      if (treffer !== null) {
        textAbgeben();
        ziel.push(bildBlock(treffer.text, treffer.adresse));
        i = treffer.ende;
        continue;
      }
    }
    text += roh[i];
    i++;
  }

  textAbgeben();
}

/**
 * Der Einstieg. Liefert immer eine Liste - nie <code>null</code>, nie eine
 * Ausnahme. Ein Lehrplan, der die Anzeige zum Absturz braechte, waere ein
 * eigener kleiner Angriff.
 */
export function parseMarkdown(quelle: string): MdBlock[] {
  if (typeof quelle !== 'string' || quelle === '') return [];

  const zeilen = quelle.replace(/\r\n?/g, '\n').split('\n');
  const bloecke: MdBlock[] = [];

  let absatz: string[] = [];
  let listenPunkte: MdSpan[][] = [];
  let listeGeordnet = false;

  const absatzSchliessen = (): void => {
    if (absatz.length > 0) {
      // Weiche Zeilenumbrueche werden zu Leerzeichen - so liest Markdown einen
      // Absatz, und so bleibt das Bilder-Herausschneiden eine Sache.
      absatzHinzu(absatz.join(' '), bloecke);
      absatz = [];
    }
  };

  const listeSchliessen = (): void => {
    if (listenPunkte.length > 0) {
      bloecke.push({ kind: 'list', ordered: listeGeordnet, items: listenPunkte });
      listenPunkte = [];
    }
  };

  const alleSchliessen = (): void => {
    absatzSchliessen();
    listeSchliessen();
  };

  let i = 0;
  while (i < zeilen.length) {
    const zeile = zeilen[i];

    const zaun = ZAUN.exec(zeile);
    if (zaun !== null) {
      alleSchliessen();
      const marke = zaun[1][0];
      const inhalt: string[] = [];
      i++;
      // Ein nicht geschlossener Zaun laeuft bis zum Ende der Quelle. Die
      // Alternative waere, ihn zu verwerfen - dann verschwaende ein vergessenes
      // Backtick-Trio den halben Lehrplan spurlos.
      while (i < zeilen.length && !new RegExp('^ {0,3}' + marke + '{3,}\\s*$').test(zeilen[i])) {
        inhalt.push(zeilen[i]);
        i++;
      }
      i++;
      bloecke.push({ kind: 'code', text: inhalt.join('\n') });
      continue;
    }

    if (zeile.trim() === '') {
      alleSchliessen();
      i++;
      continue;
    }

    if (TRENNLINIE.test(zeile)) {
      alleSchliessen();
      bloecke.push({ kind: 'rule' });
      i++;
      continue;
    }

    const ueberschrift = UEBERSCHRIFT.exec(zeile);
    if (ueberschrift !== null) {
      alleSchliessen();
      // Vier und mehr Rauten gibt es im Modell nicht. Sie auf Ebene 3 zu ziehen
      // ist freundlicher, als sie als literale Rauten stehen zu lassen - das
      // saehe nach kaputt aus, obwohl nur eine Ebene fehlt.
      const ebene = Math.min(ueberschrift[1].length, 3) as 1 | 2 | 3;
      bloecke.push({ kind: 'heading', level: ebene, spans: parseSpans(ueberschrift[2].trim()) });
      i++;
      continue;
    }

    const zitat = ZITAT.exec(zeile);
    if (zitat !== null) {
      alleSchliessen();
      const teile: string[] = [zitat[1]];
      i++;
      while (i < zeilen.length) {
        const weiter = ZITAT.exec(zeilen[i]);
        if (weiter === null) break;
        teile.push(weiter[1]);
        i++;
      }
      bloecke.push({ kind: 'quote', spans: parseSpans(teile.join(' ').trim()) });
      continue;
    }

    // Eine Ebene, und zwar mit Absicht: eingerueckte Unterpunkte fallen auf die
    // gleiche Ebene zurueck statt zu literalem Zeichensalat zu werden. Ein
    // flach gewordener Unterpunkt ist lesbar, "  - Punkt" als Absatz nicht.
    const punkt = PUNKTLISTE.exec(zeile);
    const nummer = punkt === null ? NUMMERNLISTE.exec(zeile) : null;
    if (punkt !== null || nummer !== null) {
      absatzSchliessen();
      const geordnet = nummer !== null;
      if (listenPunkte.length > 0 && listeGeordnet !== geordnet) listeSchliessen();
      listeGeordnet = geordnet;
      listenPunkte.push(parseSpans((punkt ?? nummer)![1]));
      i++;
      continue;
    }

    listeSchliessen();
    absatz.push(zeile.trim());
    i++;
  }

  alleSchliessen();
  return bloecke;
}
