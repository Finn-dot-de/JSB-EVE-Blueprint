package com.eve.own.auth.backend.domain.fleet.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * Ein Teilnehmer einer erfassten Flotte.
 *
 * <p>Der Index auf {@code fleet_event_id} traegt beides: das Anzeigen einer
 * einzelnen Flotte und den Join der FAT-Statistik.</p>
 *
 * <p><b>Kein</b> Eindeutigkeitsindex auf (Flotte, Charakter), obwohl genau das
 * die fachliche Regel waere: Die Eindeutigkeit haengt heute allein daran, dass
 * vor dem Schreiben gesucht wird, und der 60-Sekunden-Scheduler kann sich mit
 * einem manuellen Abgleich ueberschneiden. Im Bestand koennen also Doppel
 * liegen, und {@code ddl-auto=update} scheiterte dann beim Start. Bis die
 * aufgeraeumt sind, zaehlt jede Auswertung ueber verschiedene Charaktere und
 * nicht ueber Zeilen.</p>
 */
@Entity
@Table(name = "fleet_attendance",
        indexes = @Index(name = "idx_fleet_attendance_event", columnList = "fleet_event_id"))
@Getter
@Setter
public class FleetAttendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fleetEventId;

    private Long characterId;
    private String characterName;

    private Long shipTypeId;
    private String shipName;

    private Instant joinTime;
}