package com.eve.own.auth.backend.domain.character.service;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alle Stellschrauben der Alt-Erkennung an EINER Stelle, konfigurierbar ueber
 * {@code eve.alt-detection.*}.
 *
 * <p>Warum eine eigene Klasse und nicht {@code private static final} im Dienst:
 * die Zahlen hier sind <em>keine</em> Implementierungsdetails. Sie sind die
 * Fachaussage des ganzen Merkmals - wer sie aendert, aendert, welche fremden
 * Charaktere einem fremden Konto vorgeschlagen werden. Verstreut im Dienst
 * findet sie niemand wieder; gebuendelt lassen sie sich lesen, vergleichen und
 * anpassen, ohne den Rechenweg zu verstehen.</p>
 *
 * <h2>Warum das jetzt Konfiguration ist und nicht mehr {@code static final}</h2>
 * <p>Vorher war das ein Konstantenhalter. Damit kostete jeder Versuch, einen
 * einzigen Wert anders zu setzen, einen vollen Neubau samt Neustart - und weil
 * die Werte ausdruecklich <b>nicht kalibriert</b> sind, ist Ausprobieren nicht
 * die Ausnahme, sondern der einzige Weg, sie ueberhaupt zu setzen. Eine
 * Stellschraube, an der man nicht drehen kann, ist keine.</p>
 *
 * <h2>Warum Klasse mit Feldern und nicht Record wie {@code MarketOrderProperties}</h2>
 * <p>Der wertvollste Teil dieser Klasse sind nicht die Zahlen, sondern die
 * Begruendungen darunter: jede sagt, was beim Hoch- und was beim Runterdrehen
 * passiert. Ein Record kann seine Komponenten nur ueber {@code @param}-Zeilen in
 * der Klassendoku beschreiben - neunzehn mehrabsaetzige Begruendungen wuerden
 * dort zu einem Block, in dem niemand mehr die einzelne Schraube findet. Felder
 * tragen ihre eigene Doku direkt am Wert. Die Vorgabewerte stehen als
 * Feldinitialisierung und sind exakt die frueheren Konstanten; fehlt eine
 * Eigenschaft in der Konfiguration, bleibt es beim heutigen Verhalten.</p>
 *
 * <h2>Was diese Zahlen NICHT sind</h2>
 * <p>Sie sind <b>nicht kalibriert</b>. Eine Kalibrierung braucht bekannte
 * Wahrheit - also Paare, von denen man weiss, dass sie zusammengehoeren. Die
 * Datenbank kennt derzeit 12 Charaktere in 2 Konten; das reicht, um ein Signal
 * zu <em>widerlegen</em>, aber nirgends, um eine Schwelle zu <em>bestaetigen</em>.
 * Die Werte hier sind begruendete Setzungen, keine Messergebnisse. Wer sie
 * setzen will, statt sie zu raten, ruft zuerst
 * {@link AltDetectionService#calibrationSample(Long, Integer)} auf - die Ansicht
 * zeigt die besten Paare samt Aufschluesselung <em>unterhalb</em> der Schwelle,
 * und erst daran laesst sich sehen, wo die Schwelle sinnvoll liegt.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "eve.alt-detection")
public class AltDetectionProperties {

    // ==================================================================
    // Gewichte der drei Signale
    // ==================================================================

    /**
     * Gewicht der Namensaehnlichkeit.
     *
     * <p><b>Hoeher:</b> Namen entscheiden staerker. Das trifft Spieler, die ihre
     * Alts erkennbar durchnummerieren ("Foo Bar", "Foo Bar 2") - und erzeugt
     * zugleich Fehltreffer bei zwei fremden Spielern mit demselben Nachnamen.
     * <b>Niedriger:</b> nur noch Beitritt und Mining tragen; bei einer Corp ohne
     * Director-Token (kein Beitrittsdatum) bleibt dann fast nichts uebrig, und
     * die Liste ist leer statt falsch.</p>
     *
     * <p>Bewusst nicht das staerkste Gewicht: auf der einzigen hier bekannten
     * Wahrheit erreichen <em>echte</em> Alt-Paare per Levenshtein hoechstens 21
     * von 100, waehrend fremde Paare bis 29 kommen. Der reine Namensabstand ist
     * auf diesem Bestand also nicht bloss schwach, sondern verkehrt herum. Das
     * Gewicht traegt hier hauptsaechlich die beiden <em>Muster</em> (gleicher
     * Nachname, abgestreifte Nummerierung), nicht den nackten Abstand.</p>
     */
    private int weightName = 40;

