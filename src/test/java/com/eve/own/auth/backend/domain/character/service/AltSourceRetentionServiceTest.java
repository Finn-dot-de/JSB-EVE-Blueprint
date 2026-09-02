package com.eve.own.auth.backend.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.repository.CharacterContactRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterIskTransferRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterMailCountRepository;
import com.eve.own.auth.backend.domain.character.repository.CorporationMemberPresenceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Eine Aufbewahrungsfrist, die nur im Javadoc steht, ist keine.
 *
 * <p>Deshalb wird hier nicht geprueft, ob der Lauf existiert, sondern was er
 * loescht - und vor allem, was er stehen laesst. Das Loeschen selbst laeuft
 * gegen eine echte Zeitgrenze, deren Wirkung an zwei Zeilen nachgestellt wird:
 * eine aeltere und eine juengere als die Frist.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Loeschlauf der Aufbewahrungsfristen")
class AltSourceRetentionServiceTest {

    @Mock private CorporationMemberPresenceRepository presenceRepo;
    @Mock private CharacterIskTransferRepository iskTransferRepo;
    @Mock private CharacterContactRepository contactRepo;
    @Mock private CharacterMailCountRepository mailCountRepo;

    private AltSourceProperties properties;
    private AltSourceRetentionService service;

    /** Der nachgestellte Bestand: eine Zeile von vor 100 Tagen, eine von vor 10. */
    private final List<Instant> anwesenheit = new ArrayList<>();
    private final List<Instant> ueberweisungen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new AltSourceProperties();
        service = new AltSourceRetentionService(properties, presenceRepo, iskTransferRepo,
                contactRepo, mailCountRepo);

        anwesenheit.clear();
        anwesenheit.add(Instant.now().minus(Duration.ofDays(100)));
        anwesenheit.add(Instant.now().minus(Duration.ofDays(10)));

        ueberweisungen.clear();
        ueberweisungen.add(Instant.now().minus(Duration.ofDays(100)));
        ueberweisungen.add(Instant.now().minus(Duration.ofDays(10)));

