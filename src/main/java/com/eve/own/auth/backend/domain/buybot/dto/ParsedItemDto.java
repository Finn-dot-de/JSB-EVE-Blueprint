package com.eve.own.auth.backend.domain.buybot.dto;

import lombok.Data;

@Data
public class ParsedItemDto {
    private String rawName;
    private long quantity;

    // Werden aus der DB befüllt
    private Long typeId;
    private Double volumeEach;
    private Long categoryId;

    private boolean isResolved = false;

    // --- NEUE FELDER FÜR DIE BERECHNUNG ---
    private String status;
    private Double totalPrice;
    private Double appliedModifier; // Praktisch für das spätere Angular-Frontend

    public void addQuantity(long amount) {
        this.quantity += amount;
    }
}