package com.eve.own.auth.backend.esi.etag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Ein Cache-Eintrag je ESI-Endpunkt-Aufruf.
 *
 * <p>Der Schluessel ist der aufgeloeste Request-Pfad, also z.B.
 * {@code /characters/95465499/mining/} oder {@code /characters/95465499/assets/?page=2}.
 * Damit teilen sich alle Aufrufer automatisch denselben Eintrag, ohne dass
 * irgendeine Fachentitaet ETag-Spalten mitschleppen muss.</p>
 */
@Entity
@Table(name = "esi_etags", indexes = {
        @Index(name = "idx_esi_etags_last_checked", columnList = "last_checked_at")
})
@Getter
@Setter
public class EsiEtag {

    /** Aufgeloester Request-Pfad, bei sehr langen Pfaden dessen SHA-256-Hash. */
    @Id
    @Column(name = "cache_key", length = 200)
    private String cacheKey;

    /** Der von ESI gelieferte ETag, meist in der schwachen Form {@code W/"..."}. */
    @Column(name = "etag", length = 200)
    private String etag;

    /**
     * Der letzte erfolgreiche Response-Body als roher JSON-Text.
     * Null, wenn der Body groesser war als {@code esi.etag.max-payload-bytes}
     * oder das Zwischenspeichern abgeschaltet ist.
     */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    /** Anzahl der Seiten laut {@code X-Pages}; nur bei paginierten Endpunkten gesetzt. */
    @Column(name = "page_count")
    private Integer pageCount;

    /** Wann ESI den Eintrag fuer abgelaufen erklaert ({@code Expires}-Header). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Wann zuletzt gegen ESI geprueft wurde - Grundlage fuer das Aufraeumen. */
    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** Wann ESI zuletzt tatsaechlich geaenderte Daten geliefert hat. */
    @Column(name = "last_changed_at")
    private Instant lastChangedAt;

    /** Wie oft ESI seither mit 304 geantwortet hat - reine Beobachtungsgroesse. */
    @Column(name = "not_modified_hits")
    private Long notModifiedHits = 0L;
}
