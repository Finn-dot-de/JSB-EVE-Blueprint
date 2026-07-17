-- ==========================================
-- 1. BASIS-LINKS (Für absolut JEDEN, der eingeloggt ist)
-- ==========================================
INSERT INTO navigation_links (label, url, icon, category, required_role) VALUES
                                                                             ('Dashboard', '/dashboard', 'fa-solid fa-gauge-high', NULL, 'ROLE_USER'),
                                                                             ('Services', '/services', 'fa-solid fa-gears', NULL, 'ROLE_USER'),
                                                                             ('CharLink', '/charlink', 'fa-solid fa-link', NULL, 'ROLE_USER');

-- ==========================================
-- 2. MEMBER-LINKS (Nur für Leute, die in deiner Corp sind)
-- ==========================================
INSERT INTO navigation_links (label, url, icon, category, required_role) VALUES
                                                                             ('Fittings and Doctrines', '/fleet/fittings', 'fa-solid fa-list', 'Fleet Management', 'ROLE_MEMBER'),
                                                                             ('Fleet Activity Tracking', '/fleet/tracking', 'fa-solid fa-space-shuttle', 'Fleet Management', 'ROLE_MEMBER'),
                                                                             ('Fleet Pings', '/fleet/pings', 'fa-solid fa-bell', 'Fleet Management', 'ROLE_MEMBER');

-- ==========================================
-- 3. ADMIN- & SONDER-ROLLEN (Nur für bestimmte Leute)
-- ==========================================
-- Unsere neu gebaute Rollen-Verwaltung (Sollten nur Admins oder Directors sehen)
INSERT INTO navigation_links (label, url, icon, category, required_role) VALUES
    ('Roles & Rights', '/groups/rights', 'fa-solid fa-shield-halved', 'Gruppen Management', 'ROLE_DIRECTOR');

-- Ein paar weitere Beispiele aus deinem Screenshot
INSERT INTO navigation_links (label, url, icon, category, required_role) VALUES
                                                                             ('Groups', '/groups/manage', 'fa-solid fa-users', 'Gruppen Management', 'ROLE_DIRECTOR'),
                                                                             ('Character Audit', '/corptools/audit', 'fa-solid fa-eye', 'CorpTools', 'ROLE_SENIOR_MEMBER');

-- ==========================================
-- 4. EXTERNE LINKS (Z.B. Wiki oder Patreon)
-- Da hier required_role = NULL ist, ist es komplett öffentlich!
-- ==========================================
INSERT INTO navigation_links (label, url, icon, category, required_role) VALUES
                                                                             ('SYN Wiki', 'https://wiki.deine-allianz.com', 'fa-solid fa-book', NULL, NULL),
                                                                             ('Patreon', 'https://patreon.com/deine-allianz', 'fa-brands fa-patreon', NULL, NULL);

-- 1. Wir definieren ROLE_IT_ADMIN in der neuen Tabelle und setzen das Flag (is_special) auf true (bzw. 1 in SQL)
INSERT INTO system_roles (role_name, description, is_special)
VALUES ('ROLE_IT_ADMIN', 'IT Administrator', true);

-- 2. Jetzt gibst du dir die Rolle manuell (ersetze die ID durch deine Charakter-ID)
INSERT INTO character_roles (character_id, roles)
VALUES (2118431553, 'ROLE_IT_ADMIN');