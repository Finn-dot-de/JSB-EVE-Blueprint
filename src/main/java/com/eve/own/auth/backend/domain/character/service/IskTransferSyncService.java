package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.CharacterIskTransfer;
import com.eve.own.auth.backend.domain.character.entity.IskTransferDirection;
import com.eve.own.auth.backend.esi.EsiService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Haelt aus dem Wallet-Journal fest, wer wem Geld ueberwiesen hat.
 *
 * <h2>Warum das der staerkste Kandidat ist</h2>
 * <p>Eine Ueberweisung zwischen zwei <em>bestimmten</em> Charakteren ist selten
 * und gerichtet. Damit unterscheidet sie sich grundsaetzlich von einem
 * gemeinsamen Mining-Tag: der ist ein <b>Gruppenereignis</b> - in einer
 * Corporation minen alle an denselben Tagen, weshalb der rohe Wert gemessen
 * sogar invertiert war und erst eine Seltenheitsgewichtung ihn brauchbar machte.
 * Eine Zahlung von A an B braucht diese Korrektur nicht: sie benennt zwei
 * Charaktere, nicht einen Tag.</p>
 *
 * <h2>Was sie nicht kostet</h2>
 * <p><b>Null zusaetzliche ESI-Aufrufe.</b> Das Journal wird vom
 * {@link CharacterActivitySyncService} ohnehin fuer Kopfgelder und
 * Steuerzahlungen geholt; bisher wurde die Gegenpartei aus jeder Zeile
 * weggeworfen. Dieser Dienst bekommt dieselbe Antwort weitergereicht und liest
 * sie ein zweites Mal - deshalb nimmt er auch keine Charakter- und keine
 * Token-Angabe entgegen, sondern fertige Journalzeilen. Wer ihm eigene Aufrufe
 * gaebe, koennte den Vorteil nur verlieren.</p>
 *
 * <h2>Nur die registrierte Seite</h2>
 * <p>Ein Journal gibt es nur mit Token, ein Token nur vom Angemeldeten. Von
 * einem unregistrierten Y steht hier nie sein eigenes Journal - gesucht ist
 * ohnehin "Main X ueberweist regelmaessig an Y", und die Zeile dafuer entsteht
 * bei X.</p>
 */
@Slf4j
@Service
public class IskTransferSyncService {

    /**
     * Der einzige Journaltyp, der eine Ueberweisung zwischen Spielern beschreibt.
     *
     * <p>Er ist dieselbe Zeichenkette, die der {@link CharacterActivitySyncService}
     * schon fuer die Steuererkennung benutzt. Alles andere im Journal ist keine
     * Beziehung: {@code bounty_prizes} kommt von NPCs, {@code market_transaction}
     * und {@code brokers_fee} von der Boerse, {@code corporation_account_withdrawal}
     * und die Steuerarten von der Corporation. Ein Filter auf diesen einen Typ
     * schliesst sie alle aus, ohne sie einzeln aufzaehlen zu muessen - eine
     * Ausschlussliste waere unvollstaendig, sobald CCP einen Typ ergaenzt.</p>
     */
    static final String REF_TYPE_DONATION = "player_donation";

    /**
     * Ab hier vergibt CCP Charakter-IDs (seit 2010).
     *
     * <p>Alles darunter sind NPC-Corporations, Agenten, Fraktionen und
     * Systemkonten - Gegenparteien ohne Spieler dahinter.</p>
     */
    private static final long CHARACTER_ID_MIN = 90_000_000L;

    /** Beginn des Bandes, in dem CCP Corporations vergibt. */
    private static final long CORPORATION_ID_MIN = 98_000_000L;

    /** Ende des Bandes, in dem CCP Allianzen vergibt. */
    private static final long ALLIANCE_ID_MAX = 99_999_999L;

    /** ISK hat ingame zwei Nachkommastellen - dieselbe Festlegung wie bei CharacterActivity. */
    private static final int ISK_SCALE = 2;

    private final AltSourceProperties properties;
    private final AltSourceStore store;
    private final CorporationScope corporationScope;

    public IskTransferSyncService(AltSourceProperties properties,
                                  AltSourceStore store,
                                  CorporationScope corporationScope) {
        this.properties = properties;
        this.store = store;
        this.corporationScope = corporationScope;
    }

    /**
     * Wertet bereits geholte Journalzeilen aus.
     *
     * @param entries die Antwort von ESI. <b>{@code null} heisst "die Quelle war
     *     nicht da"</b> und fuehrt dazu, dass gar nichts geschrieben wird - nicht
     *     dazu, dass ein leerer Stand als vollstaendig gilt. Ein leeres Feld
     *     dagegen heisst "es gab keine Ueberweisungen"; weil angehaengt und nicht
     *     ersetzt wird, richtet auch das keinen Schaden an.
     */
    public void sync(Long characterId, EsiService.EsiJournalResponse[] entries) {
        if (!properties.isIskTransfersEnabled()) {
            return;
        }
        if (entries == null) {
            log.debug("Kein Wallet-Journal fuer Charakter {} - es wird nichts geschrieben.", characterId);
            return;
        }

        List<CharacterIskTransfer> transfers = new ArrayList<>();
        for (EsiService.EsiJournalResponse entry : entries) {
            if (!isPlayerTransfer(characterId, entry)) {
                continue;
            }
            CharacterIskTransfer transfer = toTransfer(characterId, entry);
            // Eine einzelne unlesbare Zeitangabe darf nicht den ganzen Lauf
            // beenden - sonst verlaere ein Charakter wegen einer kaputten Zeile
            // alle uebrigen Ueberweisungen desselben Journals.
            if (transfer != null) {
                transfers.add(transfer);
            }
        }
        if (transfers.isEmpty()) {
            return;
        }
        store.appendIskTransfers(characterId, transfers);
    }

