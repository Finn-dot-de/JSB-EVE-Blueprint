package com.eve.own.auth.backend.domain.character.repository;

import com.eve.own.auth.backend.domain.character.entity.CharacterMining;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterMiningRepository extends JpaRepository<CharacterMining, Long> {

    @Modifying
    @Query("DELETE FROM CharacterMining m WHERE m.characterId = :characterId")
    void deleteByCharacterId(Long characterId);

    List<CharacterMining> findByCharacterIdIn(List<Long> characterIds);

    List<CharacterMining> findByCharacterId(Long characterId);

    /**
     * Rangliste der Mining-Leistung, aggregiert auf Account-Ebene (Main + Alts).
     *
     * <p>Spalten der Ergebniszeilen:
     * [0] mainId (Number), [1] mainName (String), [2] Volumen in m3 (Number),
     * [3] Jita-Buy-Wert in ISK (Number), [4] Anzahl Einheiten (Number).</p>
     *
     * <p>Der Monatsfilter laeuft ueber den Literalwert {@code 'ALL'} statt ueber
     * {@code null}: bei einer nativen Query koennte Postgres den Datentyp eines
     * null-Parameters sonst nicht bestimmen.</p>
     *
     * <p>Die Preise kommen aus {@code mining_tax_rates.current_jita_buy}, also aus
     * demselben Topf, aus dem sich auch die Steuerberechnung bedient - damit
     * passen Rangliste und Abrechnung zusammen. Erze ohne hinterlegten Preis
     * fliessen mit 0 ISK ein, zaehlen beim Volumen aber normal mit.</p>
     */
    @Query(value = """
            SELECT COALESCE(c.main_character_id, c.character_id)     AS main_id,
                   COALESCE(mc.name, c.name)                         AS main_name,
                   SUM(m.quantity * COALESCE(t.volume, 0))           AS volume_m3,
                   SUM(m.quantity * COALESCE(r.current_jita_buy, 0)) AS isk_value,
                   SUM(m.quantity)                                   AS units
            FROM character_mining m
            JOIN characters c ON c.character_id = m.character_id
            LEFT JOIN characters mc ON mc.character_id = c.main_character_id
            LEFT JOIN evesde."invTypes" t ON t."typeID" = m.type_id
            LEFT JOIN mining_tax_rates r ON r.type_id = m.type_id
            WHERE m.mining_date IS NOT NULL
              AND (:month = 'ALL' OR substring(m.mining_date, 1, 7) = :month)
            GROUP BY 1, 2
            ORDER BY 3 DESC
            """, nativeQuery = true)
    List<Object[]> aggregateMiningByAccount(String month);

    /** Monate, zu denen ueberhaupt Mining-Daten vorliegen - neueste zuerst. */
    @Query(value = """
            SELECT DISTINCT substring(mining_date, 1, 7)
            FROM character_mining
            WHERE mining_date IS NOT NULL
            ORDER BY 1 DESC
            """, nativeQuery = true)
    List<String> findAvailableMiningMonths();
}