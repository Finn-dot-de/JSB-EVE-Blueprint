package com.eve.own.auth.backend.domain.assets.repository;

import com.eve.own.auth.backend.domain.assets.entity.AssetLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetLocationRepository extends JpaRepository<AssetLocation, Long> {

    /**
     * Alle Root-Locations aus den Assets, fuer die es noch keinen Namens-Eintrag gibt.
     */
    @Query(value = """
            SELECT DISTINCT a.root_location_id
            FROM character_assets a
            WHERE a.root_location_id IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM asset_locations l
                  WHERE l.location_id = a.root_location_id
                    AND (l.resolve_failed IS NULL OR l.resolve_failed = false)
              )
            """, nativeQuery = true)
    List<Long> findUnresolvedLocationIds();

    /**
     * Standorte, die zwar einen Namen haben, aber kein Sonnensystem.
     *
     * <p>Sie fallen durch {@link #findUnresolvedLocationIds()}, denn sie gelten
     * als aufgeloest - ein Name ist ja da. Ohne Sonnensystem laesst sich aber
     * nicht sagen, ob das Material dort am Bauort liegt, und das ist genau die
     * Frage, die der Industrie-Assistent beantworten soll.</p>
     *
     * <p>Betroffen sind Stationen, deren System nie geholt wurde, und
     * Standorte der Art {@code SOLAR_SYSTEM}, bei denen die Kennung frueher nicht
     * zurueckgeschrieben wurde. Strukturen ohne Docking-Access bleiben aussen
     * vor: dort scheitert die Abfrage an fehlenden Rechten, ein erneuter Versuch
     * kostet nur einen 403.</p>
     */
    @Query(value = """
            SELECT l.location_id
            FROM asset_locations l
            WHERE l.system_id IS NULL
              AND COALESCE(l.resolve_failed, false) = false
              AND l.location_kind IN ('STATION', 'SOLAR_SYSTEM')
            """, nativeQuery = true)
    List<Long> findLocationIdsWithoutSystem();
}