    /**
     * Gewicht der Beitrittsnaehe (start_date aus der Mitgliederverfolgung).
     *
     * <p><b>Hoeher:</b> gemeinsamer Beitritt entscheidet. Das ist das einzige
     * Signal, das fuer nicht registrierte Charaktere ueberhaupt Daten hat -
     * dreht man es hoch, wird die Liste laenger und faengt Rekrutierungswellen
     * mit ein. <b>Niedriger:</b> die Liste wird kuerzer; in einer Corp ohne
     * auswertbare Mining-Daten kann dann gar kein Vorschlag mehr die Schwelle
     * erreichen.</p>
     *
     * <p>Das hoechste Gewicht, weil es das einzige Signal ist, dessen Datenlage
     * fuer die Zielgruppe nicht strukturell leer ist. Gegen den bekannten
     * Schwachpunkt - Rekrutierungswellen setzen viele fremde Konten auf dieselbe
     * Minute - hilft nicht das Gewicht, sondern {@link #joinClusterDilution}.</p>
     *
     * <p>Fuer die Gruppierung <em>unregistrierter untereinander</em> ist es das
     * einzige zweite Signal ueberhaupt: die Mitgliederverfolgung deckt die ganze
     * Corporation ab, also beide Seiten eines solchen Paares.</p>
     */
    private int weightJoin = 45;

    /**
     * Gewicht der Mining-Uebereinstimmung.
     *
     * <p><b>Hoeher:</b> gemeinsame Mining-Tage entscheiden staerker. <b>Niedriger:</b>
     * das Signal wird zur blossen Bestaetigung.</p>
     *
     * <p>Das kleinste Gewicht, und zwar aus zwei gemessenen Gruenden. Erstens:
     * fuer einen nicht registrierten Charakter existiert in {@code character_mining}
     * <em>keine einzige Zeile</em> - jeder Sync-Pfad braucht das Token des
     * Charakters selbst. Das Signal ist heute also praktisch immer
     * <em>nicht verfuegbar</em> und faellt sauber weg, statt mit 0 einzufliessen.
     * Zweitens: die rohe Tagesueberschneidung wurde auf dem vorhandenen Bestand
     * gemessen und war <em>invertiert</em> - fremde Paare lagen ueber echten
     * (Jaccard-Mittel 0,77 gegen 0,27). Der Grund ist erklaerbar: in einer Corp
     * minen alle an denselben Tagen, der Tag ist ein Gruppenereignis und kein
     * Fingerabdruck. Genau dagegen rechnet {@link #miningRarityExponent}.</p>
     *
     * <p>Das Signal wird trotzdem gebaut: sobald die Corp-Mining-Beobachter
     * (Scope {@code esi-industry.read_corporation_mining.v1}, ebenfalls bereits
     * vorhanden) Zeilen fuer nicht registrierte Charaktere liefern, ist es die
     * einzige Quelle, die dem Mining-Signal Ort und Uhrzeit zurueckgibt.</p>
     */
    private int weightMining = 15;

    // ==================================================================
    // Die Schwelle
    // ==================================================================

    /**
     * Ab welcher Wahrscheinlichkeit ein Vorschlag ueberhaupt erscheint.
     *
     * <p><b>Hoeher:</b> weniger Vorschlaege, mehr uebersehene Alts. <b>Niedriger:</b>
     * mehr Vorschlaege - und jeder einzelne davon ist ein Angebot an den Director,
     * einen <em>fremden</em> Charakter einem <em>fremden</em> Konto zuzuschlagen.
     * Der Preis der beiden Richtungen ist nicht derselbe: ein uebersehener Alt
     * kostet nichts, eine falsche Zuordnung kostet Steuerakte, Bestaende und
     * Rollen.</p>
     *
     * <p><b>Eine 80 aus EINEM Signal ist etwas voellig anderes als eine 80 aus
     * dreien.</b> Der Score ist ein gewichteter Mittelwert ueber die
     * <em>tatsaechlich verfuegbaren</em> Signale - liegt nur die Namensaehnlichkeit
     * vor, dann heisst 80 wortwoertlich "der Name passt gut, ueber alles andere
     * ist nichts bekannt". Genau deshalb traegt jeder Vorschlag seine
     * Aufschluesselung mit: {@code signalsUsed} von {@code signalsTotal} und je
     * Signal der Einzelwert. Wer nur die Zahl anzeigt, verschweigt die Haelfte
     * der Aussage.</p>
     */
    private int minProbability = 80;

