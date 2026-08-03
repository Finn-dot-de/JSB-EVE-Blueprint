-- =====================================================================
-- character_skills: Spiegel von /characters/{id}/skills/
--
-- Grundlage fuer den Doktrin-Skillcheck (Tab "Skill Check" und Sandbox).
-- Im Dev-Setup legt Hibernate die Tabelle wegen SPRING_JPA_HIBERNATE_DDL_AUTO=update
-- selbst an; dieses Skript ist fuer Umgebungen gedacht, in denen ddl-auto
-- auf "validate" oder "none" steht.
-- =====================================================================

CREATE TABLE IF NOT EXISTS character_skills
(
    id            BIGSERIAL PRIMARY KEY,
    character_id  BIGINT  NOT NULL,
    skill_type_id BIGINT  NOT NULL,

    active_level  INTEGER NOT NULL,
    trained_level INTEGER,
    skillpoints   BIGINT
);

CREATE INDEX IF NOT EXISTS idx_skill_char_id ON character_skills (character_id);
CREATE INDEX IF NOT EXISTS idx_skill_type_id ON character_skills (skill_type_id);
CREATE INDEX IF NOT EXISTS idx_skill_type_char ON character_skills (skill_type_id, character_id);

-- Der Sync ersetzt pro Charakter immer den kompletten Snapshot
-- (delete by character_id + saveAll), deshalb kein Unique-Constraint noetig.
