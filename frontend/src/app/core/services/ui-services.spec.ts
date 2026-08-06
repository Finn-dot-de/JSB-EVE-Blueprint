import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfirmService } from './confirm.service';
import { ToastService } from './toast.service';

describe('ToastService', () => {
  let service: ToastService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({ providers: [ToastService] });
    service = TestBed.inject(ToastService);
  });

  afterEach(() => vi.useRealTimers());

  it('zeigt eine Meldung mit dem passenden Typ', () => {
    service.success('Gespeichert');
    service.error('Fehlgeschlagen');
    service.info('Hinweis');

    expect(service.toasts()).toHaveLength(3);
    expect(service.toasts().map((toast) => toast.type)).toEqual(['success', 'error', 'info']);
  });

  it('nimmt ohne Angabe den neutralen Typ', () => {
    service.show('Ohne Typ');

    expect(service.toasts()[0].type).toBe('info');
  });

  it('blendet eine Meldung nach vier Sekunden von selbst aus', () => {
    service.info('Verschwindet gleich');

    vi.advanceTimersByTime(4000);

    expect(service.toasts()).toHaveLength(0);
  });

  it('entfernt genau die angeklickte Meldung', () => {
    service.info('Erste');
    service.info('Zweite');
    const first = service.toasts()[0];

    service.remove(first.id);

    expect(service.toasts()).toHaveLength(1);
    expect(service.toasts()[0].message).toBe('Zweite');
  });

  it('vergibt für jede Meldung eine eigene Kennung', () => {
    service.info('Erste');
    service.info('Zweite');

    const [first, second] = service.toasts();
    expect(first.id).not.toBe(second.id);
  });
});

describe('ConfirmService', () => {
  let service: ConfirmService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ConfirmService] });
    service = TestBed.inject(ConfirmService);
  });

  it('startet geschlossen', () => {
    expect(service.state().isOpen).toBe(false);
  });

  it('öffnet den Dialog mit Titel und Text', () => {
    void service.ask('Wirklich löschen?', 'Das kann nicht rückgängig gemacht werden.');

    const state = service.state();
    expect(state.isOpen).toBe(true);
    expect(state.title).toBe('Wirklich löschen?');
    expect(state.message).toBe('Das kann nicht rückgängig gemacht werden.');
  });

  it('nimmt für die Knöpfe Vorgaben an', () => {
    void service.ask('Titel', 'Text');

    expect(service.state().confirmText).toBe('Ja');
    expect(service.state().cancelText).toBe('Abbrechen');
  });

  it('übernimmt eigene Beschriftungen', () => {
    void service.ask('Titel', 'Text', 'Löschen', 'Behalten');

    expect(service.state().confirmText).toBe('Löschen');
    expect(service.state().cancelText).toBe('Behalten');
  });

  it('liefert die Zustimmung an den Wartenden zurück', async () => {
    const answer = service.ask('Titel', 'Text');

    service.respond(true);

    await expect(answer).resolves.toBe(true);
    expect(service.state().isOpen).toBe(false);
  });

  it('liefert auch die Ablehnung zurück', async () => {
    const answer = service.ask('Titel', 'Text');

    service.respond(false);

    await expect(answer).resolves.toBe(false);
  });

  it('antwortet nicht zweimal auf dieselbe Frage', () => {
    // Nach dem Schliessen ist die Rückmeldefunktion abgeräumt.
    void service.ask('Titel', 'Text');
    service.respond(true);

    expect(() => service.respond(true)).not.toThrow();
    expect(service.state().isOpen).toBe(false);
  });
});