    /**
     * Wieviele Signale mindestens vorliegen muessen, damit ueberhaupt ein
     * Vorschlag entsteht.
     *
     * <p><b>Hoeher (2 oder 3):</b> ein einzelnes Signal reicht nicht mehr aus -
     * deutlich weniger und deutlich belastbarere Vorschlaege, in einer Corp ohne
     * Director-Token gar keine. <b>Auf 1:</b> ein perfekt passender Name allein
     * genuegt fuer einen Vorschlag; das ist der Fall, den zwei fremde Spieler mit
     * demselben Nachnamen ausloesen.</p>
     *
     * <p>Steht auf 1, weil ein einzelnes Signal durchaus tragen kann - ein
     * durchnummerierter Zwilling ist ein starker Hinweis. Gegen den gefaehrlichen
     * Einzelsignal-Fall schuetzt nicht diese Konstante, sondern
     * {@link #minProbabilitySingleSignal}.</p>
     */
    private int minAvailableSignals = 1;

    /**
     * Die hoehere Schwelle, wenn nur EIN Signal vorliegt.
     *
     * <p>Gemessen und nicht geschaetzt: Traegt nur der Name, ist der Gesamtwert
     * woertlich der Namenswert. Dann liegen zwei sehr verschiedene Faelle dicht
     * beieinander - ein gemeinsamer Nachname ergibt 85 ("Zaphod Video" gegen
     * "Comander-Video"), ein durchnummerierter Zwilling 95
     * ("Comander-Video 2"). Der erste ist in EVE voellig gewoehnlich und bei
     * mehreren hundert unregistrierten Mitgliedern der Regelfall, der zweite
     * ist ein ernstzunehmender Hinweis. 90 trennt sie.</p>
     *
     * <p><b>Hoeher:</b> auch Zwillinge fallen heraus, die Liste bleibt ohne
     * Director-Token leer. <b>Auf {@link #minProbability} herunter:</b> jeder
     * Namensvetter steht wieder in der Liste - mit einem Knopf daneben, der
     * einen fremden Menschen einem fremden Konto zuschlaegt.</p>
     *
     * <p>Dieselbe Schwelle regelt auch, welche Kante zwischen zwei
     * <em>unregistrierten</em> Charakteren eine Gruppe begruenden darf. Ohne
     * Director-Token traegt dort ebenfalls nur der Name - und dann ist ein
     * geteilter Nachname genau der Fall, den diese Zahl draussen haelt.</p>
     */
    private int minProbabilitySingleSignal = 90;

    // ==================================================================
    // Signal 1: Name
    // ==================================================================

    /**
     * Punktwert fuer einen exakt gleichen Nachnamen (letztes Namenswort).
     *
     * <p>In EVE ist der Nachname das uebliche Erkennungszeichen eines
     * Spieler-Verbunds: "Comander Video" und "Sansha Video" gehoeren mit hoher
     * Wahrscheinlichkeit demselben Spieler, obwohl ihr Levenshtein-Abstand riesig
     * ist. Genau diesen Fall verfehlt ein reiner Zeichenabstand.</p>
     *
     * <p><b>Hoeher (Richtung 100):</b> ein gleicher Nachname allein reicht fast
     * fuer den vollen Namensteil. <b>Niedriger:</b> das Muster wird zur blossen
     * Beigabe, und Alts mit anderem Vornamen fallen wieder heraus.</p>
     */
    private int nameFamilyMatchScore = 85;

