package com.eve.buy.bot.backend.domain.buybot.dto;

import lombok.Data;

/**
 * Eine Zeile der eingefuegten Liste - erst geparst, dann bewertet.
 *
 * <p>Das Objekt wandert durch die gesamte Verarbeitung: der Parser fuellt Name und Menge,
 * die Preis-Engine ergaenzt Status, Modifikator und Preis.
 */
@Data
public class ParsedItemDto {
    private String rawName;
    private long quantity;

    // Werden aus der DB befüllt
    private Long typeId;
    private Double volumeEach;
    private Long categoryId;

    private boolean isResolved = false;

    // --- FELDER FÜR DIE BERECHNUNG ---
    /** Menschenlesbarer Status (deutsch) - bleibt für Altbestand/Logs erhalten. */
    private String status;

    /** Maschinenlesbarer Status: OK | BLOCKED | NOT_LISTED | UNKNOWN. Das Frontend übersetzt darüber. */
    private String statusCode;
    private Double unitPrice;
    private Double totalPrice;
    private Double appliedModifier; // Praktisch für das spätere Angular-Frontend
    /** Woraus der Basispreis kam: MARKET (Jita) oder REPROCESSED (Ausbeute). */
    private String priceSource;

    /**
     * Zaehlt eine weitere Menge hinzu.
     *
     * <p>Dieselbe Position kann in der eingefuegten Liste mehrfach vorkommen.
     *
     * @param amount die zusaetzliche Stueckzahl
     */
    public void addQuantity(long amount) {
        this.quantity += amount;
    }
}
