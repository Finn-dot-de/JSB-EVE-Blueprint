-- ============================================================
-- Asset Audit - Setup
-- Nach dem ersten Start des Backends ausfuehren.
-- Hibernate (ddl-auto=update) legt die Tabellen selbst an,
-- dieses Skript setzt nur Navigation und die Feintuning-Indizes.
-- ============================================================

-- 1. Navigations-Eintrag (erscheint unter "CorpTools" in der Sidebar)
INSERT INTO public.navigation_links (category, icon, label, required_role, url, active)
VALUES ('CorpTools', 'fa-solid fa-boxes-stacked', 'Asset Audit', 'ROLE_DIRECTOR', '/corp/assets', true);


-- 2. Indizes fuer die Auswertung
--    Hibernate legt die @Index-Definitionen aus CharacterAsset bereits an.
--    Die folgenden ergaenzen die Aggregat-Queries.

CREATE INDEX IF NOT EXISTS idx_asset_location_flag
    ON public.character_assets (location_flag);

CREATE INDEX IF NOT EXISTS idx_asset_char_type_qty
    ON public.character_assets (character_id, type_id) INCLUDE (quantity);

CREATE INDEX IF NOT EXISTS idx_characters_main
    ON public.characters (main_character_id);

CREATE INDEX IF NOT EXISTS idx_characters_corp
    ON public.characters (corporation_id);

CREATE INDEX IF NOT EXISTS idx_asset_locations_region
    ON public.asset_locations (region_name);


-- ============================================================
-- 3. Rueckbau (falls das Modul wieder entfernt werden soll)
-- ============================================================
-- DELETE FROM public.navigation_links WHERE url = '/corp/assets';
-- DROP TABLE IF EXISTS public.asset_locations;
-- DROP TABLE IF EXISTS public.market_prices;
-- ALTER TABLE public.character_assets
--     DROP COLUMN IF EXISTS root_location_id,
--     DROP COLUMN IF EXISTS location_flag,
--     DROP COLUMN IF EXISTS location_type,
--     DROP COLUMN IF EXISTS is_singleton;