    /**
     * Ab welcher Laenge ein Nachname als Nachname zaehlt.
     *
     * <p><b>Hoeher:</b> kurze Nachnamen zaehlen nicht mehr mit - weniger
     * Zufallstreffer. <b>Niedriger:</b> zweibuchstabige Endungen ("Jr", "II")
     * gelten ploetzlich als geteilter Nachname und erzeugen Unsinn.</p>
     */
    private int nameFamilyMinLength = 4;

    /**
     * Punktwert, wenn beide Namen nach dem Abstreifen einer Nummerierung
     * identisch sind ("Foo Bar" und "Foo Bar 2").
     *
     * <p><b>Hoeher:</b> die Nummerierung wird zum staerksten Namensmuster.
     * <b>Niedriger:</b> sie faellt hinter den gleichen Nachnamen zurueck.</p>
     *
     * <p>Hoeher als {@link #nameFamilyMatchScore}, weil eine durchnummerierte
     * Wiederholung des <em>ganzen</em> Namens deutlich seltener zufaellig
     * entsteht als ein geteiltes letztes Wort.</p>
     */
    private int nameNumberedTwinScore = 95;

    /**
     * Endungen, die eine Alt-Nummerierung markieren und vor dem Vergleich
     * abgestreift werden.
     *
     * <p><b>Erweitern:</b> faengt mehr Namensschemata. <b>Kuerzen:</b> weniger
     * Fehldeutungen bei Spielern, deren Name echt auf "Alt" oder "II" endet.</p>
     *
     * <p>Als Liste und nicht als Array, weil Spring eine kommagetrennte
     * Eigenschaft direkt hierhin bindet und der Aufrufer nur liest.</p>
     */
    private List<String> nameAltSuffixes = List.of(
            "alt", "alts", "jr", "junior", "ii", "iii", "iv", "v", "vi",
            "2", "3", "4", "5", "6", "7", "8", "9");

    // ==================================================================
    // Signal 2: Beitritts-Cluster
    // ==================================================================

    /**
     * Zeitfenster, innerhalb dessen ein gemeinsamer Beitritt als praktisch
     * gleichzeitig gilt und den vollen Punktwert bekommt.
     *
     * <p><b>Hoeher:</b> auch ein Beitritt eine Stunde spaeter zaehlt noch voll -
     * mehr Treffer, mehr Rekrutierungswellen. <b>Niedriger:</b> nur der wirklich
     * gemeinsame Klick zaehlt; wer seine Alts nacheinander durch die Bewerbung
     * schleust, faellt heraus.</p>
     *
     * <p>15 Minuten, weil ein Spieler seine Alts typischerweise in einer Sitzung
     * hintereinander in die Corp holt - aber nicht in derselben Sekunde, weil
     * jede Bewerbung einzeln angenommen werden muss.</p>
     */
    private Duration joinFullWindow = Duration.ofMinutes(15);

    /**
     * Zeitfenster, ab dem ein gemeinsamer Beitritt gar nichts mehr aussagt.
     * Dazwischen faellt der Punktwert linear ab.
     *
     * <p><b>Hoeher:</b> auch ein Beitritt Wochen spaeter traegt noch Restpunkte -
     * in einer wachsenden Corp bekommt damit fast jedes Paar etwas, und das
     * Signal verliert seine Trennschaerfe. <b>Niedriger:</b> harte Kante; ein
     * Alt, der am naechsten Tag nachgezogen wurde, zaehlt wie ein Fremder.</p>
     */
    private Duration joinZeroWindow = Duration.ofDays(3);

    /**
     * Ob die Beitrittsnaehe an der Grosse ihres Clusters gedaempft wird.
     *
     * <p><b>Eingeschaltet:</b> traten im selben Fenster viele Mitglieder bei, wird
     * der Punktwert durch deren Anzahl geteilt. Eine Rekrutierungswelle erzeugt
     * dann keine Vorschlaege mehr. <b>Ausgeschaltet:</b> jede Welle liefert fuer
     * jedes Paar den vollen Punktwert - genau der Fehler, an dem das
     * Mining-Tagessignal gemessen gescheitert ist: ein Gruppenereignis wird fuer
     * einen Fingerabdruck gehalten.</p>
     *
     * <p>Fuer die Gruppierung unregistrierter Charaktere untereinander ist das
     * die wichtigste einzelne Schraube: sie ist es, die drei gleichzeitig
     * aufgenommene Namensvettern wieder auseinanderfallen laesst.</p>
     */
    private boolean joinClusterDilution = true;

