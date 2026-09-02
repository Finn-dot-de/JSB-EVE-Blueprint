package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Dass die Schalter unter {@code eve.alt-sources.*} auch wirklich ankommen.
 *
 * <p>Hier haengt mehr dran als bei einer Gewichtung: diese Eigenschaften
 * entscheiden, ob personenbezogene Bewegungsdaten <em>ueberhaupt entstehen</em>
 * und wie lange sie liegenbleiben. Ein Tippfehler in {@code application.properties}
 * oder ein umbenanntes Feld ginge lautlos daneben - Spring bindet, was es findet,
 * und laesst den Rest auf der Vorgabe stehen. Der Nutzer schaltete dann eine
 * Erfassung ab, sie liefe weiter, und niemand koennte sagen warum.</p>
 *
 * <p>Gebunden wird ueber den {@link Binder} wie bei
 * {@code AltDetectionPropertiesTest}: geprueft wird die Zuordnung von
 * Eigenschaftsname zu Feld, dafuer braucht es keinen Anwendungskontext.</p>
 */
@DisplayName("Die Schalter der vier Datenquellen binden aus der Konfiguration")
class AltSourcePropertiesTest {

    private static AltSourceProperties bind(Map<String, String> properties) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
        return new Binder(source)
                .bind("eve.alt-sources", AltSourceProperties.class)
                .orElseGet(AltSourceProperties::new);
    }

    @Test
    @DisplayName("Ohne jede Eigenschaft ist jede Erfassung an und die Frist betraegt 90 Tage")
    void vorgabenSindAnUndNeunzigTage() {
        AltSourceProperties props = new AltSourceProperties();

        // Vorgabe an, weil eine Erfassung, die standardmaessig aus ist, in der
        // Praxis nie laeuft und deren Ausfall niemand bemerkt.
        assertThat(props.isIskTransfersEnabled()).isTrue();
        assertThat(props.isContactsEnabled()).isTrue();
        assertThat(props.isMailEnabled()).isTrue();
        assertThat(props.isPresenceEnabled()).isTrue();

        // Die vom Nutzer festgelegte Frist.
        assertThat(props.getPresenceRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(props.getIskTransferRetention()).isEqualTo(Duration.ofDays(90));

        assertThat(props.getMailMaxRecipients()).isEqualTo(5);
        // Kein Betragsfilter in der Vorgabe: eine Untergrenze verwuerfe lautlos
        // Daten, die die Bewertung spaeter braucht.
        assertThat(props.getIskTransferMinAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Jede der vier Erfassungen laesst sich einzeln abschalten")
    void jedeErfassungIstEinzelnAbschaltbar() {
        Map<String, String> konfiguration = new LinkedHashMap<>();
        konfiguration.put("eve.alt-sources.isk-transfers-enabled", "false");
        konfiguration.put("eve.alt-sources.contacts-enabled", "false");
        konfiguration.put("eve.alt-sources.mail-enabled", "false");
        konfiguration.put("eve.alt-sources.presence-enabled", "false");

        AltSourceProperties props = bind(konfiguration);

        // Einzeln und nicht gemeinsam: die vier sind unterschiedlich
        // eingriffstief. Eine Kontaktliste ist eine bewusste Eintragung des
        // Spielers, eine Anwesenheitsreihe ist es nicht.
        assertThat(props.isIskTransfersEnabled()).isFalse();
        assertThat(props.isContactsEnabled()).isFalse();
        assertThat(props.isMailEnabled()).isFalse();
        assertThat(props.isPresenceEnabled()).isFalse();
    }

    @Test
    @DisplayName("Die Aufbewahrungsfristen und Grenzwerte kommen aus der Konfiguration")
    void fristenUndGrenzwerteBinden() {
        Map<String, String> konfiguration = new LinkedHashMap<>();
        konfiguration.put("eve.alt-sources.presence-retention", "30d");
        konfiguration.put("eve.alt-sources.isk-transfer-retention", "14d");
        konfiguration.put("eve.alt-sources.isk-transfer-min-amount", "1000000");
        konfiguration.put("eve.alt-sources.mail-max-recipients", "3");

        AltSourceProperties props = bind(konfiguration);

        // Ohne diese Zeilen koennte die Frist als Konstante im Dienst stehen und
        // die Konfiguration waere Zierde. Eine Aufbewahrungsfrist ist eine
        // Zusage an die Betroffenen, keine Implementierungsentscheidung.
        assertThat(props.getPresenceRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(props.getIskTransferRetention()).isEqualTo(Duration.ofDays(14));
        assertThat(props.getIskTransferMinAmount()).isEqualByComparingTo("1000000");
        assertThat(props.getMailMaxRecipients()).isEqualTo(3);
    }

    @Test
    @DisplayName("Eine fehlende Eigenschaft laesst die uebrigen unberuehrt")
    void teilkonfigurationLaesstDenRestStehen() {
        AltSourceProperties props =
                bind(Map.of("eve.alt-sources.presence-enabled", "false"));

        // Wer eine Quelle abstellt, stellt nicht versehentlich alle ab.
        assertThat(props.isPresenceEnabled()).isFalse();
        assertThat(props.isContactsEnabled()).isTrue();
        assertThat(props.isMailEnabled()).isTrue();
        assertThat(props.isIskTransfersEnabled()).isTrue();
    }
}
