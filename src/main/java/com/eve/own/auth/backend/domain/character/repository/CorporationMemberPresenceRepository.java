package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CorporationMemberPresence;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CorporationMemberPresenceRepository extends JpaRepository<CorporationMemberPresence, Long> {

    /** Die Reihe eines Charakters, juengste zuerst - wofuer der Index gebaut ist. */
    List<CorporationMemberPresence> findByCharacterIdOrderByMeasuredAtDesc(Long characterId);

    List<CorporationMemberPresence> findByCharacterIdAndMeasuredAtAfter(Long characterId, Instant after);

    /**
     * Je Mitglied der Corporation die zuletzt geschriebene Zeile.
     *
     * <p>Sie ist der Vergleichspunkt, an dem sich entscheidet, ob der laufende
     * Abruf ueberhaupt etwas Neues sagt. Ausgewaehlt wird ueber {@code MAX(id)}
     * und nicht ueber {@code MAX(measured_at)}: die ID steigt mit der
     * Einfuegereihenfolge und ist eindeutig, waehrend zwei Zeilen denselben
     * Messzeitpunkt tragen koennen - dann liefert der Zeitvergleich zwei
     * "letzte" Zeilen und die Bremse wird zufaellig.</p>
     */
    @Query("""
            SELECT p FROM CorporationMemberPresence p
            WHERE p.corporationId = :corporationId
              AND p.id = (SELECT MAX(p2.id) FROM CorporationMemberPresence p2
                          WHERE p2.characterId = p.characterId
                            AND p2.corporationId = p.corporationId)
            """)
    List<CorporationMemberPresence> findLatestPerCharacter(Long corporationId);

    /**
     * Die Anwesenheitsreihen <b>der ganzen Corporation</b> ab einem Zeitpunkt.
     *
     * <p>Die Bewertung braucht sie corp-weit und nicht nur fuer das gerade
     * betrachtete Paar, und zwar wegen der Seltenheitsgewichtung: um zu wissen,
     * dass Jita ein Handelsknotenpunkt und ein bestimmtes System abgelegen ist,
     * muss man zaehlen koennen, wieviele <em>andere</em> Mitglieder dort je
     * gesehen wurden. Mit den Zeilen zweier Charaktere allein waere jeder Ort
     * gleich selten - und das Signal liefe verkehrt herum, genau wie der rohe
     * Mining-Tag.</p>
     *
     * <p>Die Menge begrenzt der Zeitpunkt, den
     * {@code AltDetectionProperties.presenceLookback} setzt. Ohne ihn laege hier
     * der volle Aufbewahrungszeitraum an einem Seitenaufruf.</p>
     */
    @Query("""
            SELECT p FROM CorporationMemberPresence p
            WHERE p.corporationId = :corporationId
              AND p.measuredAt >= :since
            """)
    List<CorporationMemberPresence> findByCorporationSince(Long corporationId, Instant since);

    /** Der Loeschlauf der Aufbewahrungsfrist. */
    @Modifying
    @Query("DELETE FROM CorporationMemberPresence p WHERE p.measuredAt < :threshold")
    int deleteOlderThan(Instant threshold);
}
