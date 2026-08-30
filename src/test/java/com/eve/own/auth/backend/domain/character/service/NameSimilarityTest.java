package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Namensaehnlichkeit zweier EVE-Charaktere")
class NameSimilarityTest {

    /**
     * Die Vorgabewerte, also genau die frueheren Konstanten.
     *
     * <p>Ein frisches Objekt je Test und kein geteiltes statisches Feld: die
     * Punktwerte sind seit der Umstellung Konfiguration, und ein statischer
     * Zustand, den ein Test veraendert, wirkte in den naechsten hinein.</p>
     */
    private final AltDetectionProperties props = new AltDetectionProperties();
    private final NameSimilarity names = new NameSimilarity(props);

    @Nested
    @DisplayName("Levenshtein")
    class Levenshtein {

        @Test
        @DisplayName("identische Namen erreichen den vollen Namenswert")
        void identischeNamenVoll() {
            // Ohne diese Zeile bliebe unbemerkt, dass die Normierung den
            // Maximalwert gar nicht erreicht - dann waere die Schwelle von 80
            // aus dem Namensteil allein grundsaetzlich unerreichbar, und zwar
            // still.
            assertThat(names.score("Comander Video", "Comander Video")).isEqualTo(100);
        }

        @Test
        @DisplayName("Gross- und Kleinschreibung entscheidet nicht")
        void schreibweiseEgal() {
            assertThat(names.score("COMANDER VIDEO", "comander video")).isEqualTo(100);
        }

        @Test
        @DisplayName("voellig verschiedene Namen erreichen ihn nicht")
        void verschiedeneNamenNiedrig() {
            // Ohne diese Zeile koennte die Formel jeden Wert zurueckgeben und
            // die Schwelle waere wertlos: dann bekaeme JEDER nicht registrierte
            // Charakter einen Vorschlag, und ein Director wuerde fremde
            // Charaktere fremden Konten zuschlagen.
            assertThat(names.score("Zzz Qqqq Wwww", "Comander Video")).isLessThan(30);
        }

        @Test
        @DisplayName("die Distanz zaehlt Einfuegen, Loeschen und Ersetzen")
        void distanzKlassisch() {
            assertThat(NameSimilarity.levenshtein("kitten", "sitting")).isEqualTo(3);
            assertThat(NameSimilarity.levenshtein("", "abc")).isEqualTo(3);
            assertThat(NameSimilarity.levenshtein("abc", "abc")).isZero();
        }
    }

    @Nested
    @DisplayName("EVE-Namensmuster")
    class EveMuster {

        @Test
        @DisplayName("der geteilte Nachname zaehlt, obwohl der Zeichenabstand riesig ist")
        void gleicherNachname() {
            // In EVE ist der Nachname das uebliche Erkennungszeichen eines
            // Spieler-Verbunds. Ohne diese Regel faende die Erkennung genau den
            // haeufigsten echten Fall nicht - der reine Zeichenabstand liegt
            // hier unter 30.
            assertThat(NameSimilarity.levenshteinScore("sansha video", "comander video"))
                    .isLessThan(60);
            assertThat(names.score("Sansha Video", "Comander Video"))
                    .isEqualTo(props.getNameFamilyMatchScore());
        }

        @Test
        @DisplayName("der Bindestrich trennt wie ein Leerzeichen")
        void bindestrichTrenntNamen() {
            // "Comander-Video" und "Comander Video" sind in EVE dieselbe
            // Schreibweise. Ohne die Ersetzung faende die Nachnamenregel
            // ueberhaupt keinen Nachnamen und faellt still auf 0.
            assertThat(names.score("Sansha Video", "Comander-Video"))
                    .isEqualTo(props.getNameFamilyMatchScore());
        }

        @Test
        @DisplayName("ein zu kurzer Nachname zaehlt nicht als Nachname")
        void kurzeEndungZaehltNicht() {
            // Sonst gaelte jedes "II" oder "Jr" als geteilter Nachname und
            // zwei voellig fremde Spieler bekaemen 85 Punkte geschenkt.
            assertThat(names.score("Alpha II", "Beta II"))
                    .isLessThan(props.getNameFamilyMatchScore());
        }

        @Test
        @DisplayName("der durchnummerierte Zwilling wird erkannt")
        void durchnummerierterZwilling() {
            assertThat(names.score("Miner Guy", "Miner Guy 2"))
                    .isEqualTo(props.getNameNumberedTwinScore());
            // Auf BEIDEN Seiten abgestreift - ohne das faende die Regel
            // "Miner Guy 2" gegen "Miner Guy 3" nicht, obwohl gerade das das
            // deutlichste Muster ueberhaupt ist.
            assertThat(names.score("Miner Guy 2", "Miner Guy 3"))
                    .isEqualTo(props.getNameNumberedTwinScore());
        }

        @Test
        @DisplayName("ein einzelner Name ohne Nachnamen bekommt keinen Musterbonus")
        void einzelwortOhneBonus() {
            assertThat(names.score("Video", "Comander Video")).isLessThan(60);
        }
    }

    @Nested
    @DisplayName("Randfaelle")
    class Randfaelle {

        @Test
        @DisplayName("ein fehlender Name ergibt keinen Wert")
        void fehlenderName() {
            assertThat(names.score(null, "Comander Video")).isZero();
            assertThat(names.score("Comander Video", "   ")).isZero();
        }
    }
}