    /**
     * Ab welcher Clustergroesse die Daempfung ueberhaupt greift.
     *
     * <p><b>Hoeher:</b> erst grosse Wellen werden gedaempft. <b>Niedriger (2):</b>
     * schon ein dritter Beitritt im Fenster schwaecht das Paar ab - sehr streng.</p>
     *
     * <p>3, weil ein Paar (der Alt und sein Main) den Cluster zwangslaeufig auf 2
     * bringt: das ist genau der gesuchte Fall und darf sich nicht selbst
     * daempfen.</p>
     */
    private int joinClusterMinSize = 3;

    // ==================================================================
    // Signal 3: Mining
    // ==================================================================

    /**
     * Wieviele gemeinsame Mining-Tage mindestens vorliegen muessen, damit das
     * Signal ueberhaupt als verfuegbar gilt.
     *
     * <p><b>Hoeher:</b> das Signal meldet sich seltener - und "nicht verfuegbar"
     * ist hier die ehrlichere Antwort als ein Wert aus einem einzigen Tag.
     * <b>Auf 1:</b> ein einziger gemeinsamer Tag erzeugt schon einen Wert; bei 16
     * bekannten Mining-Tagen im ganzen Bestand ist das Rauschen.</p>
     *
     * <p><b>Wichtig:</b> unterschritten heisst <em>nicht verfuegbar</em> und
     * niemals "Wert 0". Ein Charakter ohne Mining-Zeilen hat keine gemessene
     * Unaehnlichkeit - er hat gar keine Messung.</p>
     */
    private int miningMinSharedDays = 2;

    /**
     * Wie stark seltene Mining-Tage gegenueber haeufigen zaehlen.
     *
     * <p>Der Kern der Korrektur. Gemessen wurde: die rohe Tagesueberschneidung
     * ist auf diesem Bestand <em>invertiert</em> (fremde Paare liegen ueber
     * echten), weil in einer Corp alle an denselben Tagen minen. Ein Tag, an dem
     * die halbe Corp abbaut, sagt ueber ein einzelnes Paar nichts; ein Tag, an
     * dem nur diese beiden abbauten, sagt viel. Der Exponent gewichtet jeden
     * gemeinsamen Tag mit {@code (1 / Anzahl der Miner an diesem Tag)^Exponent}.</p>
     *
     * <p><b>Hoeher:</b> nur noch die ganz seltenen Tage zaehlen - sehr streng,
     * wenige Treffer. <b>Auf 0:</b> die Korrektur ist ausgeschaltet und man
     * bekommt exakt die rohe Ueberschneidung zurueck, also die gemessen verkehrt
     * herum laufende Variante. <b>Nicht auf 0 stellen, ohne das zu wollen.</b></p>
     */
    private double miningRarityExponent = 1.0;

    // ==================================================================
    // Gruppierung unregistrierter Charaktere untereinander
    // ==================================================================

    /**
     * Ob unregistrierte Charaktere auch <em>untereinander</em> zu Gruppen
     * zusammengefasst werden.
     *
     * <p><b>Eingeschaltet:</b> die Erkennung meldet zusaetzlich Gruppen ohne
     * bekannten Main ("diese drei sind vermutlich ein Mensch"). Das ist eine
     * Beobachtung und keine Handlung - es gibt niemanden, dem man die Gruppe
     * zuordnen koennte, und deshalb auch keine Schaltflaeche daneben.
     * <b>Ausgeschaltet:</b> es bleibt bei den Vorschlaegen gegen bekannte Konten,
     * also bei der Liste, die in einer Corp ohne Director-Token in der Praxis
     * fast immer leer ist.</p>
     */
    private boolean groupUnregistered = true;

    /**
     * Wieviele Mitglieder eine gemeldete Gruppe mindestens haben muss.
     *
     * <p><b>Hoeher (3):</b> nur noch Dreier- und groessere Verbuende werden
     * gemeldet - deutlich kuerzere Liste, aber der haeufigste echte Fall (ein
     * Spieler mit genau einem Zweitcharakter) faellt heraus. <b>Niedriger als 2
     * ist sinnlos:</b> ein einzelner Charakter ist keine Gruppe.</p>
     */
    private int groupMinMembers = 2;

