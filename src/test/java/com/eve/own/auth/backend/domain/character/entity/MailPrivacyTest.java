package com.eve.own.auth.backend.domain.character.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.eve.own.auth.backend.esi.EsiService;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Waechter ueber eine Zusage an die Mitglieder dieser Corporation.
 *
 * <p><b>Von Nachrichten wird ausschliesslich gezaehlt.</b> Betreff und Text
 * werden nicht gespeichert, nicht protokolliert und nicht durch den Dienst
 * gereicht. Diese Datei prueft nicht, ob sich jemand daran haelt - sie prueft,
 * ob es ueberhaupt <em>moeglich</em> waere, sich nicht daran zu halten. Ein
 * Feld, das keinen Betreff aufnehmen kann, braucht keine Disziplin.</p>
 *
 * <p>Geprueft werden beide Enden: die Entitaet, die in die Datenbank geht, und
 * der Typ, in den die ESI-Antwort eingelesen wird. Der zweite ist der
 * wichtigere - was Jackson gar nicht erst einliest, existiert im Prozess nie
 * als Wert und kann folglich auch nicht versehentlich ins Protokoll geraten.</p>
 *
 * <p>Der Test kostet nichts, braucht weder Datenbank noch Spring-Kontext und
 * schlaegt genau dann fehl, wenn jemand beim Erweitern "nur schnell den Betreff
 * mitnehmen" will. Genau dort soll er auffallen.</p>
 */
@DisplayName("Von Mails wird ausschliesslich gezaehlt")
class MailPrivacyTest {

    /**
     * Wortbestandteile, die auf Inhalt oder auf einen Schluessel zum Inhalt
     * hindeuten.
     *
     * <p>{@code mail_id} steht mit auf der Liste, obwohl sie kein Inhalt ist:
     * mit ihr liesse sich der Text jederzeit aus ESI nachladen. Eine Zusage, die
     * den Schluessel zur Tuer aufbewahrt, ist keine.</p>
     */
    private static final List<String> VERBOTENE_BESTANDTEILE = List.of(
            "subject", "betreff", "body", "text", "content", "inhalt",
            "mailid", "message", "nachricht", "snippet", "preview");

    private static String normalisiert(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private static List<String> verstoesse(List<String> namen) {
        return namen.stream()
                .filter(name -> VERBOTENE_BESTANDTEILE.stream()
                        .anyMatch(verboten -> normalisiert(name).contains(verboten)))
                .toList();
    }

    @Test
    @DisplayName("die Mail-Entitaet hat kein Feld, das einen Betreff oder Text aufnehmen koennte")
    void entitaetHatKeinInhaltsfeld() {
        List<String> feldnamen = Arrays.stream(CharacterMailCount.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .toList();

        assertThat(verstoesse(feldnamen))
                .as("""
                        CharacterMailCount darf kein Feld für Betreff, Text oder Mail-ID haben. \
                        Wer eines ergänzt, bricht eine Zusage an die Mitglieder dieser \
                        Corporation - gespeichert wird ausschließlich, wie viele Nachrichten \
                        zwischen zwei Charakter-IDs liefen.""")
                .isEmpty();
    }

    @Test
    @DisplayName("die Entitaet hat ueberhaupt keine Zeichenkette, in die ein Text passen wuerde")
    void entitaetHatKeineZeichenkette() {
        // Schaerfer als die Namensprüfung und aus gutem Grund: ein Feld namens
        // "notiz" umginge die Wortliste mühelos. Ein Zählwerk braucht keine
        // einzige Zeichenkette - also darf es auch keine geben.
        List<String> zeichenketten = Arrays.stream(CharacterMailCount.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> CharSequence.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList();

        assertThat(zeichenketten)
                .as("In CharacterMailCount gehört keine Zeichenkette. Eine Zeile sagt nur, "
                        + "wie viele Nachrichten zwischen zwei IDs liefen.")
                .isEmpty();
    }

    @Test
    @DisplayName("die ESI-Kopfzeile liest weder Betreff noch Mail-ID ueberhaupt ein")
    void esiKopfzeileLiestKeinenBetreff() {
        List<String> komponenten =
                Arrays.stream(EsiService.EsiMailHeaderResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(verstoesse(komponenten))
                .as("""
                        EsiMailHeaderResponse darf keine Komponente für subject oder mail_id \
                        haben. Was hier nicht steht, verwirft Jackson beim Einlesen - der \
                        Betreff existiert dann im ganzen Prozess nie als Wert und kann weder \
                        protokolliert noch weitergereicht werden. Ohne mail_id gibt es \
                        außerdem keinen Schlüssel, mit dem sich der Text nachladen ließe.""")
                .isEmpty();
    }

    @Test
    @DisplayName("die ESI-Kopfzeile traegt nur Absender, Empfaenger und Zeitpunkt")
    void esiKopfzeileTraegtNurDasNoetige() {
        List<String> komponenten =
                Arrays.stream(EsiService.EsiMailHeaderResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        // Namentlich geprüft und nicht bloß gezählt: eine gleich gebliebene
        // Anzahl beweist nicht, dass dieselben Felder drinstehen.
        assertThat(komponenten).containsExactlyInAnyOrder("from", "recipients", "timestamp");
    }
}
