import { FitReadinessDto } from '../services/readiness.service';

/**
 * Ein stabiler Schlüssel je Fit.
 *
 * <p>Nicht die typeId: eine Doktrin kann zwei Fits derselben Hülle enthalten,
 * die sich sonst den Aufklapp-Zustand teilen würden. Der Sandbox-Fit hat keine
 * ID - er steht ohnehin allein und immer offen.</p>
 *
 * <p>Die Funktion steht hier und nicht in einer der Komponenten, weil das
 * Readiness Board, die Sandbox und die gemeinsame Fit-Kachel denselben
 * Schlüssel bilden müssen. Zwei Fassungen davon wären zwei Aufklapp-Zustände,
 * die auseinanderlaufen, sobald jemand nur eine davon ändert.</p>
 */
export function fitKey(fit: Pick<FitReadinessDto, 'fitId' | 'typeId'>): number {
  return fit.fitId ?? -fit.typeId;
}
