import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  message: string;
  type: 'success' | 'error' | 'info';
}

@Injectable({
  providedIn: 'root'
})
export class ToastService {
  toasts = signal<Toast[]>([]);
  private counter = 0;

  show(message: string, type: 'success' | 'error' | 'info' = 'info') {
    const id = this.counter++;
    // Füge den neuen Toast zum Signal-Array hinzu
    this.toasts.update(currentToasts => [...currentToasts, { id, message, type }]);

    // Nach 4 Sekunden automatisch ausblenden
    setTimeout(() => this.remove(id), 4000);
  }

  success(msg: string) { this.show(msg, 'success'); }
  error(msg: string) { this.show(msg, 'error'); }
  info(msg: string) { this.show(msg, 'info'); }

  remove(id: number) {
    this.toasts.update(currentToasts => currentToasts.filter(t => t.id !== id));
  }
}
