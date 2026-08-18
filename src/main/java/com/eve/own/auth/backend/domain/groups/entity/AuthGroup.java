package com.eve.own.auth.backend.domain.groups.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Eine Gruppe (SIG), der ein Charakter beitreten kann.
 *
 * <p>Eine Gruppe ist technisch nichts weiter als eine Rolle mit Beiwerk: die
 * Mitgliedschaft steckt allein im {@code roles}-Satz des Charakters, diese
 * Zeile haelt nur Anzeigename, Beschreibung und die Zustaendigkeit fest. Damit
 * vergibt der bestehende Discord-Sync die Rechte weiter, ohne von Gruppen
 * ueberhaupt zu wissen.</p>
 *
 * <p>Die Rolle einer Gruppe muss in {@code system_roles} als besonders
 * ({@code is_special}) gefuehrt sein. Der Rollen-Sync baut den Rollensatz alle
 * zehn Minuten aus der Corp-Zugehoerigkeit neu auf und behaelt dabei nur die
 * besonderen Rollen - andernfalls verschwaende eine angenommene Anfrage beim
 * naechsten Lauf spurlos. Das Anlegen einer Gruppe erledigt das mit, siehe
 * {@code AuthGroupService.ensureSpecialRole}.</p>
 *
 * <p><b>Aufgegebene Spalten:</b> {@code leader_character_id} und
 * {@code leader_role_name} stehen in der Datenbank noch, werden aber von
 * niemandem mehr gelesen oder geschrieben. Die Leitung hing zuerst an einer
 * Person, dann an genau einer Rolle und heute an einer Menge von Rollen
 * ({@link #leaderRoleNames}, eigene Tabelle {@code auth_group_leader_roles}).
 * {@code ddl-auto=update} loescht Spalten nicht, und eine Wanderung nur fuer
 * das Aufraeumen zweier stillgelegter Spalten lohnt hier nicht.</p>
 *
 * <p>Der Inhalt von {@code leader_role_name} wandert dabei <b>nicht</b> mit:
 * bestehende Gruppen stehen nach der Umstellung ohne Leitung da, bis ein Admin
 * sie neu eintraegt. Bis dahin entscheiden ueber ihre Anfragen die globalen
 * Admins - der Antragsweg bleibt also gangbar, nur enger.</p>
 */
@Entity
@Table(name = "auth_groups")
@Getter
@Setter
public class AuthGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Anzeigename der Gruppe, z.B. "Wurmloch-SIG". */
    @Column(nullable = false, unique = true)
    private String name;

    /** Wofuer die Gruppe da ist - was in der Tabelle neben dem Namen steht. */
    @Column(length = 1000)
    private String description;

    /**
     * Die Rolle, die die Mitgliedschaft ausmacht - ein Schluessel aus
     * {@code system_roles}.
     *
     * <p>Bewusst der blanke Name statt einer JPA-Beziehung, wie es die uebrigen
     * Zuordnungen dieser Anwendung auch halten: der Rollensatz am Charakter ist
     * ebenfalls eine Sammlung blanker Zeichenketten, ein Verweis auf die
     * Entitaet muesste bei jedem Vergleich erst wieder aufgeloest werden.</p>
     */
    @Column(name = "role_name", nullable = false)
    private String roleName;

    /**
     * Welche Rollen ueber Anfragen entscheiden - leer heisst: nur Admins.
     *
     * <p>Rollen statt Personen, weil eine einzelne Person eine Sackgasse baut:
     * stellte ausgerechnet der Leiter selbst einen Antrag, blieb dieser liegen -
     * ueber den eigenen Antrag entscheidet niemand, und einen zweiten
     * Zustaendigen gab es nicht. Hinter einer Rolle stehen mehrere Traeger, von
     * denen einer einspringen kann.</p>
     *
     * <p>Eine <em>Menge</em> von Rollen, weil die Zustaendigkeit in der Praxis
     * quer zu den Rollen liegt: ueber "Cap Azubi" entscheiden Direktoren
     * <em>und</em> CEOs, ueber "Blops" die Strat-FCs <em>und</em> die
     * Skirmish-FCs. Mit nur einer Rolle blieb dafuer nur, eine kuenstliche
     * Sammelrolle anzulegen und sie allen Beteiligten zusaetzlich anzuhaengen -
     * eine zweite Rollenverwaltung neben der eigentlichen.</p>
     *
     * <p>Zustaendig ist, wer <b>mindestens eine</b> dieser Rollen traegt; die
     * Pruefung ist damit die Ueberschneidung zweier Mengen statt eines
     * Zeichenkettenvergleichs. Sie bleibt billig - beide Mengen liegen ohnehin
     * geladen vor, ein Nachladen des Charakters entfaellt weiterhin.</p>
     *
     * <p>Aufbau wie {@code Character.roles}: {@code @ElementCollection} in einer
     * eigenen Tabelle, EAGER geladen. Die Sammlung wird bei jeder
     * Zustaendigkeitspruefung gebraucht, LAZY erzwaenge dafuer eine offene
     * Sitzung und eine Nachfrage je Gruppe.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "auth_group_leader_roles",
            joinColumns = @JoinColumn(name = "group_id"))
    private Set<String> leaderRoleNames = new HashSet<>();
}
