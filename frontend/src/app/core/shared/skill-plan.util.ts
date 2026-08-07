/** Eine Zeile eines Skillplans. */
export interface SkillPlanLine {
  readonly skillName: string;
  /** Die Stufe, auf die trainiert werden soll. */
  readonly level: number;
}

/** EVE schreibt Skill-Stufen als römische Ziffer, Index 0 bleibt frei. */
const ROMAN_LEVELS = ['', 'I', 'II', 'III', 'IV', 'V'] as const;

const MIN_LEVEL = 1;
const MAX_LEVEL = 5;

/**
 * Bringt Skills in die Form, die EVE beim Einfügen erwartet.
 *
 * Eine Zeile je Skill, dahinter die Stufe als römische Ziffer - genau das
 * Format, das der Client beim Anlegen eines Skillplans akzeptiert und das
 * auch der Import dieser Anwendung wieder lesen kann.
 *
 * Derselbe Skill kann aus zwei Quellen kommen: einmal als Voraussetzung eines
 * Moduls, einmal aus dem Skillplan - womöglich auf verschiedenen Stufen. Er
 * erscheint deshalb nur einmal, mit der höheren.
 */
export function toSkillPlanText(skills: readonly SkillPlanLine[]): string {
  const highest = new Map<string, number>();
  for (const skill of skills) {
    const name = skill.skillName?.trim();
    if (!name) continue;

    const level = Math.min(Math.max(Math.round(skill.level), MIN_LEVEL), MAX_LEVEL);
    highest.set(name, Math.max(highest.get(name) ?? 0, level));
  }

  return [...highest.entries()]
    .map(([name, level]) => `${name} ${ROMAN_LEVELS[level]}`)
    .join('\n');
}

/**
 * Fasst fehlende Skills mehrerer Herkünfte zu einer Trainingsliste zusammen.
 *
 * Die Voraussetzungen liefern `requiredLevel`, der Skillplan `level` - beide
 * meinen dasselbe. Diese Umformung erspart jeder Aufrufstelle die Abbildung.
 */
export function toPlanLines(
  skills: readonly { skillName: string; requiredLevel?: number; level?: number }[],
): SkillPlanLine[] {
  return skills.map((skill) => ({
    skillName: skill.skillName,
    level: skill.requiredLevel ?? skill.level ?? MAX_LEVEL,
  }));
}