    /**
     * Ob die Zeile eine echte Ueberweisung zwischen zwei Spielercharakteren ist.
     *
     * <p>Die Steuerzahlung faellt hier ohne eigene Regel heraus: sie geht an die
     * Corporation, und eine Corporation-ID liegt im Band 98.000.000 bis
     * 98.999.999. Dieselbe Pruefung wirft Allianzen und NPC-Gegenparteien mit
     * heraus.</p>
     */
    private boolean isPlayerTransfer(Long characterId, EsiService.EsiJournalResponse entry) {
        if (entry == null || !REF_TYPE_DONATION.equals(entry.ref_type())) {
            return false;
        }
        // Ohne Journal-ID kein Wiedererkennen - und ohne Wiedererkennen zaehlte
        // jeder Lauf dieselbe Ueberweisung erneut, bis die Haeufigkeit, also das
        // eigentliche Signal, nur noch die Anzahl der Laeufe abbildet.
        if (entry.id() == null || entry.date() == null) {
            return false;
        }
        if (entry.amount() == null || entry.amount() == 0.0) {
            return false;
        }
        Long counterparty = entry.second_party_id();
        if (counterparty == null || counterparty.equals(characterId)) {
            return false;
        }
        if (!isPlayerCharacter(counterparty) || corporationScope.isAllowed(counterparty)) {
            return false;
        }
        return atLeastMinimum(entry.amount());
    }

    /**
     * Ob die ID zu einem Spielercharakter gehoert.
     *
     * <p>CCPs Nummernkreise, soweit sie belastbar sind: unter 90.000.000 liegen
     * NPC-Corporations, Agenten und Fraktionen; 98.000.000 bis 98.999.999 sind
     * Corporations, 99.000.000 bis 99.999.999 Allianzen. Der Bereich dazwischen
     * und alles ab 100.000.000 sind Charaktere.</p>
     *
     * <p><b>Was diese Pruefung nicht kann:</b> IDs zwischen 100.000.000 und
     * 2.100.000.000 wurden vor 2010 vergeben und mischen Charaktere,
     * Corporations und Allianzen. Eine sehr alte Spieler-Corporation kann also
     * durchrutschen. Das steht hier, weil es die Bewertung wissen muss - sie
     * sieht die Gegenpartei-ID und kann sie im Zweifel selbst aufloesen.
     * Verschwiegen waere daraus ein stiller Fehler geworden.</p>
     */
    private static boolean isPlayerCharacter(long id) {
        if (id < CHARACTER_ID_MIN) {
            return false;
        }
        return id < CORPORATION_ID_MIN || id > ALLIANCE_ID_MAX;
    }

    private boolean atLeastMinimum(double amount) {
        BigDecimal minimum = properties.getIskTransferMinAmount();
        if (minimum == null || minimum.signum() <= 0) {
            return true;
        }
        return BigDecimal.valueOf(Math.abs(amount)).compareTo(minimum) >= 0;
    }

    /**
     * Baut die Zeile.
     *
     * <p>Der Betrag wird auf den Absolutwert gebracht, das Vorzeichen wandert in
     * die Richtung. Sonst muesste jeder spaetere Leser das Vorzeichen selbst
     * deuten - und der erste, der {@code abs()} vergisst, summiert Ein- und
     * Ausgaenge zu ungefaehr null.</p>
     *
     * @return {@code null}, wenn die Zeitangabe nicht lesbar war
     */
    private static CharacterIskTransfer toTransfer(Long characterId,
                                                   EsiService.EsiJournalResponse entry) {
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(entry.date());
        } catch (RuntimeException e) {
            log.warn("Journalzeile {} von Charakter {} hat eine unlesbare Zeitangabe: {}",
                    entry.id(), characterId, entry.date());
            return null;
        }

        CharacterIskTransfer transfer = new CharacterIskTransfer();
        transfer.setCharacterId(characterId);
        transfer.setCounterpartyId(entry.second_party_id());
        transfer.setDirection(entry.amount() < 0
                ? IskTransferDirection.OUTGOING : IskTransferDirection.INCOMING);
        transfer.setAmount(BigDecimal.valueOf(Math.abs(entry.amount()))
                .setScale(ISK_SCALE, RoundingMode.HALF_UP));
        transfer.setOccurredAt(occurredAt);
        transfer.setJournalRefId(entry.id());
        return transfer;
    }
}
