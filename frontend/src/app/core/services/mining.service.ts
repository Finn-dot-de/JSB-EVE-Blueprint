import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface LedgerItemDto {
  typeId: number;
  typeName: string;
  category: string;
  quantity: number;
  volume: number; // NEU
  jitaPrice: number; // NEU
  taxToPay: number;
}

export interface UserLedgerResponse {
  totalDebt: number;
  totalPaid: number;
  currentBalance: number;
  months: MonthlyLedgerDto[];
}

export interface MonthlyLedgerDto {
  month: string;
  totalTax: number;
  taxPaid: number;
  isPaid: boolean;
  details: LedgerItemDto[];
}

export interface MiningTaxRate {
  typeId: number;
  typeName: string;
  category: string;
  taxPercentage: number; // NEU: Prozent statt ISK
  currentJitaBuy: number; // NEU: Jita Preis
}

@Injectable({
  providedIn: 'root'
})
export class MiningService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/mining`;

  getMyLedger(): Observable<UserLedgerResponse> {
    return this.http.get<UserLedgerResponse>(`${this.apiUrl}/my-ledger`);
  }

  getTaxRates(): Observable<MiningTaxRate[]> {
    return this.http.get<MiningTaxRate[]>(`${this.apiUrl}/taxes`);
  }

  saveTaxRate(rate: MiningTaxRate): Observable<MiningTaxRate> {
    return this.http.post<MiningTaxRate>(`${this.apiUrl}/taxes`, rate);
  }

  deleteTaxRate(typeId: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/taxes/${typeId}`);
  }

  // FIX: Sendet jetzt taxPercentage
  saveBulkTax(category: string, taxPercentage: number): Observable<void> {
    return this.http.post<void>(`${this.apiUrl}/taxes/bulk?category=${category}&taxPercentage=${taxPercentage}`, {});
  }

}