        // Die Mocks loeschen wirklich - nur so kann der Test sagen, WAS
        // uebrigbleibt, statt bloss, dass jemand "delete" gerufen hat.
        when(presenceRepo.deleteOlderThan(any())).thenAnswer(aufruf -> {
            Instant grenze = aufruf.getArgument(0);
            int vorher = anwesenheit.size();
            anwesenheit.removeIf(zeitpunkt -> zeitpunkt.isBefore(grenze));
            return vorher - anwesenheit.size();
        });
        when(iskTransferRepo.deleteOlderThan(any())).thenAnswer(aufruf -> {
            Instant grenze = aufruf.getArgument(0);
            int vorher = ueberweisungen.size();
            ueberweisungen.removeIf(zeitpunkt -> zeitpunkt.isBefore(grenze));
            return vorher - ueberweisungen.size();
        });
    }

    @Test
    @DisplayName("entfernt Anwesenheitszeilen aelter als die Frist und laesst juengere stehen")
    void removesOldPresenceRowsAndKeepsYoungerOnes() {
        assertThat(service.purgePresence()).isEqualTo(1);

        // Beides gehoert geprueft. Dass etwas geloescht wurde, sagt nichts
        // darueber, ob die Grenze stimmt - ein Lauf, der ALLES loescht, bestuende
        // eine reine Anzahl-Pruefung ebenso.
        assertThat(anwesenheit).hasSize(1);
        assertThat(anwesenheit.getFirst())
                .isAfter(Instant.now().minus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("entfernt ISK-Ueberweisungen aelter als die Frist und laesst juengere stehen")
    void removesOldTransfersAndKeepsYoungerOnes() {
        assertThat(service.purgeIskTransfers()).isEqualTo(1);

        assertThat(ueberweisungen).hasSize(1);
    }

    @Test
    @DisplayName("rechnet die Grenze aus der eingestellten Frist und nicht aus einer festen Zahl")
    void thresholdComesFromTheConfiguredRetention() {
        properties.setPresenceRetention(Duration.ofDays(7));

        service.purgePresence();

        ArgumentCaptor<Instant> grenze = ArgumentCaptor.captor();
        verify(presenceRepo).deleteOlderThan(grenze.capture());
        // Ohne diese Zeile koennte die 90 als Konstante im Dienst stehen und die
        // Konfiguration waere reine Zierde - der Nutzer stellte um, und nichts
        // geschaehe.
        assertThat(Duration.between(grenze.getValue(), Instant.now()).toDays()).isEqualTo(7);
        assertThat(anwesenheit).isEmpty();
    }

    @Test
    @DisplayName("loescht bei fehlender oder unsinniger Frist gar nichts")
    void deletesNothingWithoutAUsableRetention() {
        properties.setPresenceRetention(Duration.ZERO);
        properties.setIskTransferRetention(null);

        assertThat(service.purgePresence()).isZero();
        assertThat(service.purgeIskTransfers()).isZero();

        // Die naheliegende Lesart - "null Tage Aufbewahrung, also alles weg" -
        // machte aus einem Tippfehler in der Konfiguration einen vollstaendigen
        // Datenverlust, und zwar nachts, wenn niemand hinsieht.
        verify(presenceRepo, never()).deleteOlderThan(any());
        verify(iskTransferRepo, never()).deleteOlderThan(any());
        assertThat(anwesenheit).hasSize(2);
        assertThat(ueberweisungen).hasSize(2);
    }

    @Test
    @DisplayName("die Vorgabe der Aufbewahrung sind die vom Nutzer festgelegten 90 Tage")
    void defaultRetentionIsNinetyDays() {
        assertThat(new AltSourceProperties().getPresenceRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(new AltSourceProperties().getIskTransferRetention()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    @DisplayName("die eine Frist zieht die andere nicht mit")
    void bothRetentionsAreIndependent() {
        properties.setIskTransferRetention(null);

        service.purgePresence();
        service.purgeIskTransfers();

        // Ohne getrennte Fristen muesste man beide Tabellen gleich behandeln.
        // Die Anwesenheit hat ihre 90 Tage vom Nutzer, die Ueberweisungen sind
        // eine eigene Abwaegung - und wer eine davon abschaltet, darf die
        // andere nicht mit abschalten.
        verify(presenceRepo).deleteOlderThan(any());
        verify(iskTransferRepo, never()).deleteOlderThan(any());
    }

    @Test
    @DisplayName("Momentaufnahmen ausgeschiedener Charaktere verschwinden ebenfalls")
    void momentaufnahmenVerfallen() {
        // Die Zusage auf der Oberflaeche lautet, diese Daten begrenzten sich
        // selbst, weil jeder Lauf sie ersetzt. Das gilt aber nur JE CHARAKTER
        // und nur, wenn er im Lauf vorkommt: wer sein Token entzieht, wessen
        // Token ungueltig wird oder fuer wen die Quelle abgeschaltet ist, faellt
        // dauerhaft heraus. Ohne diesen Lauf blieben seine Kontaktliste und
        // seine Nachrichtenzaehler fuer immer liegen - und die Zusage waere
        // schlicht unwahr.
        when(contactRepo.deleteOlderThan(any())).thenReturn(7);
        when(mailCountRepo.deleteOlderThan(any())).thenReturn(3);

        assertThat(service.purgeSnapshots()).isEqualTo(10);

        ArgumentCaptor<Instant> grenze = ArgumentCaptor.forClass(Instant.class);
        verify(contactRepo).deleteOlderThan(grenze.capture());
        // Die Grenze liegt eine Frist in der Vergangenheit, nicht im Jetzt -
        // sonst raeumte der Lauf auch die eben erst geschriebenen Zeilen ab.
        assertThat(grenze.getValue())
                .isBefore(Instant.now().minus(Duration.ofDays(89)))
                .isAfter(Instant.now().minus(Duration.ofDays(91)));
    }

    @Test
    @DisplayName("Ohne gueltige Frist bleiben auch die Momentaufnahmen stehen")
    void ohneFristKeineMomentaufnahmen() {
        // Dieselbe Vorsicht wie bei den anderen beiden: die naheliegende Lesart
        // "null Tage, also alles weg" machte aus einem Tippfehler nachts einen
        // Totalverlust.
        properties.setSnapshotRetention(Duration.ZERO);

        assertThat(service.purgeSnapshots()).isZero();

        verify(contactRepo, never()).deleteOlderThan(any());
        verify(mailCountRepo, never()).deleteOlderThan(any());
    }
}
