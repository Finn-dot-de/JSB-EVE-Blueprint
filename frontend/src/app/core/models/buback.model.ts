export interface AssetResult {
  quantity: number;
  name: string;
  volume: number;
  demand: number;
  price: number;
  isBlacklisted: boolean;
}

export interface BuybackResponse {
  results: AssetResult[];
  totalPrice: number;
  botReaction: string;
}
