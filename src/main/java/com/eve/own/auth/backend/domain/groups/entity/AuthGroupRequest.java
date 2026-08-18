package com.eve.own.auth.backend.domain.groups.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Die Beitrittsanfrage eines Charakters an eine Gruppe.
 *
 * <p>Die Anfrage bleibt auch nach der Entscheidung stehen. Sie ist die einzige
 * Stelle, an der spaeter noch abzulesen ist, woher eine Rolle am Charakter
 * kommt und wer sie vergeben hat - im Rollensatz selbst steht nur der Name.</p>
 */
@Entity
@Table(name = "auth_group_requests")
@Getter
@Setter
public class AuthGroupRequest {

    /**
     * Der Status als Zeichenkette und nicht als {@code enum}: die uebrigen
     * Zustaende dieser Anwendung ({@code industry_orders.status}) liegen ebenso
     * in der Datenbank, und eine Zeichenkette laesst sich ohne Wanderung des
     * Schemas um einen Wert erweitern. Der Preis dafuer sind diese Konstanten -
     * ein Tippfehler in {@code "PENDIG"} faellt sonst still auf die Fuesse, wie
     * schon bei den Rollennamen in {@code SystemRoles} beschrieben.
     */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /**
     * Der antragstellende Charakter - nicht sein Konto.
     *
     * <p>Die Rolle haengt am einzelnen Charakter, nicht am Verbund aus Haupt-
     * und Alt-Charakteren. Wer mit zwei Charakteren in die Gruppe will, stellt
     * zwei Anfragen.</p>
     */
    @Column(name = "character_id", nullable = false)
    private Long characterId;

    /** PENDING, APPROVED oder REJECTED - siehe die Konstanten oben. */
    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    /** Wann die Anfrage einging - die Verwaltung zeigt dieses Datum. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** Wann entschieden wurde; {@code null} solange die Anfrage offen ist. */
    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * Wer entschieden hat. Ohne dieses Feld liesse sich spaeter nicht mehr
     * sagen, wer eine Rolle vergeben hat - die Rolle steht dann am Charakter,
     * ihre Herkunft nirgends.
     */
    @Column(name = "decided_by_character_id")
    private Long decidedByCharacterId;
}
