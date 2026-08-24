package com.eve.own.auth.backend.domain.mining.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import com.eve.own.auth.backend.domain.mining.entity.MiningTaxCredit;
import com.eve.own.auth.backend.domain.mining.repository.MiningTaxCreditRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Steuergutschriften - der einzige Ort dieser Anwendung, an dem ein Mensch einem
 * anderen Geld zuspricht.
 *
 * <p>Alles Uebrige am Mining-Steuerwesen rechnet: die Schuld faellt aus Menge,
 * Preis und Satz, die Zahlung faellt aus dem Wallet-Journal. Beide lassen sich
 * nachrechnen, wenn jemand zweifelt. Eine Gutschrift laesst sich nicht
 * nachrechnen - sie ist eine Entscheidung. Was hier nicht geprueft wird, ist
 * ungeprueft, und der Schaden waere in ISK zu beziffern.</p>
 *
 * <p>Fuenf Regeln tragen diesen Dienst, und dieser Test haelt fuer jede fest,
 * was ohne sie geschaehe:</p>
 * <ol>
 *   <li>Nur die Fuehrung kommt heran - geprueft im Dienst, nicht nur am Endpunkt.</li>
 *   <li>Jede Buchung erzeugt einen Nachweis mit Handelndem, Betrag und Zeitpunkt.</li>
 *   <li>Eine Ruecknahme ist eine Gegenbuchung; die urspruengliche Zeile bleibt stehen.</li>
 *   <li>Die Selbstvergabe bleibt erlaubt, wird aber gekennzeichnet.</li>
 *   <li>Der Betrag bleibt auch in Milliardenhoehe auf die Einheit genau.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Steuergutschriften")
class MiningTaxCreditServiceTest {

    private static final Long DIREKTORIN = 100L;
    private static final Long MITGLIED_OHNE_AMT = 200L;

    /** Der Beguenstigte - ein Main mit einem Alt. */
    private static final Long MEMBER = 1000L;
    private static final Long MEMBER_ALT = 1001L;

    @Mock private MiningTaxCreditRepository creditRepo;
    @Mock private CharacterRepository characterRepo;
    @Mock private MiningAdminGuard guard;

    private MiningTaxCreditService service;

    /** Vergibt beim Speichern eine ID, wie es die Datenbank taete. */
    private long nextId;

    @BeforeEach
    void setUp() {
        service = new MiningTaxCreditService(creditRepo, characterRepo, guard);
        nextId = 1L;

        when(guard.requireLeadership(DIREKTORIN)).thenReturn(character(DIREKTORIN, DIREKTORIN));
        when(guard.requireLeadership(MITGLIED_OHNE_AMT))
                .thenThrow(new AccessDeniedException("kein Amt"));

        when(characterRepo.findById(MEMBER)).thenReturn(Optional.of(character(MEMBER, MEMBER)));
        when(characterRepo.findById(MEMBER_ALT))
                .thenReturn(Optional.of(character(MEMBER_ALT, MEMBER)));
        when(characterRepo.findById(DIREKTORIN))
                .thenReturn(Optional.of(character(DIREKTORIN, DIREKTORIN)));
        when(characterRepo.findAllById(any()))
                .thenReturn(List.of(character(MEMBER, MEMBER), character(DIREKTORIN, DIREKTORIN)));

        when(creditRepo.save(any())).thenAnswer(invocation -> {
            MiningTaxCredit credit = invocation.getArgument(0);
            if (credit.getId() == null) {
                credit.setId(nextId++);
            }
            return credit;
        });
        when(creditRepo.existsByReversalOfCreditId(anyLong())).thenReturn(false);
        when(creditRepo.findAll()).thenReturn(List.of());
        when(creditRepo.findByAccountIdOrderByOccurredAtDesc(anyLong())).thenReturn(List.of());
        when(creditRepo.findTop200ByOrderByOccurredAtDesc()).thenReturn(List.of());
    }

