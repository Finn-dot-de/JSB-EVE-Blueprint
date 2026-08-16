import { bootstrapApplication } from '@angular/platform-browser';
import { registerLocaleData } from '@angular/common';
import localeDe from '@angular/common/locales/de';
import { appConfig } from './app/app.config';
import { AppComponent } from './app/app.component';

// Deutsche Zahlenformate (1.234,56) - Englisch bringt Angular von Haus aus mit.
registerLocaleData(localeDe, 'de-DE');

bootstrapApplication(AppComponent, appConfig)
  .catch((err) => console.error(err));
