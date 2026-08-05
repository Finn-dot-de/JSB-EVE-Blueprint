package com.eve.own.auth.backend.domain.mining.service;

import com.eve.own.auth.backend.common.EveImageUrls;
import com.eve.own.auth.backend.domain.character.entity.Character;
import com.eve.own.auth.backend.domain.character.repository.CharacterMiningRepository;
import com.eve.own.auth.backend.domain.character.repository.CharacterRepository;
import com.eve.own.auth.backend.domain.mining.dto.MiningDtos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Die Rangliste "wer hat am meisten abgebaut", aggregiert je Account.
 *
 * <p>Bewusst ohne Rollenpruefung: die Liste ist als Ansporn fuer alle Mitglieder
 * gedacht. Sie zeigt ausschliesslich Menge und Wert des Abbaus, keine
 * Steuerschulden oder Salden - die bleiben den Admin-Bilanzen vorbehalten.</p>
 */
@Service
public class MiningLeaderboardService {

    /** Kennung fuer "gesamter verfuegbarer Zeitraum". */
    public static final String ALL_MONTHS = "ALL";

    /** Spaltenpositionen der nativen Aggregatsabfrage. */
    private static final int COL_MAIN_ID = 0;
    private static final int COL_MAIN_NAME = 1;
    private static final int COL_VOLUME = 2;
    private static final int COL_VALUE = 3;
    private static final int COL_UNITS = 4;

    private final CharacterRepository characterRepo;
    private final CharacterMiningRepository miningRepo;

    public MiningLeaderboardService(CharacterRepository characterRepo,
                                    CharacterMiningRepository miningRepo) {
        this.characterRepo = characterRepo;
        this.miningRepo = miningRepo;
    }

    /**
     * @param month          "YYYY-MM" oder {@link #ALL_MONTHS}; ohne Angabe der
     *                       neueste Monat mit Daten
     * @param viewerCharacterId der anfragende Charakter, um seine Zeile zu markieren
     */
    @Transactional(readOnly = true)
    public MiningDtos.MiningLeaderboardDto leaderboard(String month, Long viewerCharacterId) {
        List<String> availableMonths = miningRepo.findAvailableMiningMonths();
        String selectedMonth = selectMonth(month, availableMonths);
        Long viewerAccountId = characterRepo.findById(viewerCharacterId)
                .map(Character::getAccountId)
                .orElse(null);

        List<Object[]> aggregated = miningRepo.aggregateMiningByAccount(selectedMonth);

        List<MiningDtos.MiningLeaderRowDto> rows = new ArrayList<>(aggregated.size());
        double totalVolume = 0;
        double totalValue = 0;
        int rank = 1;

        for (Object[] row : aggregated) {
            Long accountId = asLong(row[COL_MAIN_ID]);
            double volume = asDouble(row[COL_VOLUME]);
            double value = asDouble(row[COL_VALUE]);

            totalVolume += volume;
            totalValue += value;

            rows.add(new MiningDtos.MiningLeaderRowDto(
                    rank++, accountId,
                    row[COL_MAIN_NAME] != null ? String.valueOf(row[COL_MAIN_NAME]) : "Account " + accountId,
                    EveImageUrls.portrait(accountId),
                    volume, value, asLong(row[COL_UNITS]),
                    accountId.equals(viewerAccountId)));
        }

        return new MiningDtos.MiningLeaderboardDto(
                selectedMonth, availableMonths, totalVolume, totalValue, rows);
    }

    /**
     * Ohne Vorgabe wird der neueste Monat gezeigt.
     *
     * <p>Der Gesamtzeitraum waere als Einstieg wenig aussagekraeftig: ESI liefert
     * je nach Charakter unterschiedlich weit zurueckreichende Ledger, wer laenger
     * dabei ist stuende allein deshalb weiter oben.</p>
     */
    private static String selectMonth(String requested, List<String> availableMonths) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        return availableMonths.isEmpty() ? ALL_MONTHS : availableMonths.getFirst();
    }

    private static long asLong(Object value) {
        return value != null ? ((Number) value).longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value != null ? ((Number) value).doubleValue() : 0d;
    }
}