    private static Character character(Long id, Long mainId) {
        Character character = new Character();
        character.setId(id);
        character.setName("Pilot " + id);
        character.setMainCharacterId(mainId);
        return character;
    }

    /** Die zuletzt gespeicherte Buchung - der Nachweis, wie er in der Tabelle landet. */
    private MiningTaxCredit lastSaved() {
        ArgumentCaptor<MiningTaxCredit> captor = ArgumentCaptor.forClass(MiningTaxCredit.class);
        verify(creditRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ==================================================================

    @Nested
    @DisplayName("Wer darf")
    class Access {

        @Test
        @DisplayName("laesst ein gewoehnliches Mitglied nicht vergeben")
        void plainMemberCannotGrant() {
            // OHNE DIESE REGEL koennte sich jedes angemeldete Mitglied selbst
            // einen beliebigen Betrag zusprechen. Die Annotation am Endpunkt
            // allein genuegt dafuer nicht: sie gehoert zu einem Einstiegspunkt,
            // faellt bei einem Umbau lautlos weg und schuetzt einen zweiten
            // Aufrufer - etwa einen kuenftigen Scheduler - ueberhaupt nicht.
            assertThatThrownBy(() ->
                    service.grant(MITGLIED_OHNE_AMT, MEMBER, "1000", "her damit"))
                    .isInstanceOf(AccessDeniedException.class);

            verify(creditRepo, never()).save(any());
        }

        @Test
        @DisplayName("laesst ein gewoehnliches Mitglied nicht zuruecknehmen")
        void plainMemberCannotReverse() {
            // OHNE DIESE REGEL koennte ein Mitglied die Gutschrift eines anderen
            // kassieren - eine Aenderung an fremdem Geld ohne jede Befugnis.
            assertThatThrownBy(() -> service.reverse(MITGLIED_OHNE_AMT, 1L, null))
                    .isInstanceOf(AccessDeniedException.class);

            verify(creditRepo, never()).save(any());
            verify(creditRepo, never()).findById(anyLong());
        }

        @Test
        @DisplayName("laesst ein gewoehnliches Mitglied den Verlauf nicht lesen")
        void plainMemberCannotRead() {
            // OHNE DIESE REGEL saehe jedes Mitglied, wer wem wieviel zugesteckt
            // hat. Das ist dieselbe Auskunft wie die Vergabe selbst, nur lesend -
            // und sie gehoert demselben Kreis.
            assertThatThrownBy(() -> service.historyFor(MITGLIED_OHNE_AMT, MEMBER))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> service.recentHistory(MITGLIED_OHNE_AMT))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("Vergeben")
    class Granting {

        @Test
        @DisplayName("schreibt einen Nachweis mit Handelndem, Betrag und Zeitpunkt")
        void writesAudit() {
            // OHNE DIESEN NACHWEIS waere die Frage "wer hat das genehmigt?" ab
            // der ersten Buchung unbeantwortbar. Am Saldo eines Accounts steht
            // nur eine Zahl; woher sie kommt, sagt sie nicht.
            Instant vorher = Instant.now();

            MiningDtos.TaxCreditDto dto = service.grant(DIREKTORIN, MEMBER, "1500000", "Fleet-Ausschuettung");

            MiningTaxCredit gespeichert = lastSaved();
            assertThat(gespeichert.getActorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(gespeichert.getAccountId()).isEqualTo(MEMBER);
            assertThat(gespeichert.getAmount()).isEqualByComparingTo("1500000.00");
            assertThat(gespeichert.getReason()).isEqualTo("Fleet-Ausschuettung");
            assertThat(gespeichert.getStatus()).isEqualTo(MiningTaxCredit.STATUS_ACTIVE);
            assertThat(gespeichert.getOccurredAt()).isBetween(vorher, Instant.now());

            // Und dasselbe geht an den Aufrufer zurueck, mit Namen statt IDs.
            assertThat(dto.actorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(dto.actorName()).isEqualTo("Pilot 100");
            assertThat(dto.amount()).isEqualByComparingTo("1500000.00");
            assertThat(dto.occurredAt()).isNotNull();
        }

        @Test
        @DisplayName("laesst den Grund weg, wenn keiner angegeben wurde")
        void reasonStaysOptional() {
            // Ein erzwungener Grund wird zu "x" - dann steht ein Platzhalter im
            // Protokoll, und das ist schlechter als ein ehrlich leeres Feld.
            service.grant(DIREKTORIN, MEMBER, "100", "   ");

            assertThat(lastSaved().getReason()).isNull();
        }

        @Test
        @DisplayName("kennzeichnet die Selbstvergabe")
        void marksSelfGrant() {
            // Verboten ist sie nicht - das Leadership schuerft selbst und hat
            // denselben Anspruch. OHNE DAS KENNZEICHEN muesste aber jeder, der
            // spaeter sucht, erst wissen, dass es diesen Fall gibt, und zwei
            // Spalten von Hand vergleichen. Als Kennzeichen faellt er in jeder
            // Liste von selbst auf.
            service.grant(DIREKTORIN, DIREKTORIN, "500", null);

            assertThat(lastSaved().isSelfGranted()).isTrue();
        }

        @Test
        @DisplayName("erkennt die Selbstvergabe auch ueber einen Alt")
        void marksSelfGrantAcrossAlts() {
            // Der Vergleich laeuft gegen den Account und nicht gegen die
            // Charakter-ID: wer seinem eigenen Alt-Verbund etwas gutschreibt,
            // bedient sich genauso selbst - und wuerde bei einem reinen
            // ID-Vergleich unauffaellig durchrutschen.
            when(guard.requireLeadership(DIREKTORIN))
                    .thenReturn(character(DIREKTORIN, MEMBER));

            service.grant(DIREKTORIN, MEMBER_ALT, "500", null);

            assertThat(lastSaved().isSelfGranted()).isTrue();
        }

        @Test
        @DisplayName("bucht auf den Account, wenn die ID eines Alts angegeben wird")
        void bookingsLandOnTheAccount() {
            // Die Steuer wird ueber den Verbund gefuehrt. Eine Gutschrift an
            // einen einzelnen Alt liesse sich gegen nichts verrechnen.
            service.grant(DIREKTORIN, MEMBER_ALT, "700", null);

            assertThat(lastSaved().getAccountId()).isEqualTo(MEMBER);
        }

        @Test
        @DisplayName("weist einen unbekannten Empfaenger ab")
        void rejectsUnknownAccount() {
            when(characterRepo.findById(4711L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.grant(DIREKTORIN, 4711L, "100", null))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(creditRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Der Betrag")
    class Amount {

        @Test
        @DisplayName("bleibt in Milliardenhoehe auf die Einheit genau")
        void billionsStayExact() {
            // DIESER TEST IST WICHTIGER, ALS ER AUSSIEHT.
            //
            // Ein double kann 12.345.678.901,23 gar nicht darstellen. Er haelt
            // 12345678901.229999542236328125 - der naechstgelegene Wert, den das
            // Binaerformat kennt. Dass die Anzeige trotzdem "12345678901.23"
            // schreibt, ist Kosmetik von Double.toString: sie druckt die kuerzeste
            // Ziffernfolge, die wieder auf dieselbe Binaerzahl zeigt. Der Fehler
            // ist da, er ist nur unsichtbar - und er waechst mit dem Betrag und
            // mit jeder Rechnung, die darauf aufsetzt. Im Bestand steht bereits
            // eine Summe 1319981075.6900005, die so nie gezahlt wurde.
            //
            // Bei einer GERECHNETEN Zahl ist das haesslich. Bei einer ZUGESAGTEN
            // waere es falsch: wer 12.345.678.901,23 ISK verspricht, muss genau
            // das wiederfinden - nicht etwas, das sich so ausdrucken laesst.
            String betrag = "12345678901.23";

            MiningDtos.TaxCreditDto dto = service.grant(DIREKTORIN, MEMBER, betrag, null);

            assertThat(dto.amount().toPlainString()).isEqualTo(betrag);
            assertThat(lastSaved().getAmount().toPlainString()).isEqualTo(betrag);

            // Der Gegenbeweis: new BigDecimal(double) zeigt, was der double
            // wirklich enthaelt, statt was er von sich behauptet. Schluege diese
            // Zeile fehl, waere BigDecimal hier ueberfluessig.
            assertThat(new BigDecimal(Double.parseDouble(betrag)).toPlainString())
                    .isNotEqualTo(betrag);
        }

        @Test
        @DisplayName("summiert Milliardenbetraege ohne Abweichung")
        void billionsSumExactly() {
            // Drei Ausschuettungen. Die Summe ist die Zahl, die als Guthaben in
            // der Bilanz steht und gegen die Steuerschuld laeuft - sie muss
            // stimmen, nicht ungefaehr stimmen.
            when(creditRepo.findByAccountIdOrderByOccurredAtDesc(MEMBER)).thenReturn(List.of(
                    credit(1L, MEMBER, "10000000000.01", MiningTaxCredit.STATUS_ACTIVE, null),
                    credit(2L, MEMBER, "20000000000.02", MiningTaxCredit.STATUS_ACTIVE, null),
                    credit(3L, MEMBER, "30000000000.03", MiningTaxCredit.STATUS_ACTIVE, null)));

            assertThat(summeDerVerrechenbaren(MEMBER).toPlainString()).isEqualTo("60000000000.06");

            // Dieselbe Addition in double landet auf 60000000000.05999755859375.
            // Rund zweieinhalb Tausendstel ISK - fuer sich genommen egal, und
            // genau deshalb faellt es nie auf. Der Punkt ist nicht dieser eine
            // Rest, sondern dass ab hier keine Zahl mehr exakt ist: jede weitere
            // Buchung, jede Verrechnung gegen die Schuld traegt ihn weiter.
            double ueberDouble = 10000000000.01 + 20000000000.02 + 30000000000.03;
            assertThat(new BigDecimal(ueberDouble).toPlainString())
                    .isNotEqualTo("60000000000.06");
        }

        @Test
        @DisplayName("liest die deutsche Schreibweise mit Tausenderpunkt und Komma")
        void readsGermanNotation() {
            // "12.500.000,50" ist das, was ein deutschsprachiger Nutzer eintippt.
            // Ungefiltert waere daraus 12,5 geworden - eine Gutschrift ueber
            // zwoelfeinhalb ISK statt zwoelfeinhalb Millionen.
            service.grant(DIREKTORIN, MEMBER, "12.500.000,50", null);

            assertThat(lastSaved().getAmount().toPlainString()).isEqualTo("12500000.50");
        }

        @Test
        @DisplayName("liest das Maschinenformat mit Punkt als Dezimalzeichen")
        void readsCanonicalNotation() {
            // Der erste Anlauf hat Punkte pauschal entfernt. Damit wurde aus
            // diesem voellig regulaeren Betrag das Tausendfache, und nur die
            // Obergrenze hat es noch aufgehalten - ein Betrag knapp darunter
            // waere durchgelaufen.
            service.grant(DIREKTORIN, MEMBER, "9876543210.99", null);

            assertThat(lastSaved().getAmount().toPlainString()).isEqualTo("9876543210.99");
        }

        @Test
        @DisplayName("liest Tausenderpunkte auch ohne Komma")
        void readsGroupedThousandsWithoutComma() {
            // Mehr als ein Punkt kann nur Gliederung sein - eine Dezimalzahl hat
            // hoechstens einen.
            service.grant(DIREKTORIN, MEMBER, "12.500.000", null);

            assertThat(lastSaved().getAmount().toPlainString()).isEqualTo("12500000.00");
        }

        @Test
        @DisplayName("weist eine mehrdeutige Schreibweise zurueck, statt zu raten")
        void rejectsAmbiguousNotation() {
            // "12.500" heisst je nach Herkunft 12,50 oder 12500 - beide Lesarten
            // sind vertretbar, und zwischen ihnen liegt der Faktor 1000. Bei Geld
            // wird in so einem Fall nicht die wahrscheinlichere gewaehlt, sondern
            // zurueckgefragt. Ein stilles Raten waere hier die teuerste Zeile
            // dieses Dienstes.
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "12.500", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mehrdeutig");

            verify(creditRepo, never()).save(any());
        }

        @Test
        @DisplayName("bringt gleiche Betraege auf dieselbe Schreibweise")
        void normalizesScale() {
            // "5" und "5,00" sollen zeichengleich in der Datenbank landen, sonst
            // sehen zwei gleiche Betraege in einer Liste verschieden aus.
            service.grant(DIREKTORIN, MEMBER, "5", null);

            assertThat(lastSaved().getAmount().toPlainString()).isEqualTo("5.00");
        }

        @Test
        @DisplayName("rundet eine dritte Nachkommastelle nicht weg, sondern lehnt ab")
        void rejectsTooManyDecimals() {
            // Aus 10,9999 stillschweigend 11,00 zu machen hiesse, einen Betrag zu
            // buchen, den niemand genannt hat. Bei Geld wird nicht geraten.
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "10.9999", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zwei Nachkommastellen");
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "10,9999", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zwei Nachkommastellen");
        }

        @Test
        @DisplayName("weist null, negative und unlesbare Betraege ab")
        void rejectsUnusableAmounts() {
            // Ein Minus darf nur als Gegenbuchung einer echten Gutschrift
            // entstehen. Direkt eingetippt haette es keine Gegenseite und saehe
            // im Verlauf nach nichts aus.
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "-100", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "0", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "viel", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "  ", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, null, null))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(creditRepo, never()).save(any());
        }

        @Test
        @DisplayName("weist einen Betrag ueber der Grenze ab")
        void rejectsAbsurdAmount() {
            // Nicht gegen Betrug - wer die Rolle hat, bucht sonst eben zweimal.
            // Sondern gegen die Null zuviel: in einer Liste aus grossen Zahlen
            // faellt sie nicht auf.
            assertThatThrownBy(() -> service.grant(DIREKTORIN, MEMBER, "9999999999999", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Grenze");
        }
    }

    @Nested
    @DisplayName("Zuruecknehmen")
    class Reversing {

        private MiningTaxCredit original;

        @BeforeEach
        void bestehendeGutschrift() {
            original = credit(7L, MEMBER, "5000000.00", MiningTaxCredit.STATUS_ACTIVE, null);
            original.setActorCharacterId(DIREKTORIN);
            original.setReason("Fleet-Ausschuettung");
            when(creditRepo.findById(7L)).thenReturn(Optional.of(original));
        }

        @Test
        @DisplayName("laesst die urspruengliche Buchung sichtbar stehen")
        void originalStaysVisible() {
            // OHNE DIESE REGEL - also mit einem DELETE - waere die Frage "was war
            // da eigentlich?" unbeantwortbar. Der Betrag, der Handelnde von
            // damals, sein Grund und der Zeitpunkt sind genau das, was jemand
            // spaeter sucht; ein Loeschen nimmt alles vier mit. Die Zeile bleibt
            // deshalb Zeichen fuer Zeichen erhalten, nur ihr Zustand wechselt.
            service.reverse(DIREKTORIN, 7L, "doppelt gebucht");

            assertThat(original.getId()).isEqualTo(7L);
            assertThat(original.getAmount()).isEqualByComparingTo("5000000.00");
            assertThat(original.getActorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(original.getReason()).isEqualTo("Fleet-Ausschuettung");
            assertThat(original.getStatus()).isEqualTo(MiningTaxCredit.STATUS_REVERSED);

            verify(creditRepo, never()).delete(any());
            verify(creditRepo, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("schreibt eine Gegenbuchung mit eigenem Nachweis")
        void writesCounterBooking() {
            Instant vorher = Instant.now();

            MiningDtos.TaxCreditDto gegenbuchung = service.reverse(DIREKTORIN, 7L, "doppelt gebucht");

            assertThat(gegenbuchung.amount()).isEqualByComparingTo("-5000000.00");
            assertThat(gegenbuchung.status()).isEqualTo(MiningTaxCredit.STATUS_REVERSAL);
            assertThat(gegenbuchung.reversalOfCreditId()).isEqualTo(7L);
            assertThat(gegenbuchung.actorCharacterId()).isEqualTo(DIREKTORIN);
            assertThat(gegenbuchung.reason()).isEqualTo("doppelt gebucht");
            assertThat(gegenbuchung.occurredAt()).isBetween(vorher, Instant.now());
            assertThat(gegenbuchung.accountId()).isEqualTo(MEMBER);
        }

        @Test
        @DisplayName("hebt sich mit der urspruenglichen Buchung rechnerisch auf")
        void pairNetsToZero() {
            // Der Kern der Konstruktion: die Summe ueber ALLE Zeilen und die
            // Summe ueber die Zeilen mit ACTIVE ergeben dieselbe Zahl. Bekaeme
            // die Gegenbuchung stattdessen ACTIVE, waere ein Filter auf ACTIVE
            // still falsch - die Belastung bliebe drin, die Gutschrift fiele
            // raus, und der Saldo des Mitglieds ruschte um den vollen Betrag ins
            // Minus.
            MiningTaxCredit reversal =
                    credit(8L, MEMBER, "-5000000.00", MiningTaxCredit.STATUS_REVERSAL, 7L);
            original.setStatus(MiningTaxCredit.STATUS_REVERSED);
            when(creditRepo.findByAccountIdOrderByOccurredAtDesc(MEMBER))
                    .thenReturn(List.of(original, reversal));
            when(creditRepo.findAll()).thenReturn(List.of(original, reversal));

            assertThat(summeDerVerrechenbaren(MEMBER)).isEqualByComparingTo("0.00");
            assertThat(summe(service.applicableByAccount().get(MEMBER)))
                    .isEqualByComparingTo("0.00");

            // Und der Punkt, der seit der Umstellung dazugehoert: die beiden
            // Zeilen werden nicht etwa gegeneinander verrechnet, sie sind gar
            // nicht erst dabei. Der Wasserfall verteilt die Buchungen EINZELN -
            // eine Belastung von -5 Mio wuerde ueber min() als Deckung
            // durchgereicht, und ein Monat forderte mehr ein, als er kostet.
            assertThat(service.applicableFor(MEMBER)).isEmpty();
        }

        @Test
        @DisplayName("kennzeichnet auch die Ruecknahme in eigener Sache")
        void marksSelfReversal() {
            // Auch das Kassieren kann ein Alleingang sein - etwa wenn jemand die
            // Gutschrift eines Kollegen an sich selbst zurueckdreht.
            when(guard.requireLeadership(DIREKTORIN)).thenReturn(character(DIREKTORIN, MEMBER));

            // Am Rueckgabewert und nicht am zuletzt Gespeicherten: die Ruecknahme
            // speichert zweimal - erst die Gegenbuchung, dann die umgestellte
            // urspruengliche Zeile - und die letzte davon ist nicht die gesuchte.
            assertThat(service.reverse(DIREKTORIN, 7L, null).selfGranted()).isTrue();
        }

        @Test
        @DisplayName("nimmt dieselbe Buchung kein zweites Mal zurueck")
        void refusesDoubleReversal() {
            // OHNE DIESE REGEL entstuenden zwei Gegenbuchungen, und dem Mitglied
            // waere der Betrag doppelt abgezogen. Die eindeutige Bedingung auf
            // reversal_of_credit_id faengt den Fall in der Datenbank ab; hier
            // faengt ihn eine lesbare Meldung ab.
            original.setStatus(MiningTaxCredit.STATUS_REVERSED);

            assertThatThrownBy(() -> service.reverse(DIREKTORIN, 7L, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bereits zurueckgenommen");

            verify(creditRepo, never()).save(any());
        }

        @Test
        @DisplayName("erkennt eine bereits vorhandene Gegenbuchung auch am Zustand vorbei")
        void refusesWhenCounterBookingExists() {
            when(creditRepo.existsByReversalOfCreditId(7L)).thenReturn(true);

            assertThatThrownBy(() -> service.reverse(DIREKTORIN, 7L, null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("nimmt eine Gegenbuchung nicht ihrerseits zurueck")
        void refusesReversingAReversal() {
            // Das waere die Ruecknahme der Ruecknahme - also wieder eine
            // Gutschrift, nur ohne dass jemand einen Betrag genannt haette. Wer
            // das Geld doch geben will, legt eine neue Gutschrift an; dann steht
            // der Betrag wieder ausdruecklich da.
            MiningTaxCredit reversal =
                    credit(8L, MEMBER, "-5000000.00", MiningTaxCredit.STATUS_REVERSAL, 7L);
            when(creditRepo.findById(8L)).thenReturn(Optional.of(reversal));

            assertThatThrownBy(() -> service.reverse(DIREKTORIN, 8L, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Gegenbuchung");
        }

        @Test
        @DisplayName("weist eine unbekannte Buchung ab")
        void refusesUnknownCredit() {
            when(creditRepo.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reverse(DIREKTORIN, 999L, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("kommt ohne den Charakterdatensatz des Empfaengers aus")
        void survivesMissingAccountCharacter() {
            // Der Beguenstigte kann die Corp verlassen haben und aus der Tabelle
            // verschwunden sein. Die Ruecknahme darf daran nicht scheitern - die
            // Buchung existiert weiter und muss sich korrigieren lassen.
            when(characterRepo.findById(MEMBER)).thenReturn(Optional.empty());

            MiningDtos.TaxCreditDto gegenbuchung = service.reverse(DIREKTORIN, 7L, null);

            assertThat(gegenbuchung.accountId()).isEqualTo(MEMBER);
        }
    }

    @Nested
    @DisplayName("Nachlesen")
    class History {

        @Test
        @DisplayName("liefert den Verlauf eines Accounts mit Namen statt IDs")
        void historyCarriesNames() {
            // Der Verlauf wird gelesen, um zu verstehen, WER gehandelt hat - eine
            // Liste aus Zahlen beantwortet das nicht.
            MiningTaxCredit gutschrift =
                    credit(1L, MEMBER, "1000.00", MiningTaxCredit.STATUS_ACTIVE, null);
            gutschrift.setActorCharacterId(DIREKTORIN);
            when(creditRepo.findByAccountIdOrderByOccurredAtDesc(MEMBER))
                    .thenReturn(List.of(gutschrift));

            List<MiningDtos.TaxCreditDto> verlauf = service.historyFor(DIREKTORIN, MEMBER);

            assertThat(verlauf).hasSize(1);
            assertThat(verlauf.getFirst().accountName()).isEqualTo("Pilot 1000");
            assertThat(verlauf.getFirst().actorName()).isEqualTo("Pilot 100");
            assertThat(verlauf.getFirst().portraitUrl()).contains("/characters/1000/portrait");
        }

        @Test
        @DisplayName("nennt die ID, wenn der Charakter verschwunden ist")
        void fallsBackToIdForVanishedCharacter() {
            MiningTaxCredit gutschrift =
                    credit(1L, 4711L, "1000.00", MiningTaxCredit.STATUS_ACTIVE, null);
            gutschrift.setActorCharacterId(4712L);
            when(creditRepo.findTop200ByOrderByOccurredAtDesc()).thenReturn(List.of(gutschrift));
            when(characterRepo.findAllById(any())).thenReturn(List.of());

            MiningDtos.TaxCreditDto zeile = service.recentHistory(DIREKTORIN).getFirst();

            assertThat(zeile.accountName()).isEqualTo("Charakter 4711");
            assertThat(zeile.actorName()).isEqualTo("Charakter 4712");
        }

        @Test
        @DisplayName("liefert eine leere Liste ohne Buchungen")
        void emptyHistory() {
            assertThat(service.historyFor(DIREKTORIN, MEMBER)).isEmpty();
            assertThat(service.recentHistory(DIREKTORIN)).isEmpty();
            assertThat(summeDerVerrechenbaren(MEMBER)).isEqualByComparingTo("0.00");
            assertThat(service.applicableByAccount()).isEmpty();
        }

        @Test
        @DisplayName("summiert je Account getrennt")
        void sumsPerAccount() {
            when(creditRepo.findAll()).thenReturn(new ArrayList<>(List.of(
                    credit(1L, MEMBER, "100.00", MiningTaxCredit.STATUS_ACTIVE, null),
                    credit(2L, MEMBER, "50.00", MiningTaxCredit.STATUS_ACTIVE, null),
                    credit(3L, 2000L, "70.00", MiningTaxCredit.STATUS_ACTIVE, null))));

            Map<Long, List<MiningDtos.TaxCreditDto>> verrechenbare = service.applicableByAccount();

            assertThat(summe(verrechenbare.get(MEMBER))).isEqualByComparingTo("150.00");
            assertThat(summe(verrechenbare.get(2000L))).isEqualByComparingTo("70.00");
        }

        @Test
        @DisplayName("stolpert nicht ueber eine Zeile ohne Betrag")
        void toleratesRowWithoutAmount() {
            // Die Spalte ist NOT NULL; das gilt Zeilen, die an der Anwendung
            // vorbei entstanden sind. Eine NullPointerException mitten in der
            // Bilanz waere die schlechtere Antwort - dann sieht niemand mehr
            // irgendeine Zahl.
            MiningTaxCredit kaputt = credit(1L, MEMBER, "10.00", MiningTaxCredit.STATUS_ACTIVE, null);
            kaputt.setAmount(null);
            when(creditRepo.findAll()).thenReturn(List.of(kaputt));
            when(creditRepo.findByAccountIdOrderByOccurredAtDesc(MEMBER)).thenReturn(List.of(kaputt));

            assertThat(summe(service.applicableByAccount().get(MEMBER))).isEqualByComparingTo("0");
            assertThat(summeDerVerrechenbaren(MEMBER)).isEqualByComparingTo("0");
            assertThat(service.historyForChecked(MEMBER).getFirst().amount())
                    .isEqualByComparingTo("0");
        }
    }

    /**
     * Die Summe der verrechenbaren Buchungen eines Accounts.
     *
     * <p>Der Dienst gibt die Buchungen einzeln heraus und nicht ihre Summe - seit
     * eine Gutschrift einen Monat als bezahlt ausweisen kann, muss die
     * Monatszeile sagen koennen, WELCHE Buchung ihn gedeckt hat. Die Summe faellt
     * dort aus derselben Liste, also faellt sie hier genauso.</p>
     */
    private BigDecimal summeDerVerrechenbaren(Long accountId) {
        return summe(service.applicableFor(accountId));
    }

    private static BigDecimal summe(List<MiningDtos.TaxCreditDto> credits) {
        return credits.stream()
                .map(MiningDtos.TaxCreditDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static MiningTaxCredit credit(Long id, Long accountId, String amount, String status,
                                          Long reversalOf) {
        MiningTaxCredit credit = new MiningTaxCredit();
        credit.setId(id);
        credit.setAccountId(accountId);
        credit.setAmount(new BigDecimal(amount));
        credit.setStatus(status);
        credit.setReversalOfCreditId(reversalOf);
        credit.setActorCharacterId(DIREKTORIN);
        credit.setOccurredAt(Instant.now());
        return credit;
    }
}
