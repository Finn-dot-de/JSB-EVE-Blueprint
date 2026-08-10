package com.eve.own.auth.backend.domain.industry.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Ein Spiegel eines Industriejobs aus ESI.
 *
 * <p>Die Job-Nummer ist der Primaerschluessel. Das schliesst strukturell aus,
 * dass derselbe Job doppelt gezaehlt wird, wenn er sowohl ueber den Charakter-
 * als auch ueber den Corp-Endpunkt hereinkommt.</p>
 *
 * <p>Die Aktivitaet steht doppelt: einmal wie ESI sie meldet, einmal in der
 * Zaehlung der SDE. Die beiden unterscheiden sich bei Reaktionen - ESI zaehlt 9,
 * die SDE 11 - und wer nur eine Spalte fuehrt, verliert entweder den Bezug zu
 * den Stammdaten oder den zur Quelle.</p>
 */
@Entity
@Table(name = "industry_jobs")
@Getter
@Setter
public class IndustryJob {

    @Id
    @Column(name = "job_id")
    private Long jobId;

    /** CHARACTER oder CORPORATION. */
    @Column(nullable = false, length = 12)
    private String source;

    @Column(name = "owner_character_id")
    private Long ownerCharacterId;

    @Column(name = "installer_id", nullable = false)
    private Long installerId;

    @Column(name = "facility_id")
    private Long facilityId;

    @Column(name = "activity_id_esi", nullable = false)
    private Integer activityIdEsi;

    /** Null, wenn ESI eine Aktivitaet meldet, die die SDE nicht kennt. */
    @Column(name = "activity_id_sde")
    private Integer activityIdSde;

    @Column(name = "blueprint_type_id")
    private Long blueprintTypeId;

    @Column(name = "product_type_id")
    private Long productTypeId;

    @Column(nullable = false)
    private Integer runs;

    @Column(name = "successful_runs")
    private Integer successfulRuns;

    /** active, paused, ready, delivered, cancelled oder reverted. */
    @Column(length = 16)
    private String status;

    @Column(name = "start_date")
    private Instant startDate;

    @Column(name = "end_date")
    private Instant endDate;

    @Column(name = "completed_date")
    private Instant completedDate;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
