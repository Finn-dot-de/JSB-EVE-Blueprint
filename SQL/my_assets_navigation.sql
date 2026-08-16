-- =====================================================================
-- Navigationseintrag fuer die Mitglieder-Selbstauskunft "Meine Assets".
--
-- Bewusst ohne category: der Eintrag landet damit in der persoenlichen
-- Gruppe neben Dashboard, Services und CharLink - dort sucht ein Mitglied
-- seine eigenen Sachen, nicht unter "CorpTools" (dort liegt das
-- Director-Audit unter /corp/assets).
--
-- required_role ROLE_USER: sichtbar fuer jeden eingeloggten Charakter.
-- Die Absicherung passiert ohnehin serverseitig im MyAssetController -
-- dieser Eintrag steuert nur die Sichtbarkeit im Menue.
-- =====================================================================

INSERT INTO public.navigation_links (category, icon, label, required_role, url, active)
VALUES ('CorpTools', 'fa-solid fa-box-open', 'My Assets', 'ROLE_USER', '/my/assets', true);
