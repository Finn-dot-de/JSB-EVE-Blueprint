import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  DiscordCharacterAudit,
  DiscordMapping,
  DiscordRoleAudit,
  DiscordService,
  DiscordSyncErgebnis,
  DiscordSyncZeile,
  DiscordUrsache,
} from '../../services/discord.service';
import { ToastService } from '../../services/toast.service'; // <-- NEU: Importieren

@Component({
  selector: 'app-discord-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './discord-admin.component.html',
  styleUrl: './discord-admin.component.scss',
})
export class DiscordAdminComponent implements OnInit {
  private discordService = inject(DiscordService);
  private toastService = inject(ToastService);

  mappings = signal<DiscordMapping[]>([]);
  loading = signal(true);

  /**
   * Das Prüfergebnis - `null` heißt "noch nicht geprüft", nicht "alles gut".
   *
   * <p>Die leere Liste ist ein gültiges Ergebnis (kein Konto verknüpft) und
   * muss sich vom ungeprüften Zustand unterscheiden lassen. Sonst zeigte die
   * Seite beim Öffnen dieselbe Ruhe wie nach einer bestandenen Prüfung.</p>
   */
  audit = signal<DiscordRoleAudit[] | null>(null);
  auditLoading = signal(false);

  /**
   * Ob seit der letzten Prüfung etwas geschehen ist, das das Ergebnis überholt:
   * eine gespeicherte Zuordnung oder ein angestoßener Abgleich.
   *
   * <p>Ein Ergebnis, das vor der Änderung entstanden ist, beantwortet die
   * Frage "hat es gewirkt?" falsch - und zwar beruhigend falsch. Lieber als
   * veraltet ausweisen, als es stumm stehen zu lassen.</p>
   */
  auditVeraltet = signal(false);

  /**
   * Ob nur Charaktere mit Befund gezeigt werden.
   *
   * <p>Voreinstellung ja: Die Gegenüberstellung ist je Charakter eine ganze
   * Tabelle. Wer sie für jeden Verknüpften ausklappt, scrollt an den zwei
   * Zeilen vorbei, wegen derer er die Seite geöffnet hat. Wer nachsehen will,
   * was ein unauffälliger Charakter trägt, schaltet um.</p>
   */
  nurAuffaellige = signal(true);

  /** Für welchen Charakter gerade ein Abgleich läuft - `null`, wenn keiner läuft. */
  syncLaeuft = signal<number | null>(null);

  /**
   * Das Ergebnis des angestoßenen Abgleichs je Charakter.
   *
   * <p>Bleibt stehen, bis erneut geprüft wird. Der Nutzer stößt den Abgleich
   * an, weil vorher etwas nicht ging - die Antwort darauf verschwinden zu
   * lassen, sobald er woanders hinklickt, wäre derselbe stumme Knopf wie
   * zuvor.</p>
   */
  syncErgebnisse = signal<Record<number, DiscordSyncErgebnis>>({});

  /**
   * Wenn schon der Aufruf selbst scheiterte - dann gibt es kein Ergebnis, aber
   * sehr wohl eine Auskunft. Sie gehört an dieselbe Stelle wie das Ergebnis.
   */
  syncFehler = signal<Record<number, string>>({});

  /**
   * Charaktere, deren Stand nach einem Abgleich einzeln neu geholt wurde.
   *
   * <p>Überstimmt die aus der Prüfung abgeleitete Zeile. Ein Abgleich, nach dem
   * dieselbe fehlende Rolle dasteht wie vorher, liest sich wie ein Fehlschlag,
   * obwohl er gewirkt hat.</p>
   */
  private aktualisiert = signal<Record<number, DiscordCharacterAudit>>({});

  /** Kurze Etiketten zu den Ursachen. Der ausformulierte Satz kommt als `grund` vom Server. */
  private static readonly URSACHE_KURZ: Record<DiscordUrsache, string> = {
    KEIN_MAPPING: 'Keine Zuordnung',
    MAPPING_OHNE_ROLLEN_ID: 'Zuordnung ohne Rollen-ID',
    KEINE_VERKNUEPFUNG: 'Kein Discord-Konto',
    ZUGRIFF_VERWEIGERT: 'Zugriff verweigert (403)',
    KONTO_NICHT_AUF_SERVER: 'Nicht auf dem Server (404)',
    DISCORD_NICHT_ERREICHBAR: 'Discord nicht erreichbar',
    ROLLE_AUF_SERVER_UNBEKANNT: 'Rolle auf dem Server unbekannt',
    ABGLEICH_STEHT_AUS: 'Abgleich steht aus',
    UNBEKANNT: 'Ursache unbekannt',
  };

