package com.eve.own.auth.backend.domain.character.service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wie aehnlich sich zwei EVE-Charakternamen sind, als Wert von 0 bis 100.
 *
 * <p>Selbst implementiert, weil dieses Projekt keine neue Abhaengigkeit
 * aufnimmt - und weil der reine Levenshtein-Abstand fuer diesen Zweck ohnehin
 * nicht genuegt. Gemessen auf der einzigen hier bekannten Wahrheit erreichen
 * <em>echte</em> Alt-Paare per Zeichenabstand hoechstens 21 von 100, waehrend
 * fremde Paare bis 29 kommen: der nackte Abstand laeuft auf diesem Bestand
 * verkehrt herum. Das ist auch inhaltlich zu erwarten - ein Alt heisst
 * absichtlich nicht wie sein Main.</p>
 *
 * <p>Deshalb sind hier drei Vergleiche zusammengefasst, und es zaehlt der
 * <em>hoechste</em>:</p>
 * <ol>
 *   <li>der normierte Levenshtein-Abstand ueber den ganzen Namen,</li>
 *   <li>der gleiche Nachname - in EVE das uebliche Erkennungszeichen eines
 *       Spieler-Verbunds ("Comander Video" und "Sansha Video"),</li>
 *   <li>der durchnummerierte Zwilling - derselbe Name mit angehaengter Ziffer,
 *       roemischer Zahl oder "Alt".</li>
 * </ol>
 *
 * <p>Der Maximalwert und nicht der Mittelwert, weil die drei Muster einander
 * ausschliessen: wer den Nachnamen teilt, hat meist einen ganz anderen
 * Vornamen, und ein Mittelwert wuerde jedes einzelne Muster unter die Schwelle
 * druecken.</p>
 *
 * <p><b>Was das nicht kann:</b> ein Spieler, der seine Alts frei benennt - der
 * Normalfall bei Spionage- und Handels-Alts -, ist hier nicht zu finden. Kein
 * hoher Wert ist dann ein Beweis, und ein niedriger Wert ist kein Freispruch;
 * er heisst nur, dass dieses eine Muster nicht greift.</p>
 */
public final class NameSimilarity {

    private NameSimilarity() {
        throw new AssertionError("Utility-Klasse, nicht instanziierbar.");
    }

    /**
     * Die Aehnlichkeit zweier Namen von 0 (nichts gemeinsam) bis 100 (gleich).
     *
     * @return 0, wenn einer der beiden Namen fehlt - ein fehlender Name ist keine
     *     gemessene Unaehnlichkeit, aber der Aufrufer behandelt den Namen ohnehin
     *     als immer verfuegbares Signal, weil beide Namen ueber ESI vorliegen
     */
    public static int score(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 100;
        }

        int best = levenshteinScore(a, b);
        best = Math.max(best, familyNameScore(a, b));
        best = Math.max(best, numberedTwinScore(a, b));
        return best;
    }

    /**
     * Normiert einen Namen fuer den Vergleich.
     *
     * <p>Bindestriche und Apostrophe werden zu Leerzeichen: "Comander-Video"
     * traegt in EVE dieselbe Aussage wie "Comander Video", und ohne diesen
     * Schritt findet die Nachnamen-Regel den Nachnamen nicht.</p>
     */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('\'', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Der normierte Levenshtein-Abstand als Prozentwert. */
    static int levenshteinScore(String a, String b) {
        int maxLength = Math.max(a.length(), b.length());
        if (maxLength == 0) {
            return 0;
        }
        int distance = levenshtein(a, b);
        return (int) Math.round(100.0 * (maxLength - distance) / maxLength);
    }

    /**
     * Die klassische Levenshtein-Distanz in zwei Zeilen statt einer vollen Matrix.
     *
     * <p>Zwei Zeilen genuegen, weil jede Zelle nur ihre Nachbarn oben, links und
     * oben-links braucht. Bei Namen von 20 Zeichen ist der Speicher egal - der
     * Grund ist ein anderer: der Aufruf laeuft im Kreuzprodukt aus allen nicht
     * registrierten Mitgliedern und allen Konten, also einige tausend Mal je
     * Seitenaufruf.</p>
     */
    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int deletion = previous[j] + 1;
                int insertion = current[j - 1] + 1;
                current[j] = Math.min(substitution, Math.min(deletion, insertion));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * Punktwert fuer einen geteilten Nachnamen, also ein gleiches letztes
     * Namenswort.
     *
     * <p>Beide Namen muessen mindestens zwei Woerter haben - sonst waere der
     * "Nachname" der ganze Name, und die Regel wuerde den Levenshtein-Zweig nur
     * doppelt zaehlen. Zu kurze Endungen zaehlen nicht, damit "II" oder "Jr"
     * nicht als geteilter Nachname durchgehen.</p>
     */
    static int familyNameScore(String a, String b) {
        List<String> left = Arrays.asList(a.split(" "));
        List<String> right = Arrays.asList(b.split(" "));
        if (left.size() < 2 || right.size() < 2) {
            return 0;
        }
        String leftFamily = left.getLast();
        String rightFamily = right.getLast();
        if (leftFamily.length() < AltDetectionTuning.NAME_FAMILY_MIN_LENGTH
                || !leftFamily.equals(rightFamily)) {
            return 0;
        }
        return AltDetectionTuning.NAME_FAMILY_MATCH_SCORE;
    }

    /**
     * Punktwert dafuer, dass beide Namen nach dem Abstreifen einer Nummerierung
     * gleich sind.
     *
     * <p>Abgestreift wird auf <em>beiden</em> Seiten: sonst faende die Regel
     * "Foo Bar 2" gegen "Foo Bar 3" nicht, obwohl gerade das das deutlichste
     * Muster ueberhaupt ist.</p>
     */
    static int numberedTwinScore(String a, String b) {
        String strippedLeft = stripAltSuffix(a);
        String strippedRight = stripAltSuffix(b);
        if (strippedLeft.isEmpty() || !strippedLeft.equals(strippedRight)) {
            return 0;
        }
        return AltDetectionTuning.NAME_NUMBERED_TWIN_SCORE;
    }

    /** Entfernt genau eine bekannte Alt-Endung am Wortende. */
    static String stripAltSuffix(String normalized) {
        int lastSpace = normalized.lastIndexOf(' ');
        if (lastSpace < 0) {
            return normalized;
        }
        String tail = normalized.substring(lastSpace + 1);
        for (String suffix : AltDetectionTuning.NAME_ALT_SUFFIXES) {
            if (tail.equals(suffix)) {
                return normalized.substring(0, lastSpace);
            }
        }
        return normalized;
    }
}
