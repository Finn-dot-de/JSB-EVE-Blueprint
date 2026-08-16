package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein moeglicher Bauort.
 *
 * <p>Die Stammdaten helfen hier nicht: {@code mapDenormalize} enthaelt Monde,
 * Planeten und Tore, aber <em>keine</em> Stationen, und eine Tabelle
 * {@code staStations} gibt es in dieser Datenbank nicht. Alles, was ein Ort ist,
 * muss also aus ESI kommen und hier gesammelt werden.</p>
 *
 * <p>{@code servicesKnown} ist die ehrliche Antwort auf ein echtes Problem: fuer
 * fremde Strukturen liefert ESI keine Dienste. Ob dort gefertigt werden kann,
 * weiss man schlicht nicht - und die Oberflaeche sagt das dann auch, statt zu
 * raten.</p>
 */
@Entity
@Table(name = "industry_structures")
@Getter
@Setter
public class IndustryStructure {

    @Id
    @Column(name = "structure_id")
    private Long structureId;

    @Column(length = 255)
    private String name;

    @Column(name = "solar_system_id")
    private Long solarSystemId;

    @Column(name = "system_name", length = 128)
    private String systemName;

    @Column(name = "security_status")
    private Double securityStatus;

    /** Der Strukturtyp - daraus kommt der Name (Tatara, Raitaru, ...). */
    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "type_name", length = 128)
    private String typeName;

    @Column(name = "owner_corporation_id")
    private Long ownerCorporationId;

    /** CORP, PUBLIC oder NPC. */
    @Column(nullable = false, length = 12)
    private String source;

    /** Ob dort gefertigt werden kann - nur aussagekraeftig, wenn die Dienste bekannt sind. */
    @Column(name = "manufacturing_online")
    private Boolean manufacturingOnline;

    /** Ob dort wiederaufbereitet werden kann. */
    @Column(name = "reprocessing_online")
    private Boolean reprocessingOnline;

    /** Ob dort Reaktionen laufen koennen. */
    @Column(name = "reactions_online")
    private Boolean reactionsOnline;

    /**
     * Ob die Dienste ueberhaupt bekannt sind.
     *
     * <p>Bei fremden Strukturen ist das {@code false}, und dann duerfen die drei
     * Felder darueber nicht als "nein" gelesen werden - sie bedeuten "unbekannt".</p>
     */
    @Column(name = "services_known", nullable = false)
    private Boolean servicesKnown = false;

    @Column(name = "fuel_expires")
    private Instant fuelExpires;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
