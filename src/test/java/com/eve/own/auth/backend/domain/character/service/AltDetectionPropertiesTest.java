package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Dass die Eigenschaften unter {@code eve.alt-detection.*} auch wirklich
 * ankommen.
 *
 * <p>Ohne diese Datei wuerde ein Tippfehler in {@code application.properties}
 * oder ein umbenanntes Feld <b>lautlos</b> danebengehen: Spring bindet, was es
 * findet, und laesst den Rest auf der Vorgabe stehen. Der Nutzer setzte dann
 * eine Zahl, an der Liste aendert sich nichts, und niemand koennte sagen warum -
 * genau der Zustand, den die Umstellung von {@code static final} auf
 * Konfiguration beseitigen soll.</p>
 *
 * <p>Gebunden wird ueber den {@link Binder} und nicht ueber einen ganzen
 * Anwendungskontext: geprueft wird die Zuordnung von Eigenschaftsname zu Feld,
 * und dafuer braucht es keine Datenbank.</p>
 */
@DisplayName("Die Stellschrauben der Alt-Erkennung binden aus der Konfiguration")
class AltDetectionPropertiesTest {

    private static AltDetectionProperties bind(Map<String, String> properties) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        return new Binder(source)
                .bind("eve.alt-detection", AltDetectionProperties.class)
                .orElseGet(AltDetectionProperties::new);
    }

    @Test
    @DisplayName("Ohne jede Eigenschaft gelten die frueheren Konstanten unveraendert weiter")
    void vorgabenSindDieAltenKonstanten() {
        // Der Umbau darf das Verhalten nicht anfassen. Ohne diese Zeile koennte
        // ein Vorgabewert beim Verschieben stillschweigend ein anderer geworden
        // sein - und dann waere die gesamte Begruendung darueber, warum genau
        // 90 den Namensvetter vom Zwilling trennt, falsch.
        AltDetectionProperties props = new AltDetectionProperties();

        assertThat(props.getWeightName()).isEqualTo(40);
        assertThat(props.getWeightJoin()).isEqualTo(45);
        assertThat(props.getWeightMining()).isEqualTo(15);
        assertThat(props.getMinProbability()).isEqualTo(80);
        assertThat(props.getMinAvailableSignals()).isEqualTo(1);
        assertThat(props.getMinProbabilitySingleSignal()).isEqualTo(90);
        assertThat(props.getNameFamilyMatchScore()).isEqualTo(85);
        assertThat(props.getNameFamilyMinLength()).isEqualTo(4);
        assertThat(props.getNameNumberedTwinScore()).isEqualTo(95);
        assertThat(props.getNameAltSuffixes()).contains("alt", "jr", "ii", "2", "9");
        assertThat(props.getJoinFullWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.getJoinZeroWindow()).isEqualTo(Duration.ofDays(3));
        assertThat(props.isJoinClusterDilution()).isTrue();
        assertThat(props.getJoinClusterMinSize()).isEqualTo(3);
        assertThat(props.getMiningMinSharedDays()).isEqualTo(2);
        assertThat(props.getMiningRarityExponent()).isEqualTo(1.0);
        assertThat(props.getMaxPairsPerCorporation()).isEqualTo(250_000);

        // Die vier neuen Signale. Ihre Gewichte stehen hier mit, weil ihre
        // Reihenfolge eine Fachaussage ist: die Ueberweisung ist das staerkste
        // Signal, weil sie von vornherein ZWEI Charaktere benennt, und die Post
        // das schwaechste, weil man seinem eigenen Alt nicht schreibt. Wer die
        // Reihenfolge umstellt, aendert das Merkmal und nicht bloss eine Zahl.
        assertThat(props.getWeightIsk()).isEqualTo(50);
        assertThat(props.getWeightContact()).isEqualTo(25);
        assertThat(props.getWeightMail()).isEqualTo(8);
        assertThat(props.getWeightPresence()).isEqualTo(30);
        assertThat(props.getWeightIsk())
                .as("die Ueberweisung ist das staerkste Signal")
                .isGreaterThan(props.getWeightJoin());
        assertThat(props.getWeightMail())
                .as("die Post ist das schwaechste Signal")
                .isLessThan(props.getWeightMining());

        assertThat(props.getIskFullDays()).isEqualTo(4);
        assertThat(props.getIskBothDirectionsBonus()).isEqualTo(15);
        assertThat(props.isIskCounterpartyDilution()).isTrue();
        assertThat(props.getIskCounterpartyFullCount()).isEqualTo(5);
        assertThat(props.getContactOneWayScore()).isEqualTo(60);
        assertThat(props.getContactStrongStanding()).isEqualTo(5.0);
        assertThat(props.getContactStandingBonus()).isEqualTo(20);
        assertThat(props.getContactFullListSize()).isEqualTo(20);
        assertThat(props.getMailFullCount()).isEqualTo(6);
        assertThat(props.getPresenceLookback()).isEqualTo(Duration.ofDays(30));
        assertThat(props.getPresenceBucket()).isEqualTo(Duration.ofHours(3));
        assertThat(props.getPresenceRarityExponent())
                .as("auf 0 laeuft das Standort-Signal gemessen verkehrt herum")
                .isEqualTo(1.5);
        assertThat(props.getPresenceFullEvidence()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("Jede einzelne Schraube laesst sich ueber ihren Eigenschaftsnamen setzen")
    void jedeSchraubeIstErreichbar() {
        // Absichtlich ALLE auf einmal und mit Werten, die sich von der Vorgabe
        // unterscheiden: eine Schraube, deren Name nicht passt, faellt sonst
        // nicht auf, weil sie einfach auf ihrer Vorgabe stehen bleibt.
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("eve.alt-detection.weight-name", "41");
        raw.put("eve.alt-detection.weight-join", "46");
        raw.put("eve.alt-detection.weight-mining", "16");
        raw.put("eve.alt-detection.min-probability", "81");
        raw.put("eve.alt-detection.min-available-signals", "2");
        raw.put("eve.alt-detection.min-probability-single-signal", "91");
        raw.put("eve.alt-detection.name-family-match-score", "86");
        raw.put("eve.alt-detection.name-family-min-length", "5");
        raw.put("eve.alt-detection.name-numbered-twin-score", "96");
        raw.put("eve.alt-detection.name-alt-suffixes", "alt,zwei");
        raw.put("eve.alt-detection.join-full-window", "30m");
        raw.put("eve.alt-detection.join-zero-window", "7d");
        raw.put("eve.alt-detection.join-cluster-dilution", "false");
        raw.put("eve.alt-detection.join-cluster-min-size", "4");
        raw.put("eve.alt-detection.mining-min-shared-days", "3");
        raw.put("eve.alt-detection.mining-rarity-exponent", "1.5");
        raw.put("eve.alt-detection.group-unregistered", "false");
        raw.put("eve.alt-detection.group-min-members", "3");
        raw.put("eve.alt-detection.group-max-members", "9");
        raw.put("eve.alt-detection.calibration-default-limit", "25");
        raw.put("eve.alt-detection.calibration-max-limit", "150");
        raw.put("eve.alt-detection.max-pairs-per-corporation", "500");
        raw.put("eve.alt-detection.weight-isk", "51");
        raw.put("eve.alt-detection.weight-contact", "26");
        raw.put("eve.alt-detection.weight-mail", "9");
        raw.put("eve.alt-detection.weight-presence", "31");
        raw.put("eve.alt-detection.isk-full-days", "5");
        raw.put("eve.alt-detection.isk-both-directions-bonus", "16");
        raw.put("eve.alt-detection.isk-counterparty-dilution", "false");
        raw.put("eve.alt-detection.isk-counterparty-full-count", "6");
        raw.put("eve.alt-detection.contact-one-way-score", "61");
        raw.put("eve.alt-detection.contact-strong-standing", "6.5");
        raw.put("eve.alt-detection.contact-standing-bonus", "21");
        raw.put("eve.alt-detection.contact-full-list-size", "21");
        raw.put("eve.alt-detection.mail-full-count", "7");
        raw.put("eve.alt-detection.presence-lookback", "45d");
        raw.put("eve.alt-detection.presence-bucket", "2h");
        raw.put("eve.alt-detection.presence-rarity-exponent", "2.5");
        raw.put("eve.alt-detection.presence-full-evidence", "0.9");

        AltDetectionProperties props = bind(raw);

        assertThat(props.getWeightName()).isEqualTo(41);
        assertThat(props.getWeightJoin()).isEqualTo(46);
        assertThat(props.getWeightMining()).isEqualTo(16);
        assertThat(props.getMinProbability()).isEqualTo(81);
        assertThat(props.getMinAvailableSignals()).isEqualTo(2);
        assertThat(props.getMinProbabilitySingleSignal()).isEqualTo(91);
        assertThat(props.getNameFamilyMatchScore()).isEqualTo(86);
        assertThat(props.getNameFamilyMinLength()).isEqualTo(5);
        assertThat(props.getNameNumberedTwinScore()).isEqualTo(96);
        assertThat(props.getNameAltSuffixes()).containsExactly("alt", "zwei");
        assertThat(props.getJoinFullWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.getJoinZeroWindow()).isEqualTo(Duration.ofDays(7));
        assertThat(props.isJoinClusterDilution()).isFalse();
        assertThat(props.getJoinClusterMinSize()).isEqualTo(4);
        assertThat(props.getMiningMinSharedDays()).isEqualTo(3);
        assertThat(props.getMiningRarityExponent()).isEqualTo(1.5);
        assertThat(props.isGroupUnregistered()).isFalse();
        assertThat(props.getGroupMinMembers()).isEqualTo(3);
        assertThat(props.getGroupMaxMembers()).isEqualTo(9);
        assertThat(props.getCalibrationDefaultLimit()).isEqualTo(25);
        assertThat(props.getCalibrationMaxLimit()).isEqualTo(150);
        assertThat(props.getMaxPairsPerCorporation()).isEqualTo(500);
        assertThat(props.getWeightIsk()).isEqualTo(51);
        assertThat(props.getWeightContact()).isEqualTo(26);
        assertThat(props.getWeightMail()).isEqualTo(9);
        assertThat(props.getWeightPresence()).isEqualTo(31);
        assertThat(props.getIskFullDays()).isEqualTo(5);
        assertThat(props.getIskBothDirectionsBonus()).isEqualTo(16);
        assertThat(props.isIskCounterpartyDilution()).isFalse();
        assertThat(props.getIskCounterpartyFullCount()).isEqualTo(6);
        assertThat(props.getContactOneWayScore()).isEqualTo(61);
        assertThat(props.getContactStrongStanding()).isEqualTo(6.5);
        assertThat(props.getContactStandingBonus()).isEqualTo(21);
        assertThat(props.getContactFullListSize()).isEqualTo(21);
        assertThat(props.getMailFullCount()).isEqualTo(7);
        assertThat(props.getPresenceLookback()).isEqualTo(Duration.ofDays(45));
        assertThat(props.getPresenceBucket()).isEqualTo(Duration.ofHours(2));
        assertThat(props.getPresenceRarityExponent()).isEqualTo(2.5);
        assertThat(props.getPresenceFullEvidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("Eine gebundene Endungsliste wirkt bis in die Namensaehnlichkeit")
    void gebundeneEndungenWirken() {
        // Die Endungsliste ist die einzige Schraube, die nicht in eine Zahl
        // muendet, sondern in einen Textvergleich. Ohne diese Zeile koennte sie
        // gebunden aussehen und trotzdem nirgends ankommen.
        AltDetectionProperties props = bind(
                Map.of("eve.alt-detection.name-alt-suffixes", "zwei"));
        NameSimilarity names = new NameSimilarity(props);

        assertThat(names.score("Miner Guy", "Miner Guy zwei"))
                .as("die eigene Endung wird abgestreift")
                .isEqualTo(props.getNameNumberedTwinScore());
        assertThat(names.score("Miner Guy", "Miner Guy 2"))
                .as("die frueher fest eingebaute Ziffer zaehlt nun nicht mehr")
                .isLessThan(props.getNameNumberedTwinScore());
    }
}
