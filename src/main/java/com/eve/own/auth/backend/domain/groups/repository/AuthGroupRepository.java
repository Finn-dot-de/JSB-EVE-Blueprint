package com.eve.own.auth.backend.domain.groups.repository;

import com.eve.own.auth.backend.domain.groups.entity.AuthGroup;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Die Gruppen (SIGs). */
@Repository
public interface AuthGroupRepository extends JpaRepository<AuthGroup, Long> {

    /**
     * Alle Gruppen, nach Namen sortiert.
     *
     * <p>Die Sortierung gehoert hierher und nicht in die Anzeige: die Tabelle
     * kennt keine andere sinnvolle Ordnung, und ohne {@code ORDER BY} bestimmt
     * die Datenbank die Reihenfolge - die Zeilen springen dann nach jedem
     * Speichern.</p>
     */
    List<AuthGroup> findAllByOrderByNameAsc();

    /**
     * Die Gruppen, deren Leitung an mindestens einer dieser Rollen haengt.
     *
     * <p>Grundlage der Verwaltungsansicht: hinein geht der Rollensatz des
     * Betrachters, heraus kommen genau die Gruppen, ueber deren Anfragen er
     * entscheiden darf. Eine leere Liste heisst, dass er - sofern er nicht
     * ohnehin Admin ist - nichts zu entscheiden hat.</p>
     *
     * <p>Ausgeschrieben statt abgeleitet: die Leitungsrollen liegen seit der
     * Umstellung in einer eigenen Tabelle, und ein abgeleitetes
     * {@code findByLeaderRoleNamesIn} setzt eine Sammlung mit einer Menge
     * gleich - was Hibernate je nach Fassung anders uebersetzt oder gar nicht.
     * Der {@code join} ueber die Sammlung sagt genau, was gemeint ist: eine
     * Ueberschneidung. Das {@code distinct} gehoert dazu, weil eine Gruppe mit
     * zwei passenden Leitungsrollen sonst zweimal in der Liste stuende.</p>
     *
     * <p>Achtung beim Aufruf: eine leere Rollenmenge erzeugt ein
     * {@code IN ()}, das je nach Datenbank ein Syntaxfehler ist. Der Aufrufer
     * faengt diesen Fall vorher ab.</p>
     */
    @Query("SELECT DISTINCT g FROM AuthGroup g JOIN g.leaderRoleNames r WHERE r IN :roleNames")
    List<AuthGroup> findByLeaderRoleNameIn(@Param("roleNames") Collection<String> roleNames);

    /**
     * Ob bereits eine Gruppe an dieser Rolle haengt.
     *
     * <p>Zwei Gruppen auf derselben Rolle waeren nicht mehr auseinanderzuhalten:
     * die Mitgliedschaft steckt allein im Rollennamen, ein Beitritt zur einen
     * machte den Antragsteller stillschweigend auch zum Mitglied der anderen.</p>
     */
    boolean existsByRoleName(String roleName);
}
