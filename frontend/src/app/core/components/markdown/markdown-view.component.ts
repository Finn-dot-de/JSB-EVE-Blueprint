import { Component, Input, computed, signal } from '@angular/core';
import { MdBlock, MdSpan, parseMarkdown } from './markdown';

/**
 * Gibt eine Reihe von Spans aus.
 *
 * <p>Eine eigene Komponente und nicht viermal derselbe Vorlagenblock: Spans
 * kommen in Ueberschrift, Absatz, Zitat und Listenpunkt vor. Viermal
 * abgeschrieben waere die sicherheitskritischste Vorlage des Projekts an vier
 * Stellen zu pflegen - und beim naechsten Umbau wuerde jemand drei davon
 * erwischen. Ein <code>ngTemplateOutlet</code> waere die Alternative gewesen,
 * verliert aber die Typpruefung an der Kontextvariablen; ausgerechnet hier
 * wollen wir sie behalten.</p>
 *
 * <p><code>display: contents</code>, damit der Wrapper den Textfluss von
 * <code>&lt;h2&gt;</code>, <code>&lt;p&gt;</code> und <code>&lt;li&gt;</code>
 * nicht bricht.</p>
 */
@Component({
  selector: 'app-markdown-spans',
  standalone: true,
  template: `
    @for (span of spans; track $index) {
      @switch (span.kind) {
        @case ('strong') {
          <strong>{{ span.text }}</strong>
        }
        @case ('em') {
          <em>{{ span.text }}</em>
        }
        @case ('code') {
          <code class="md-code-inline">{{ span.text }}</code>
        }
        @case ('link') {
          <!--
            [href] traegt hier bereits einen von safeHref geprueften Wert. Eine
            Attributbindung ist NICHT von sich aus sicher: Angulars
            SAFE_URL_PATTERN blockt ausschliesslich javascript:. Die Pruefung
            gehoert also vor die Bindung und nicht dahinter.
            rel="nofollow", weil der Text von einem Autor kommt und nicht von uns.
          -->
          <a
            class="md-link"
            [href]="span.href"
            target="_blank"
            rel="noopener noreferrer nofollow"
            >{{ span.text }}</a
          >
        }
        @default {
          {{ span.text }}
        }
      }
    }
  `,
  styles: [
    `
      :host {
        display: contents;
      }

      .md-code-inline {
        font-family: ui-monospace, 'Cascadia Code', 'Consolas', monospace;
        font-size: 0.88em;
        padding: 0.1em 0.35em;
        border-radius: 4px;
        background-color: color-mix(in srgb, var(--text-secondary) 14%, transparent);
        color: var(--text-primary);
      }

      .md-link {
        color: var(--accent-color);
        text-decoration: underline;
      }
    `,
  ],
})
export class MarkdownSpansComponent {
  @Input() spans: MdSpan[] = [];
}

/**
 * Zeigt einen Markdown-Lehrplan an - ohne eine einzige HTML-Zeichenkette.
 *
 * <p>In dieser Datei und in {@link parseMarkdown} gibt es keine Bindung auf
 * rohes Markup und keinen Vertrauensvorschuss an der Bereinigung von Angular -
 * die Bezeichner dafuer stehen hier bewusst nicht einmal als Wort, damit ein
 * einfaches grep die Regel durchsetzen kann. Jeder Text laeuft durch eine
 * Interpolation und ist damit per Konstruktion escapet; rohes HTML im Lehrplan
 * hat schlicht keinen Weg in den DOM, weil es keine HTML-Zeichenkette gibt, die
 * jemand weiterreichen koennte.</p>
 *
 * <p>Dieselbe Komponente bedient die aufgeklappte Themenkarte UND die Vorschau
 * im Editor. Das ist keine Bequemlichkeit: die Vorschau zeigt ungespeicherten
 * Text, der die Pruefung im Backend noch nicht gesehen hat. Ein zweiter
 * Anzeigepfad waere genau der Pfad, auf dem die Pruefung fehlt.</p>
 */