  /**
   * Von der Discord-Rollen-ID zurück auf den Namen der Auth-Rolle.
   *
   * <p>Das Backend vergleicht Rollen-IDs, weil Discord nur die kennt. Eine
   * achtzehnstellige Zahl sagt aber niemandem, welche Rolle fehlt - die
   * Zuordnung dafür steht bereits auf derselben Seite in der Tabelle
   * darüber.</p>
   */
  private rollenNamen = computed(() => {
    const namen = new Map<string, string>();
    for (const mapping of this.mappings()) {
      if (mapping.discordRoleId) {
        namen.set(mapping.discordRoleId, mapping.authRole);
      }
    }
    return namen;
  });

  /**
   * Der Fall, der still Rollen kostet: ein Discord-Konto, mehrere Charaktere,
   * verschiedene Soll-Rollen.
   *
   * <p>Steht zuoberst und getrennt von den übrigen Abweichungen. Er ist kein
   * Anzeigefehler und auch keine Folge eines fehlgeschlagenen Aufrufs: Der
   * Abgleich läuft für jeden Charakter einmal über dasselbe Konto, und der
   * letzte Lauf überschreibt den vorigen. Wer nur die fehlende Rolle sieht und
   * sie von Hand nachträgt, hat sie bis zum nächsten Abgleich.</p>
   */
  konflikte = computed(() => (this.audit() ?? []).filter((eintrag) => eintrag.sollUneinig));

  /**
   * Dieselbe Prüfung, aufgeschlüsselt je Charakter - die Sicht, in der die
   * Frage gestellt wird ("was hat Tom, und was fehlt ihm").
   *
   * <p>Abgeleitet aus dem Kontoergebnis und nicht ein zweites Mal geholt: Jeder
   * Charakter eines Kontos bekommt dessen Zeilen unverändert; das ist genau die
   * Abbildung, die `/audit/characters` serverseitig vornimmt. Sie hier zu
   * machen kostet keinen zweiten Durchlauf über alle Konten - und jeder solche
   * Durchlauf ist ein Discord-Aufruf je verknüpftem Konto an einer
   * Schnittstelle mit Rate Limit.</p>
   *
   * <p>Verglichen wird nichts: Es werden nur Felder übernommen. Zwei Stellen,
   * die dasselbe <em>vergleichen</em>, liefen auseinander - hier gibt es nur
   * eine, und sie steht im Backend.</p>
   */
  charaktere = computed<DiscordCharacterAudit[]>(() => {
    const eintraege = this.audit();
    if (eintraege === null) {
      return [];
    }
    const einzeln = this.aktualisiert();
    const zeilen: DiscordCharacterAudit[] = [];
    for (const eintrag of eintraege) {
      for (const charakter of eintrag.charaktere) {
        zeilen.push(
          einzeln[charakter.characterId] ??
            this.sichtAuf(eintrag, charakter.characterId, charakter.name),
        );
      }
    }
    return zeilen.sort((a, b) => a.characterName.localeCompare(b.characterName));
  });

  /**
   * Charaktere, an denen etwas zu tun oder etwas nicht feststellbar ist.
   *
   * <p>`!pruefbar` zählt mit, obwohl es kein Rollenbefund ist: Über dieses
   * Konto ist nichts bekannt, und das darf nicht dieselbe Ruhe ausstrahlen wie
   * eine bestandene Prüfung. Es steht deshalb in der Liste, aber mit eigener
   * Darstellung - nicht unter den fehlenden Rollen.</p>
   */
  auffaellige = computed(() =>
    this.charaktere().filter((zeile) => this.hatBefund(zeile) || !zeile.pruefbar),
  );

  /** Was die Liste zeigt - je nach Schalter alle oder nur die auffälligen. */
  sichtbareCharaktere = computed(() =>
    this.nurAuffaellige() ? this.auffaellige() : this.charaktere(),
  );

  /** Wie viele Charaktere geprüft wurden und in Ordnung waren - als Zahl, nicht als Liste. */
  unauffaellige = computed(() => this.charaktere().length - this.auffaellige().length);

  /** Ob die Prüfung gelaufen ist und nichts gefunden hat. */
  ohneBefund = computed(
    () => this.audit() !== null && this.konflikte().length === 0 && this.auffaellige().length === 0,
  );

  ngOnInit() {
    this.discordService.getMappings().subscribe({
      next: (data) => {
        this.mappings.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.toastService.error('Zugriff verweigert oder Fehler beim Laden der Rollen!');
        this.loading.set(false);
      },
    });
  }

  saveMapping(mapping: DiscordMapping) {
    this.discordService.saveMapping(mapping).subscribe({
      next: () => {
        this.toastService.success(`Mapping für ${mapping.authRole} gespeichert!`);
        // Nur nach einem Erfolg: Nach einem Fehlschlag steht in Discord noch
        // dasselbe wie zur Zeit der Prüfung, das Ergebnis gilt also weiter.
        if (this.audit() !== null) {
          this.auditVeraltet.set(true);
        }
      },
      error: () => this.toastService.error(`Fehler beim Speichern von ${mapping.authRole}.`),
    });
  }

