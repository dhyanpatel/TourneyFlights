// API Types derived from OpenAPI spec v2.0.0

// Session Configuration
export interface SessionConfig {
  originAirport?: string;
  friendAirports?: string[];
  filterMonths?: number;
  tripDurationDays?: number;
}

export interface CreateSessionRequest {
  apiKeys: string[];
  config?: SessionConfig;
}

export interface SessionResponse {
  sessionId: string;
  config: SessionConfig;
  totalTournaments: number;
  totalBuckets: number;
  message?: string;
}

export interface SessionInfoResponse {
  sessionId: string;
  config: SessionConfig;
  createdAt: string;
  expiresAt: string;
  totalTournaments: number;
  totalBuckets: number;
  quotesLoaded: number;
  apiKeyCount: number;
}

export interface UpdateConfigRequest {
  originAirport?: string;
  friendAirports?: string[];
  filterMonths?: number;
  tripDurationDays?: number;
}

export interface UpdateConfigResponse {
  message: string;
  config: SessionConfig;
}

export interface AirportsResponse {
  airports: string[];
}

export interface StatesResponse {
  states: string[];
}

export interface TournamentsResponse {
  tournaments: string[];
}

export interface QuotesResponse {
  count: number;
  quotes: WeekendQuote[];
}

export interface WeekendQuote {
  bucket: WeekendBucket;
  quotes: FlightQuote[];
  isFriendAirport: boolean;
  cacheInfo?: CacheInfo;
}

// Health
export interface HealthResponse {
  status: string;
  activeSessions: number;
}

export interface WeekendBucket {
  key: WeekendKey;
  tournaments: Tournament[];
}

export interface WeekendKey {
  airport: string;
  weekendStart: string;
}

export interface Tournament {
  name: string;
  city: string;
  stateOrRegion: string;
  startDate: string;
  endDate: string;
  rawDateText: string;
}

export interface FlightQuote {
  origin: string;
  destination: string;
  departureDate: string;
  returnDate: string;
  priceUsd: number;
  outboundDepartureTime: string;
  outboundArrivalTime: string;
  airline: string;
  googleFlightsUrl?: string;
}

export interface ErrorResponse {
  error: string;
}

export interface QuotesQueryParams {
  airport?: string;
  state?: string;
  maxPrice?: number;
  search?: string;
  friendsOnly?: boolean;
  limit?: number;
}

// Flight Search
export interface SearchFlightsRequest {
  originAirport?: string;
  destinationAirport?: string;
  departureDate?: string;
  returnDate?: string;
  maxResults?: number;
  skipCache?: boolean;
}

export interface SearchFlightsResponse {
  results: FlightSearchResult[];
  totalQuotes: number;
}

// SSE Progress Event
export interface SearchProgressEvent {
  current: number;
  total: number;
  destination: string;
  departureDate: string;
  fromCache: boolean;
  priceUsd: number | null;
}

export interface FlightSearchResult {
  origin: string;
  destination: string;
  departureDate: string;
  returnDate: string;
  quotes: FlightQuote[];
  cacheInfo: CacheInfo;
}

// Cache Info
export interface CacheInfo {
  fromCache: boolean;
  cacheAgeSeconds: number | null;
  cachedAt: string | null;
}

// Buckets
export interface BucketsResponse {
  count: number;
  buckets: WeekendBucket[];
}

export interface ApiKeyUsageResponse {
  keys: ApiKeyUsage[];
}

export interface ApiKeyUsage {
  maskedKey: string;
  accountEmail: string | null;
  planName: string | null;
  searchesPerMonth: number | null;
  thisMonthUsage: number | null;
  planSearchesLeft: number | null;
  extraCredits: number | null;
  totalSearchesLeft: number | null;
  error: string | null;
}
