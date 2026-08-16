import { describe, expect, it, vi } from 'vitest';
import { copyText } from './clipboard.util';
import { toPlanLines, toSkillPlanText } from './skill-plan.util';

describe('toSkillPlanText', () => {
  it('schreibt eine Zeile je Skill mit römischer Stufe', () => {
    // Genau dieses Format nimmt der EVE-Client beim Anlegen eines Skillplans an.
    const text = toSkillPlanText([
      { skillName: 'Power Grid Management', level: 5 },
      { skillName: 'Hull Upgrades', level: 4 },
    ]);

    expect(text).toBe('Power Grid Management V\nHull Upgrades IV');
  });

  it('deckt alle fünf Stufen ab', () => {
    // Verschiedene Namen: gleiche Namen würden zusammengefasst.
    const text = toSkillPlanText(
      [1, 2, 3, 4, 5].map((level) => ({ skillName: `Skill ${level}`, level })),
    );

    expect(text.split('\n')).toEqual([
      'Skill 1 I',
      'Skill 2 II',
      'Skill 3 III',
      'Skill 4 IV',
      'Skill 5 V',
    ]);
  });

  it('führt denselben Skill nur einmal, mit der höheren Stufe', () => {
    // Ein Skill kann zugleich Modul-Voraussetzung und Teil des Plans sein.
    const text = toSkillPlanText([
      { skillName: 'Hull Upgrades', level: 3 },
      { skillName: 'Hull Upgrades', level: 5 },
    ]);

    expect(text).toBe('Hull Upgrades V');
  });

  it('hält Stufen außerhalb des Bereichs fest', () => {
    expect(toSkillPlanText([{ skillName: 'S', level: 9 }])).toBe('S V');
    expect(toSkillPlanText([{ skillName: 'S', level: 0 }])).toBe('S I');
    expect(toSkillPlanText([{ skillName: 'S', level: -3 }])).toBe('S I');
  });

  it('rundet eine gebrochene Stufe', () => {
    expect(toSkillPlanText([{ skillName: 'S', level: 3.6 }])).toBe('S IV');
  });

  it('überspringt Einträge ohne Namen', () => {
    expect(toSkillPlanText([
      { skillName: '   ', level: 5 },
      { skillName: 'Hull Upgrades', level: 4 },
    ])).toBe('Hull Upgrades IV');
  });

  it('liefert für eine leere Liste einen leeren Text', () => {
    // Der Aufrufer erkennt daran, dass nichts zu kopieren ist.
    expect(toSkillPlanText([])).toBe('');
  });
});

describe('toPlanLines', () => {
  it('nimmt requiredLevel aus den Voraussetzungen', () => {
    expect(toPlanLines([{ skillName: 'S', requiredLevel: 4 }]))
      .toEqual([{ skillName: 'S', level: 4 }]);
  });

  it('nimmt level aus den Plan-Einträgen', () => {
    expect(toPlanLines([{ skillName: 'S', level: 3 }]))
      .toEqual([{ skillName: 'S', level: 3 }]);
  });

  it('mischt beide Herkünfte in einer Liste', () => {
    // Genau so kommen sie an: fehlende Voraussetzungen plus fehlende Plan-Skills.
    const lines = toPlanLines([
      { skillName: 'Aus Modul', requiredLevel: 2 },
      { skillName: 'Aus Plan', level: 5 },
    ]);

    expect(toSkillPlanText(lines)).toBe('Aus Modul II\nAus Plan V');
  });

  it('nimmt ohne jede Stufenangabe die höchste', () => {
    expect(toPlanLines([{ skillName: 'S' }])).toEqual([{ skillName: 'S', level: 5 }]);
  });
});

describe('copyText', () => {
  it('meldet Erfolg, wenn die Zwischenablage annimmt', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    await expect(copyText('Hull Upgrades V')).resolves.toBe(true);
    expect(writeText).toHaveBeenCalledWith('Hull Upgrades V');

    vi.unstubAllGlobals();
  });

  it('meldet einen Fehlschlag, statt ihn durchzureichen', async () => {
    // Ohne sicheren Kontext verweigert der Browser das Schreiben.
    vi.stubGlobal('navigator', {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('verweigert')) },
    });

    await expect(copyText('egal')).resolves.toBe(false);

    vi.unstubAllGlobals();
  });

  it('rührt die Zwischenablage bei leerem Text gar nicht an', async () => {
    const writeText = vi.fn();
    vi.stubGlobal('navigator', { clipboard: { writeText } });

    await expect(copyText('')).resolves.toBe(false);
    expect(writeText).not.toHaveBeenCalled();

    vi.unstubAllGlobals();
  });
});