  /**
   * Fragt Discord nach dem Ist-Zustand. Ändert nichts.
   *
   * <p>Auf Anforderung statt beim Öffnen der Seite: Jede Prüfung kostet einen
   * Aufruf je verknüpftem Konto an eine Schnittstelle mit Rate Limit, in der
   * der Abgleich alle dreißig Minuten ohnehin schreibt. Wer nur eine
   * Rollen-ID nachschlagen will, soll das nicht auslösen.</p>
   */
  pruefen() {
    if (this.auditLoading()) {
      return;
    }
    this.auditLoading.set(true);
    this.discordService.getAudit().subscribe({
      next: (data) => {
        this.audit.set(data);
        this.auditVeraltet.set(false);
        // Die Einzelstände und die Abgleichsergebnisse beziehen sich auf die
        // Lage von vorher. Neben frischen Zahlen stehengelassen behaupteten
        // sie einen Stand, den gerade niemand mehr geprüft hat.
        this.aktualisiert.set({});
        this.syncErgebnisse.set({});
        this.syncFehler.set({});
        this.auditLoading.set(false);
      },
      error: () => {
        this.toastService.error('Die Prüfung konnte nicht ausgeführt werden.');
        // Das alte Ergebnis bleibt stehen und bleibt als veraltet markiert -
        // es auf null zu setzen sähe aus wie "nichts gefunden".
        this.auditLoading.set(false);
      },
    });
  }

  /**
   * Führt den Abgleich für einen Charakter sofort aus und behält die Antwort.
   *
   * <p>Das Ergebnis ist der Zweck des Knopfes. Ein Knopf, der nur "fertig"
   * sagt, hilft hier niemandem: Angestoßen wird er, weil vorher etwas nicht
   * ging - und "ging wieder nicht, weil 403" ist die Auskunft, wegen der man
   * ihn drückt.</p>
   */
  stosseAn(characterId: number) {
    if (this.syncLaeuft() !== null) {
      return;
    }
    this.syncLaeuft.set(characterId);
    this.syncFehler.update((bisher) => this.ohne(bisher, characterId));
    this.discordService.stosseAbgleichAn(characterId).subscribe({
      next: (ergebnis) => {
        this.syncErgebnisse.update((bisher) => ({ ...bisher, [characterId]: ergebnis }));
        this.syncLaeuft.set(null);
        this.holeStandNach(characterId, ergebnis);
      },
      error: (fehler: { status?: number }) => {
        this.syncFehler.update((bisher) => ({
          ...bisher,
          [characterId]: this.fehlertext(fehler?.status),
        }));
        this.syncLaeuft.set(null);
      },
    });
  }

  /** Das Ergebnis des letzten Abgleichs für diesen Charakter, falls einer lief. */
  ergebnisFuer(characterId: number): DiscordSyncErgebnis | null {
    return this.syncErgebnisse()[characterId] ?? null;
  }

  /** Warum der Abgleich gar nicht erst hinausging, falls schon der Aufruf scheiterte. */
  fehlerFuer(characterId: number): string | null {
    return this.syncFehler()[characterId] ?? null;
  }

  /**
   * Ob an diesem Charakter etwas zu tun ist.
   *
   * <p>Wortgleich zur Regel im Backend: Weder "nicht feststellbar" noch eine
   * handvergebene Rolle ohne Zuordnung sind ein Befund. Beides ergäbe eine
   * Meldung, auf die niemand etwas tun kann - und die zweite hat den Abgleich
   * schon einmal dazu gebracht, handvergebene Rollen abzuräumen.</p>
   */
  hatBefund(zeile: DiscordCharacterAudit): boolean {
    return (
      zeile.rollen.some((rolle) => rolle.zustand === 'FEHLT') ||
      zeile.weitereDiscordRollen.some((rolle) => rolle.verwaltet) ||
      zeile.sollUneinig
    );
  }

  /**
   * Ob Discord die Auskunft über dieses Konto verweigert (403).
   *
   * <p>Eigene Frage und nicht bloß `!pruefbar`: Beim 403 gibt es genau zwei
   * Ursachen, und beide kann der Leser prüfen. Bei einem 404 oder einem
   * Zeitablauf sind es andere - ein gemeinsamer Text für alle drei wäre in zwei
   * Dritteln der Fälle falsch.</p>
   */
  zugriffVerweigert(zeile: DiscordCharacterAudit): boolean {
    return (
      !zeile.pruefbar && zeile.rollen.some((rolle) => rolle.ursache === 'ZUGRIFF_VERWEIGERT')
    );
  }

