package com.eve.own.auth.backend.domain.mining.dto;

import java.util.List;

/**
 * Die Antworttypen der Mining-Endpunkte.
 *
 * <p>Als Sammelklasse, weil die Records ausschliesslich zusammen auftreten und
 * einzelne Dateien mit je drei Zeilen hier nichts gewinnen wuerden.</p>
 */
public final class MiningDtos {

    private MiningDtos() {
        throw new AssertionError("Nur ein Namensraum fuer die Antworttypen.");
    }

    /** Ein abgebauter Typ innerhalb eines Abrechnungsmonats. */
    public record LedgerItemDto(Long typeId, String typeName, String category, long quantity,
                                double volume, double jitaPrice, double taxToPay) {}

    /**
     * Ein Abrechnungsmonat.
     *
     * @param taxPaid der Anteil, der durch bereits geleistete Zahlungen gedeckt ist
     * @param isPaid  ob der Monat als beglichen gilt
     */
    public record MonthlyLedgerDto(String month, double totalTax, double taxPaid, boolean isPaid,
                                   List<LedgerItemDto> details) {}

    /** Der Kontostand eines Accounts ueber alle Monate. */
    public record UserLedgerResponse(double totalDebt, double totalPaid, double currentBalance,
                                     List<MonthlyLedgerDto> months) {}

    /** Eine Zeile der Rangliste - ein Account, also Main samt Alts. */
    public record MiningLeaderRowDto(int rank, Long mainId, String mainName, String portraitUrl,
                                     double volume, double value, long units, boolean isMe) {}

    public record MiningLeaderboardDto(String month, List<String> availableMonths,
                                       double totalVolume, double totalValue,
                                       List<MiningLeaderRowDto> rows) {}

    /** Die Bilanz eines Accounts in der Admin-Uebersicht. */
    public record AdminLedgerSummaryDto(Long mainId, String mainName, String portraitUrl,
                                        double totalTax, double totalPaid, double currentBalance) {}
}
