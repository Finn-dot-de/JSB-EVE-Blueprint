package com.eve.own.auth.backend.domain.market;

import com.eve.own.auth.backend.domain.industry.repository.IndustryQueryRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wie weit jedes Sonnensystem von der Zielstation entfernt ist.
 *
 * <p>Gebraucht wird das fuer die Reichweite der Kaufgebote - siehe
 * {@link MarketOrderReach}. Die Karte kommt aus dem SDE
 * ({@code mapSolarSystemJumps}) und aendert sich zur Laufzeit nie, deshalb wird
 * sie einmal berechnet und dann behalten. Gemessen kostet die Breitensuche rund
 * eine Sekunde und liefert 5.228 erreichbare Systeme.</p>
 *
 * <p><b>Warum das ein eigener Dienst ist und keine Zeile im Abzug.</b> Die
 * Abfrage will eine Lesetransaktion, der Abzug selbst dauert gemessen 110
 * Sekunden. Waere {@code pull()} transaktional, hielte er eine
 * Datenbankverbindung ueber die ganze Netzarbeit hinweg offen - fuer eine
 * Abfrage, die einmal am Tag wirklich laeuft.</p>
 */
@Slf4j
@Service
public class MarketJumpDistances {

    private final IndustryQueryRepository queryRepo;
    private final MarketOrderProperties props;

    /** Einmal berechnet, dann behalten - die Sprungdaten stehen im SDE. */
    private final AtomicReference<Map<Long, Integer>> karte = new AtomicReference<>();

    public MarketJumpDistances(IndustryQueryRepository queryRepo, MarketOrderProperties props) {
        this.queryRepo = queryRepo;
        this.props = props;
    }

    /**
     * Die Sprungentfernungen zum System der Zielstation.
     *
     * <p>Bezugspunkt ist ausdruecklich das konfigurierte
     * {@code eve.market.station-system-id} und nicht Jita. Eine fest auf Jita
     * bezogene Karte waere nur fuer die Vorgabe richtig und fuer jede andere
     * Station eine falsche Zahl - und eine falsche Sprungzahl faellt niemandem
     * auf, sie macht nur den Preis leise verkehrt.</p>
     *
     * @return System-ID auf Spruenge; nicht enthaltene Systeme sind ueber Tore
     *         nicht erreichbar
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> toStationSystem() {
        Map<Long, Integer> vorhanden = karte.get();
        if (vorhanden != null) {
            return vorhanden;
        }
        Map<Long, Integer> berechnet = queryRepo.allJumpsFrom(props.stationSystemId());
        karte.set(berechnet);
        log.info("Sprungentfernungen zum Marktsystem {} berechnet: {} erreichbare Systeme.",
                props.stationSystemId(), berechnet.size());
        return berechnet;
    }
}
