package com.eve.own.auth.backend.domain.character.service;

import com.eve.own.auth.backend.domain.character.CorporationScope;
import com.eve.own.auth.backend.domain.character.entity.ActivityType;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.entity.CharacterActivity;
import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import com.eve.own.auth.backend.domain.eve.entity.InvType;
import com.eve.own.auth.backend.domain.eve.repository.InvTypeRepository;
import com.eve.own.auth.backend.esi.EsiService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Leitet die Kennzahlen eines Charakters aus Mining-Ledger und Wallet-Journal ab.
 *
 * <p>Ergebnis sind die Werte aus {@link ActivityType}: abgebautes Volumen,
 * Kopfgelder, erlegte NPCs und erkannte Steuerzahlungen.</p>
 *
 * <p>Nebenbei reicht die Klasse das ohnehin geholte Journal an den
 * {@link IskTransferSyncService} weiter. Das ist keine Vermischung von
 * Zustaendigkeiten, sondern die Vermeidung eines zweiten Abrufs derselben
 * Daten: die Auswertung selbst liegt vollstaendig dort.</p>
 */
@Slf4j
@Service
public class CharacterActivitySyncService {

    /** ESI-Journaltyp der Kopfgeld-Gutschriften. */
    private static final String REF_TYPE_BOUNTY = "bounty_prizes";

    /** ESI-Journaltyp einer Spieler-Ueberweisung. */
    private static final String REF_TYPE_DONATION = "player_donation";

    /**
     * Stichworte, an denen eine Ueberweisung als Steuerzahlung erkannt wird.
     *
     * <p>Der Verwendungszweck ist ein Freitextfeld, das Spieler von Hand
     * ausfuellen - mehr als eine Heuristik ist hier nicht zu haben. Erfasst sind
     * deshalb die deutsche und die englische Schreibweise.</p>
     */
    private static final Set<String> TAX_KEYWORDS = Set.of("steuer", "mining", "tax");

    private final EsiService esiService;
    private final InvTypeRepository invTypeRepo;
    private final AssetSyncService assetSyncService;
    private final CorporationScope corporationScope;
    private final IskTransferSyncService iskTransferSyncService;

    public CharacterActivitySyncService(EsiService esiService,
                                        InvTypeRepository invTypeRepo,
                                        AssetSyncService assetSyncService,
                                        CorporationScope corporationScope,
                                        IskTransferSyncService iskTransferSyncService) {
        this.esiService = esiService;
        this.invTypeRepo = invTypeRepo;
        this.assetSyncService = assetSyncService;
        this.corporationScope = corporationScope;
        this.iskTransferSyncService = iskTransferSyncService;
    }

    public void sync(Character character, String token) {
        Instant measuredAt = Instant.now();
        List<CharacterActivity> activities = new ArrayList<>();

        activities.addAll(syncMining(character, token, measuredAt));
        activities.addAll(syncWalletJournal(character, token, measuredAt));

        if (!activities.isEmpty()) {
            assetSyncService.mergeCharacterActivities(character.getId(), activities);
        }
    }

    /** Spiegelt das Mining-Ledger und verdichtet es zum abgebauten Volumen. */
    private List<CharacterActivity> syncMining(Character character, String token, Instant measuredAt) {
        var response = esiService.getMiningLedger(character.getId(), token);
        if (response.data() == null || response.data().length == 0) {
            return List.of();
        }
        EsiService.EsiMiningResponse[] entries = response.data();

        List<CharacterMining> ledger = Arrays.stream(entries)
                .map(entry -> toMiningEntry(character.getId(), entry))
                .toList();
        assetSyncService.mergeCharacterMining(character.getId(), ledger);

        return List.of(CharacterActivity.of(
                character.getId(), ActivityType.MINING_VOLUME, totalVolume(entries), measuredAt));
    }

    private static CharacterMining toMiningEntry(Long characterId, EsiService.EsiMiningResponse entry) {
        CharacterMining mining = new CharacterMining();
        mining.setCharacterId(characterId);
        mining.setDate(entry.date());
        mining.setTypeId(entry.type_id());
        mining.setQuantity(entry.quantity());
        return mining;
    }

    /** Menge mal Stueckvolumen aus der SDE - unbekannte Typen zaehlen als volumenlos. */
    private double totalVolume(EsiService.EsiMiningResponse[] entries) {
        Set<Long> typeIds = Arrays.stream(entries)
                .map(EsiService.EsiMiningResponse::type_id)
                .collect(Collectors.toSet());

        Map<Long, Double> volumeByType = invTypeRepo.findAllById(typeIds).stream()
                .filter(type -> type.getVolume() != null)
                .collect(Collectors.toMap(InvType::getTypeId, InvType::getVolume));

        return Arrays.stream(entries)
                .mapToDouble(entry -> entry.quantity() * volumeByType.getOrDefault(entry.type_id(), 0.0))
                .sum();
    }

    /**
     * Wertet das Wallet-Journal fuer Kopfgelder und Steuerzahlungen aus - und
     * reicht dieselbe Antwort an die Erfassung der Spieler-Ueberweisungen weiter.
     *
     * <p>Die Weitergabe steht hier und nicht in einem eigenen Lauf, weil sie so
     * <b>keinen einzigen zusaetzlichen ESI-Aufruf</b> kostet: das Journal liegt
     * an dieser Stelle bereits vor. Bisher wurde die Gegenpartei jeder Zeile
     * weggeworfen; genau sie ist das staerkste Merkmal, das dieses Journal
     * hergibt - siehe {@link IskTransferSyncService}.</p>
     *
     * <p>Weitergereicht wird erst nach der Pruefung auf {@code null}: eine
     * ausgefallene Quelle darf keine Teildaten schreiben.</p>
     */
    private List<CharacterActivity> syncWalletJournal(Character character, String token, Instant measuredAt) {
        var response = esiService.getWalletJournal(character.getId(), token);
        if (response.data() == null) {
            return List.of();
        }
        iskTransferSyncService.sync(character.getId(), response.data());

        List<CharacterActivity> activities = new ArrayList<>();
        double bountyTotal = 0.0;
        long bountyEntries = 0;

        for (EsiService.EsiJournalResponse entry : response.data()) {
            if (entry.amount() == null) {
                continue;
            }
            if (REF_TYPE_BOUNTY.equals(entry.ref_type())) {
                bountyTotal += entry.amount();
                bountyEntries++;
            } else if (isTaxPayment(entry)) {
                activities.add(CharacterActivity.of(character.getId(), ActivityType.TAX_PAYMENT,
                        Math.abs(entry.amount()), Instant.parse(entry.date())));
            }
        }

        activities.add(CharacterActivity.of(
                character.getId(), ActivityType.PVE_ISK, bountyTotal, measuredAt));
        activities.add(CharacterActivity.of(
                character.getId(), ActivityType.RAT_KILLS, bountyEntries, measuredAt));
        return activities;
    }

    /**
     * Eine abgehende Ueberweisung an die Haupt-Corporation, deren Verwendungszweck
     * auf eine Abgabe hindeutet.
     */
    private boolean isTaxPayment(EsiService.EsiJournalResponse entry) {
        if (!REF_TYPE_DONATION.equals(entry.ref_type()) || entry.amount() >= 0) {
            return false;
        }
        if (!corporationScope.isMain(entry.second_party_id()) || entry.reason() == null) {
            return false;
        }
        String reason = entry.reason().toLowerCase(Locale.ROOT);
        return TAX_KEYWORDS.stream().anyMatch(reason::contains);
    }
}
