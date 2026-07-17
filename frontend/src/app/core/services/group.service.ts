import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {environment} from '../../../environments/environment';

export interface CorpTitleDto {
  titleId: number;
  name: string;
  mappedRole: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class GroupService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/groups`;

  getCorporationTitles(): Observable<CorpTitleDto[]> {
    return this.http.get<CorpTitleDto[]>(`${this.apiUrl}/titles`);
  }
}
