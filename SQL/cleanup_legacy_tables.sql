-- Entfernt die Tabellen des früheren Corp-Tools, die der Buybot nicht mehr benutzt.
--
-- Hibernate löscht beim Schema-Update nie Tabellen, deshalb bleiben sie in einer
-- gewachsenen Datenbank liegen. Sie stören den Betrieb nicht, kosten aber Platz.
--
-- VOR DEM AUSFÜHREN SICHERN:
--   docker compose exec postgres pg_dump -U eve_user eve_own_auth > backup.sql
--
-- Ausführen:
--   docker compose exec -T postgres psql -U eve_user -d eve_own_auth -f - < SQL/cleanup_legacy_tables.sql

BEGIN;

-- Charakter-Audit: Assets, Skillpunkte, Loyalitätspunkte, Mining- und Ratting-Statistik
DROP TABLE IF EXISTS character_activity;
DROP TABLE IF EXISTS character_asset_summary;
DROP TABLE IF EXISTS character_assets;
DROP TABLE IF EXISTS character_lp;
DROP TABLE IF EXISTS character_stats;

-- Navigation der alten Portal-Oberfläche
DROP TABLE IF EXISTS navigation_links;

-- Discord-Rollen-Synchronisation; der Buybot meldet über Webhook, nicht über den Bot
DROP TABLE IF EXISTS discord_connections;
DROP TABLE IF EXISTS discord_role_mappings;

COMMIT;

-- Verbleiben müssen:
--   alliances, characters, character_roles, corporations,
--   system_roles, title_role_mappings          (Anmeldung und Rollen)
--   buyback_config, buyback_locations,
--   buyback_category_rules, buyback_type_rules,
--   buyback_contract_checks                    (Buybot)
--   audit_entries                              (Protokoll)

-- ==========================================================================
-- TEIL 2: Nicht gebrauchte Tabellen der EVE-Statikdatenbank
--
-- Der Importer holt inzwischen nur noch die vier Tabellen, die der Buybot
-- braucht. In einer gewachsenen Datenbank liegen die uebrigen aber noch
-- herum und belegen ueber 100 MB.
--
-- Ungefaehrlich: der naechste SDE-Import legt an, was er braucht.
-- ==========================================================================

BEGIN;

DROP TABLE IF EXISTS evesde."dgmAttributeCategories";
DROP TABLE IF EXISTS evesde."dgmAttributeTypes";
DROP TABLE IF EXISTS evesde."dgmEffects";
DROP TABLE IF EXISTS evesde."dgmTypeAttributes";
DROP TABLE IF EXISTS evesde."dgmTypeEffects";
DROP TABLE IF EXISTS evesde."industryActivity";
DROP TABLE IF EXISTS evesde."industryActivityMaterials";
DROP TABLE IF EXISTS evesde."industryActivityProbabilities";
DROP TABLE IF EXISTS evesde."industryActivityProducts";
DROP TABLE IF EXISTS evesde."industryActivitySkills";
DROP TABLE IF EXISTS evesde."industryBlueprints";
DROP TABLE IF EXISTS evesde."invFlags";
DROP TABLE IF EXISTS evesde."invMarketGroups";
DROP TABLE IF EXISTS evesde."invMetaGroups";
DROP TABLE IF EXISTS evesde."invMetaTypes";
DROP TABLE IF EXISTS evesde."invTraits";
DROP TABLE IF EXISTS evesde."invVolumes";
DROP TABLE IF EXISTS evesde."mapConstellations";
DROP TABLE IF EXISTS evesde."mapDenormalize";
DROP TABLE IF EXISTS evesde."mapJumps";
DROP TABLE IF EXISTS evesde."mapRegions";
DROP TABLE IF EXISTS evesde."mapSolarSystemJumps";
DROP TABLE IF EXISTS evesde."mapSolarSystems";
DROP TABLE IF EXISTS evesde."planetSchematics";
DROP TABLE IF EXISTS evesde."planetSchematicsPinMap";
DROP TABLE IF EXISTS evesde."planetSchematicsTypeMap";

-- Von Hibernate frueher versehentlich angelegte Doppel der SDE-Tabellen
DROP TABLE IF EXISTS evesde.inv_categories;
DROP TABLE IF EXISTS evesde.inv_groups;

COMMIT;

VACUUM FULL;
