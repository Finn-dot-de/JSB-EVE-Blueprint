import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CorpStatsComponent } from './corp-stats.component';
import { CharacterService, CorpStatsDto } from '../../services/character.service';

/** Eine Corp-Statistik, wie sie der Server liefert. */
function corp(overrides: Partial<CorpStatsDto> = {}): CorpStatsDto {
  return {
    corpId: 98000001,
    corpName: 'Corp Eins',
    totalEsiMembers: 10,
    registeredMains: 3,
    registeredAlts: 2,
    totalRegisteredChars: 5,
    authedMembers: [
      { mainId: 1, mainName: 'Alpha', portraitUrl: '', alts: [{ id: 2, name: 'Alpha Alt', portraitUrl: '', isMain: false }] },
      { mainId: 3, mainName: 'Beta', portraitUrl: '', alts: [] },
    ],
    unauthedMembers: [{ id: 9, name: 'Fremder Pilot', portraitUrl: '' }],
    ...overrides,
  } as CorpStatsDto;
}

describe('CorpStatsComponent', () => {
  let component: CorpStatsComponent;
  let charService: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(() => {
    charService = {
      getCorpStats: vi.fn().mockReturnValue(of([corp()])),
      getAllAccounts: vi.fn().mockReturnValue(
        of([
          { mainId: 1, mainName: 'Alpha', portraitUrl: '', corporationName: 'Corp', alts: [{ id: 2, name: 'Alpha Alt', portraitUrl: '', corporationName: 'Corp' }] },
          { mainId: 3, mainName: 'Beta', portraitUrl: '', corporationName: 'Corp', alts: [] },
        ]),
      ),
    };

    TestBed.configureTestingModule({
      providers: [{ provide: CharacterService, useValue: charService }],
    });
    component = TestBed.runInInjectionContext(() => new CorpStatsComponent());
  });

  describe('Laden', () => {
    it('lädt die Corp-Statistiken beim Start', () => {
      component.ngOnInit();

      expect(component.stats()).toHaveLength(1);
      expect(component.loading()).toBe(false);
    });

    it('zeigt die Meldung des Servers, wenn das Laden scheitert', () => {
      charService['getCorpStats'].mockReturnValue(
        throwError(() => ({ error: { message: 'ESI antwortet nicht.' } })),
      );

      component.loadCorpStats();

      expect(component.errorMsg()).toBe('ESI antwortet nicht.');
      expect(component.loading()).toBe(false);
    });

    it('lädt die Account-Liste erst beim Wechsel dorthin', () => {
      component.setTab('ACCOUNTS');

      expect(charService['getAllAccounts']).toHaveBeenCalled();
      expect(component.accounts()).toHaveLength(2);
    });

    it('lädt die Account-Liste nicht zweimal', () => {
      component.setTab('ACCOUNTS');
      charService['getAllAccounts'].mockClear();

      component.setTab('CORP');
      component.setTab('ACCOUNTS');

      expect(charService['getAllAccounts']).not.toHaveBeenCalled();
    });

    it('meldet einen Fehlschlag der Account-Liste', () => {
      charService['getAllAccounts'].mockReturnValue(throwError(() => ({})));

      component.loadAccounts();

      expect(component.errorMsg()).toContain('Account-Liste');
      expect(component.loadingAccounts()).toBe(false);
    });
  });

  describe('Abdeckungsquote', () => {
    it('rechnet den Anteil registrierter Charaktere aus', () => {
      expect(component.getPercentage(corp())).toBe(50);
    });

    it('kommt ohne ESI-Mitglieder ohne Division durch null aus', () => {
      expect(component.getPercentage(corp({ totalEsiMembers: 0 }))).toBe(0);
    });
  });

  describe('Aufklappen', () => {
    it('klappt eine Corporation auf und wieder zu', () => {
      component.toggleCorp(98000001);
      expect(component.expandedCorpId()).toBe(98000001);

      component.toggleCorp(98000001);
      expect(component.expandedCorpId()).toBeNull();
    });

    it('räumt beim Umschalten den Suchbegriff ab', () => {
      // Sonst filterte die nächste Corporation nach einem alten Begriff.
      component.searchQuery.set('alpha');

      component.toggleCorp(98000001);

      expect(component.searchQuery()).toBe('');
    });
  });

  describe('Filter', () => {
    it('gibt ohne Suchbegriff alle Mitglieder zurück', () => {
      expect(component.getFilteredAuthed(corp())).toHaveLength(2);
      expect(component.getFilteredUnauthed(corp())).toHaveLength(1);
    });

    it('findet einen Account über seinen Main', () => {
      component.searchQuery.set('beta');

      expect(component.getFilteredAuthed(corp())).toHaveLength(1);
    });

    it('findet einen Account auch über einen Alt', () => {
      component.searchQuery.set('alpha alt');

      expect(component.getFilteredAuthed(corp())).toHaveLength(1);
    });

    it('filtert die nicht registrierten Mitglieder mit', () => {
      component.searchQuery.set('fremder');

      expect(component.getFilteredUnauthed(corp())).toHaveLength(1);

      component.searchQuery.set('gibtsnicht');
      expect(component.getFilteredUnauthed(corp())).toHaveLength(0);
    });

    it('filtert die Account-Liste über Main und Alts', () => {
      component.setTab('ACCOUNTS');

      component.searchQueryAccounts.set('beta');
      expect(component.getFilteredAccounts()).toHaveLength(1);

      component.searchQueryAccounts.set('alpha alt');
      expect(component.getFilteredAccounts()).toHaveLength(1);

      component.searchQueryAccounts.set('');
      expect(component.getFilteredAccounts()).toHaveLength(2);
    });
  });
});
