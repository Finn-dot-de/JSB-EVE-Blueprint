package com.eve.buy.bot.backend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Ein persistierter Protokolleintrag.
 *
 * <p>Der Buybot wird überwiegend von nicht angemeldeten Spielern genutzt. Für sie gibt es
 * keinen Charakternamen, deshalb sind IP-Adresse und {@link #requestId} die einzigen
 * Anknüpfungspunkte: Meldet jemand einen Fehler, nennt er die Fehler-ID aus der
 * Oberfläche, und der Eintrag lässt sich eindeutig wiederfinden.
 *
 * <p>IP-Adressen sind personenbezogene Daten. Sie werden nach der in
 * {@code buybot.audit.retention-days} eingestellten Frist automatisch gelöscht.
 */
@Entity
@Table(name = "audit_entries", indexes = {
        @Index(name = "idx_audit_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_audit_request_id", columnList = "request_id"),
        @Index(name = "idx_audit_category", columnList = "category")
})
@Getter
@Setter
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Zeitpunkt des Ereignisses in UTC. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private AuditCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AuditSeverity severity;

    /** Kurze, für Menschen lesbare Zusammenfassung. */
    @Column(name = "message", nullable = false, length = 500)
    private String message;

    /** Kennung des Aufrufs; wird dem Nutzer im Fehlerfall angezeigt. */
    @Column(name = "request_id", length = 36)
    private String requestId;

    /** EVE-Charakter-ID des Auslösers, {@code null} bei nicht angemeldeten Spielern. */
    @Column(name = "actor_character_id")
    private Long actorCharacterId;

    /** Anzeigename des Auslösers oder {@code anonym}. */
    @Column(name = "actor_name", length = 100)
    private String actorName;

    /** IP-Adresse des Aufrufers, hinter dem Reverse-Proxy aus {@code X-Forwarded-For}. */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "path", length = 300)
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    /** Dauer der Verarbeitung in Millisekunden. */
    @Column(name = "duration_ms")
    private Long durationMs;

    /** Zusatzangaben, etwa Ausnahmetyp und Stelle im Code. */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;
}
