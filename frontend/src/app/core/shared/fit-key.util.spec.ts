import { describe, expect, it } from 'vitest';
import { fitKey } from './fit-key.util';

describe('fitKey', () => {
  it('hält zwei Fittings derselben Hülle auseinander', () => {
    // Über die typeId würden beide denselben Aufklapp-Zustand teilen.
    expect(fitKey({ fitId: 11, typeId: 33472 })).not.toBe(fitKey({ fitId: 12, typeId: 33472 }));
  });

  it('vergibt auch dem Sandbox-Fitting ohne ID einen Schlüssel', () => {
    expect(fitKey({ fitId: null, typeId: 33472 })).toBe(-33472);
  });

  it('kann den Schlüssel eines gespeicherten Fits nie mit dem der Sandbox verwechseln', () => {
    // Gespeicherte Fits sind positiv, die Sandbox ist negativ - sonst könnte
    // ein Sandbox-Lauf den Aufklapp-Zustand eines echten Fits treffen.
    expect(fitKey({ fitId: 33472, typeId: 33472 })).toBeGreaterThan(0);
    expect(fitKey({ fitId: null, typeId: 33472 })).toBeLessThan(0);
  });
});
