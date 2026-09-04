package com.eve.own.auth.backend.domain.fleet.repository;

import com.eve.own.auth.backend.domain.fleet.entity.FleetPing;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Zugriff auf die abgesetzten Flotten-Pings. */
@Repository
public interface FleetPingRepository extends JpaRepository<FleetPing, Long> {

    /**
     * Der juengste Ping eines Charakters - die eine Frage der Wartezeit.
     *
     * <p>Ohne Zustandsfilter, und das ist Absicht: Gezaehlt wird das
     * <em>Absetzen</em>, nicht der Erfolg. Ein sofort wieder abgesagter Ping hat
     * trotzdem geklingelt.</p>
     */
    Optional<FleetPing> findTopByFcCharacterIdOrderByCreatedAtDesc(Long fcCharacterId);

    /**
     * Die Rechenschaftsliste.
     *
     * <p>Fest auf 50 begrenzt wie {@code findTop200ByOrderByOccurredAtDesc} in
     * den Protokoll-Repositories: Wer "wer hat das gepingt" fragt, meint die
     * letzten Tage. Eine unbegrenzte Abfrage waere eine, die mit der Zeit
     * langsam wird, ohne dass es jemand merkt.</p>
     */
    List<FleetPing> findTop50ByOrderByCreatedAtDesc();
}