  /** Das kurze Etikett zur Ursache. Unbekannte Werte stehen roh da, statt zu verschwinden. */
  ursacheKurz(ursache: DiscordUrsache | null): string {
    if (ursache === null) {
      return '';
    }
    return DiscordAdminComponent.URSACHE_KURZ[ursache] ?? ursache;
  }

  /**
   * Ob das Konto eine Rolle trägt, die das Auth verwaltet, dieser Charakter
   * aber nicht haben soll.
   *
   * <p>Die einzige der zusätzlichen Rollen, die überhaupt ein Befund ist -
   * hier stehen Rechte offen. Alle übrigen sind von Hand vergeben und gehen
   * das Auth nichts an.</p>
   */
  hatUeberzaehlige(zeile: DiscordCharacterAudit): boolean {
    return zeile.weitereDiscordRollen.some((rolle) => rolle.verwaltet);
  }

  /** Was mit dieser Rolle geschah - im Klartext, samt der Richtung. */
  aktionText(zeile: DiscordSyncZeile): string {
    if (zeile.aktion === 'VERGEBEN') {
      return zeile.erfolg ? 'gesetzt' : 'konnte nicht gesetzt werden';
    }
    return zeile.erfolg ? 'entzogen' : 'konnte nicht entzogen werden';
  }

  /** Der Name der Auth-Rolle, oder die rohe ID, wenn dazu keine Zuordnung mehr steht. */
  rollenName(discordRoleId: string): string {
    return this.rollenNamen().get(discordRoleId) ?? discordRoleId;
  }

  /** Ob dieser Charakter der Main ist, an dem das Soll hängt. */
  istMain(eintrag: DiscordRoleAudit, characterId: number): boolean {
    return eintrag.mainCharacterId === characterId;
  }

  /** Dieselben Zeilen, nur aus der Sicht eines der Charaktere des Kontos. */
  private sichtAuf(
    eintrag: DiscordRoleAudit,
    characterId: number,
    characterName: string,
  ): DiscordCharacterAudit {
    return {
      characterId,
      characterName,
      mainCharacterId: eintrag.mainCharacterId,
      mainCharacterName: eintrag.mainCharacterName,
      discordUserId: eintrag.discordUserId,
      verknuepft: true,
      pruefbar: eintrag.pruefbar,
      hinweis: eintrag.hinweis,
      rollen: eintrag.rollen,
      weitereDiscordRollen: eintrag.weitereDiscordRollen,
      sollUneinig: eintrag.sollUneinig,
    };
  }

  /**
   * Holt nach einem wirksamen Abgleich den Stand dieses einen Charakters nach.
   *
   * <p>Nur wenn der Abgleich hinausging: Lief er gar nicht erst, hat sich in
   * Discord nichts geändert, und ein zweiter Aufruf brächte dieselben Zahlen
   * zurück.</p>
   */
  private holeStandNach(characterId: number, ergebnis: DiscordSyncErgebnis) {
    if (!ergebnis.ausgefuehrt) {
      return;
    }
    // Die Geschwister am selben Konto bleiben auf dem Stand der Prüfung - der
    // Abgleich hat gerade unter ihnen weggeschrieben. Sie einzeln nachzuholen
    // hieße, für jeden einen weiteren Discord-Aufruf auszulösen.
    if (this.geschwisterAmKonto(ergebnis.discordUserId) > 1) {
      this.auditVeraltet.set(true);
    }
    this.discordService.getCharacterAudit(characterId).subscribe({
      next: (stand) => this.aktualisiert.update((bisher) => ({ ...bisher, [characterId]: stand })),
      // Die Zeile darunter stammt weiterhin aus der Prüfung von vorher, und
      // der Abgleich hat gerade etwas geändert. Das Ergebnis darüber bleibt
      // die verlässliche Auskunft.
      error: () => this.auditVeraltet.set(true),
    });
  }

  /** Wie viele Charaktere an diesem Konto hängen. */
  private geschwisterAmKonto(discordUserId: string | null): number {
    if (discordUserId === null) {
      return 0;
    }
    return this.charaktere().filter((zeile) => zeile.discordUserId === discordUserId).length;
  }

  /** Was zu sagen ist, wenn schon der Aufruf scheiterte - nach dem, was bekannt ist. */
  private fehlertext(status: number | undefined): string {
    if (status === 404) {
      return 'Diesen Charakter kennt das Auth nicht (mehr) - der Abgleich lief nicht.';
    }
    return `Der Abgleich konnte nicht angestoßen werden (HTTP ${status ?? '?'}). In Discord hat sich dadurch nichts geändert.`;
  }

  /** Einen Eintrag entfernen, ohne das übrige Verzeichnis anzufassen. */
  private ohne<T>(verzeichnis: Record<number, T>, schluessel: number): Record<number, T> {
    const kopie = { ...verzeichnis };
    delete kopie[schluessel];
    return kopie;
  }
}