    /**
     * Obergrenze fuer die Mitglieder einer gemeldeten Gruppe.
     *
     * <p>Eine Reissleine gegen ein Namensschema, das eine ganze Corp umfasst -
     * etwa eine Rekrutierungscorp, in der jeder denselben Nachnamen traegt.
     * Ueberschreitet eine Gruppe diese Groesse, ist sie mit hoher Sicherheit
     * eine Konvention und kein Mensch; sie wird verworfen statt gemeldet.</p>
     *
     * <p><b>Hoeher:</b> auch sehr grosse Verbuende erscheinen - ein Spieler mit
     * zwoelf Minenalts ist real. <b>Niedriger:</b> strenger, aber echte grosse
     * Alt-Flotten fallen heraus.</p>
     */
    private int groupMaxMembers = 8;

    // ==================================================================
    // Kalibrieransicht
    // ==================================================================

    /**
     * Wieviele Paare die Kalibrieransicht liefert, wenn der Aufrufer nichts sagt.
     *
     * <p><b>Hoeher:</b> mehr Zeilen auf einen Blick. <b>Niedriger:</b> weniger
     * Ballast; die Ansicht ist nach Wert sortiert, die interessanten Zeilen
     * stehen ohnehin oben.</p>
     */
    private int calibrationDefaultLimit = 50;

    /**
     * Harte Obergrenze fuer die Kalibrieransicht.
     *
     * <p>Sie ist kein Feintuning, sondern eine Grenze gegen den Vollabzug: ohne
     * sie koennte ein Director mit {@code ?limit=100000} die gesamte
     * Namens-Kreuztabelle ueber mehrere hundert Menschen als eine Antwort
     * abholen. Die Ansicht soll zeigen, <em>wie</em> der Scorer rechnet, und
     * nicht alles ausliefern, <em>was</em> er ueber alle Mitglieder denkt.</p>
     *
     * <p><b>Hoeher:</b> groessere Abzuege moeglich. <b>Niedriger:</b> strenger -
     * zum Setzen einer Schwelle genuegen die obersten Zeilen ohnehin.</p>
     */
    private int calibrationMaxLimit = 200;

    // ==================================================================
    // Rechenaufwand
    // ==================================================================

    /**
     * Obergrenze fuer das Kreuzprodukt je Corporation.
     *
     * <p>Echte Zahlen aus dem Bestand: 399 nicht registrierte Mitglieder ueber
     * vier Corporations, dem gegenueber 11 registrierte Konten - also rund 4.400
     * Paare insgesamt, groesste Corp 273 x 11 = rund 3.000. Ein Levenshtein ueber
     * zwei Namen von je 20 Zeichen sind 400 Zellen; das ganze Kreuzprodukt liegt
     * damit im Bereich von zwei Millionen Rechenschritten und dauert
     * Millisekunden. Die eigentliche Laufzeit sind die ESI-Aufrufe: je
     * Corporation eine Mitgliederliste, ein Namensabruf und eine
     * Mitgliederverfolgung.</p>
     *
     * <p>Fuer die Gruppierung unregistrierter untereinander gilt dieselbe
     * Grenze, und dort ist sie enger: das Kreuzprodukt ist nicht
     * {@code unregistriert x Konten}, sondern {@code unregistriert x
     * unregistriert / 2} - bei 273 Mitgliedern also rund 37.000 Paare statt
     * 3.000. Immer noch Millisekunden, aber der Abstand zur Grenze ist ein
     * Zehntel des frueheren.</p>
     *
     * <p><b>Hoeher:</b> auch riesige Corporations werden vollstaendig gerechnet.
     * <b>Niedriger:</b> frueherer Abbruch - der Endpunkt antwortet dann mit einer
     * unvollstaendigen Liste statt minutenlang zu rechnen. Die Grenze ist eine
     * Reissleine gegen eine Corp, die um Groessenordnungen waechst, kein
     * Feintuning.</p>
     */
    private int maxPairsPerCorporation = 250_000;
}
