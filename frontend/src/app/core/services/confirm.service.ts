import { Injectable, signal } from '@angular/core';

export interface ConfirmState {
  isOpen: boolean;
  title: string;
  message: string;
  confirmText: string;
  cancelText: string;
  resolve?: (value: boolean) => void;
}

@Injectable({
  providedIn: 'root'
})
export class ConfirmService {
  state = signal<ConfirmState>({
    isOpen: false,
    title: '',
    message: '',
    confirmText: 'Ja',
    cancelText: 'Abbrechen'
  });

  // Diese Methode liefert ein Promise zurück (true = Ja, false = Abbrechen)
  ask(title: string, message: string, confirmText = 'Ja', cancelText = 'Abbrechen'): Promise<boolean> {
    return new Promise((resolve) => {
      this.state.set({
        isOpen: true,
        title,
        message,
        confirmText,
        cancelText,
        resolve
      });
    });
  }

  // Wird aufgerufen, wenn der User auf einen der Buttons klickt
  respond(result: boolean) {
    const current = this.state();
    if (current.resolve) {
      current.resolve(result); // Gibt das Ergebnis an das wartende await zurück
    }
    // Modal schließen und Resolve-Funktion leeren
    this.state.set({ ...current, isOpen: false, resolve: undefined });
  }
}