@Component({
  selector: 'app-markdown-view',
  standalone: true,
  imports: [MarkdownSpansComponent],
  template: `
    <div class="md">
      @for (block of blocks(); track $index) {
        @switch (block.kind) {
          @case ('heading') {
            @switch (block.level) {
              @case (1) {
                <h1 class="md-ueberschrift"><app-markdown-spans [spans]="block.spans" /></h1>
              }
              @case (2) {
                <h2 class="md-ueberschrift"><app-markdown-spans [spans]="block.spans" /></h2>
              }
              @default {
                <h3 class="md-ueberschrift"><app-markdown-spans [spans]="block.spans" /></h3>
              }
            }
          }
          @case ('paragraph') {
            <p class="md-absatz"><app-markdown-spans [spans]="block.spans" /></p>
          }
          @case ('list') {
            @if (block.ordered) {
              <ol class="md-liste">
                @for (punkt of block.items; track $index) {
                  <li><app-markdown-spans [spans]="punkt" /></li>
                }
              </ol>
            } @else {
              <ul class="md-liste">
                @for (punkt of block.items; track $index) {
                  <li><app-markdown-spans [spans]="punkt" /></li>
                }
              </ul>
            }
          }
          @case ('quote') {
            <blockquote class="md-zitat"><app-markdown-spans [spans]="block.spans" /></blockquote>
          }
          @case ('code') {
            <pre class="md-codeblock"><code>{{ block.text }}</code></pre>
          }
          @case ('image') {
            <!--
              [src] traegt einen von safeImageSrc geprueften Wert: https und der
              Host EXAKT auf der Allowlist. referrerpolicy="no-referrer", damit
              der fremde Host wenigstens nicht auch noch erfaehrt, welcher
              Lehrplan gerade offen ist - die IP sieht er ohnehin.
            -->
            <figure class="md-bild">
              <img
                [src]="block.src"
                [alt]="block.alt"
                loading="lazy"
                decoding="async"
                referrerpolicy="no-referrer"
              />
              @if (block.alt !== '') {
                <figcaption>{{ block.alt }}</figcaption>
              }
            </figure>
          }
          @case ('blocked') {
            <p class="hinweis warnung">
              <i class="fa-solid fa-triangle-exclamation"></i>{{ block.reason }}
            </p>
          }
          @case ('rule') {
            <hr class="md-trenner" />
          }
        }
      }
    </div>
  `,
  styles: [
    `
      .md {
        color: var(--text-primary);
        line-height: 1.55;
      }

      .md-ueberschrift {
        margin: 1.25rem 0 0.5rem;
        color: var(--text-primary);
        line-height: 1.3;
      }

      .md > :first-child {
        margin-top: 0;
      }

      h1.md-ueberschrift {
        font-size: 1.35rem;
      }
      h2.md-ueberschrift {
        font-size: 1.15rem;
      }
      h3.md-ueberschrift {
        font-size: 1rem;
      }

      .md-absatz {
        margin: 0 0 0.75rem;
      }

      .md-liste {
        margin: 0 0 0.75rem;
        padding-left: 1.4rem;

        li {
          margin-bottom: 0.25rem;
        }
      }

      .md-zitat {
        margin: 0 0 0.75rem;
        padding: 0.35rem 0 0.35rem 0.9rem;
        /* --accent-neutral statt --accent-color: im MA-Thema ist der Akzent rot,
           und ein rot umrandetes Zitat liest sich als Fehler. */
        border-left: 3px solid var(--accent-neutral);
        color: var(--text-secondary);
      }

      .md-codeblock {
        margin: 0 0 0.75rem;
        padding: 0.75rem 0.9rem;
        border-radius: 6px;
        overflow-x: auto;
        background-color: var(--bg-color);
        border: 1px solid var(--border-color);
        font-family: ui-monospace, 'Cascadia Code', 'Consolas', monospace;
        font-size: 0.85rem;
        color: var(--text-primary);
      }

      .md-bild {
        margin: 0 0 0.9rem;

        img {
          max-width: 100%;
          height: auto;
          border-radius: 6px;
          border: 1px solid var(--border-color);
          display: block;
        }

        figcaption {
          margin-top: 0.3rem;
          font-size: 0.8rem;
          color: var(--text-secondary);
        }
      }

      .md-trenner {
        border: none;
        border-top: 1px solid var(--border-color);
        margin: 1.25rem 0;
      }

      /* Wortgleich aus industry.component.scss - im Projekt gibt es ausser
         .surface-panel nichts Globales zu importieren. */
      .hinweis {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        padding: 0.75rem 1rem;
        border-radius: 6px;
        margin: 0 0 0.9rem;
        font-size: 0.92rem;

        &.warnung {
          color: var(--warning-color);
          background-color: color-mix(in srgb, var(--warning-color) 10%, transparent);
          border: 1px solid color-mix(in srgb, var(--warning-color) 35%, transparent);
        }
      }
    `,
  ],
})
export class MarkdownViewComponent {
  private readonly quelle = signal<string>('');

  /**
   * Der Markdown-Quelltext. <code>null</code> und <code>undefined</code> werden
   * zu einem leeren Text: ein Lehrplan, der noch nicht geladen ist, soll eine
   * leere Flaeche ergeben und keinen Absturz.
   */
  @Input()
  set source(wert: string | null | undefined) {
    this.quelle.set(typeof wert === 'string' ? wert : '');
  }

  readonly blocks = computed<MdBlock[]>(() => parseMarkdown(this.quelle()));
}
