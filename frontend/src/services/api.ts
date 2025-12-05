import type {
  CreateSessionRequest,
  SessionResponse,
  SessionInfoResponse,
  UpdateConfigRequest,
  UpdateConfigResponse,
  AirportsResponse,
  StatesResponse,
  TournamentsResponse,
  QuotesResponse,
  QuotesQueryParams,
  SearchFlightsRequest,
  SearchFlightsResponse,
  SearchProgressEvent,
  BucketsResponse,
  ErrorResponse,
  ApiKeyUsageResponse,
} from '@/types/api';

const API_BASE_URL = '';

class ApiError extends Error {
  constructor(
    public status: number,
    public errorResponse: ErrorResponse
  ) {
    super(errorResponse.error);
    this.name = 'ApiError';
  }
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const errorData: ErrorResponse = await response.json().catch(() => ({
      error: `HTTP ${response.status}: ${response.statusText}`,
    }));
    throw new ApiError(response.status, errorData);
  }
  return response.json();
}

function buildHeaders(sessionId?: string): HeadersInit {
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
  };
  if (sessionId) {
    headers['X-Session-Id'] = sessionId;
  }
  return headers;
}

export const api = {
  async createSession(request: CreateSessionRequest): Promise<SessionResponse> {
    const response = await fetch(`${API_BASE_URL}/api/session`, {
      method: 'POST',
      headers: buildHeaders(),
      body: JSON.stringify(request),
    });
    return handleResponse<SessionResponse>(response);
  },

  async deleteSession(sessionId: string): Promise<void> {
    const response = await fetch(`${API_BASE_URL}/api/session`, {
      method: 'DELETE',
      headers: buildHeaders(sessionId),
    });
    if (!response.ok) {
      const errorData: ErrorResponse = await response.json().catch(() => ({
        error: `HTTP ${response.status}: ${response.statusText}`,
      }));
      throw new ApiError(response.status, errorData);
    }
  },

  async getSession(sessionId: string): Promise<SessionInfoResponse> {
    const response = await fetch(`${API_BASE_URL}/api/session`, {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<SessionInfoResponse>(response);
  },

  async updateConfig(sessionId: string, config: UpdateConfigRequest): Promise<UpdateConfigResponse> {
    const response = await fetch(`${API_BASE_URL}/api/session/config`, {
      method: 'PATCH',
      headers: buildHeaders(sessionId),
      body: JSON.stringify(config),
    });
    return handleResponse<UpdateConfigResponse>(response);
  },

  async getAirports(sessionId: string): Promise<AirportsResponse> {
    const response = await fetch(`${API_BASE_URL}/api/airports`, {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<AirportsResponse>(response);
  },

  async getStates(sessionId: string): Promise<StatesResponse> {
    const response = await fetch(`${API_BASE_URL}/api/states`, {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<StatesResponse>(response);
  },

  async getTournaments(sessionId: string): Promise<TournamentsResponse> {
    const response = await fetch(`${API_BASE_URL}/api/tournaments`, {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<TournamentsResponse>(response);
  },

  async searchFlights(
    sessionId: string,
    request: SearchFlightsRequest
  ): Promise<SearchFlightsResponse> {
    const response = await fetch(`${API_BASE_URL}/api/flights/search`, {
      method: 'POST',
      headers: buildHeaders(sessionId),
      body: JSON.stringify(request),
    });
    return handleResponse<SearchFlightsResponse>(response);
  },

  searchFlightsStream(
    sessionId: string,
    request: SearchFlightsRequest,
    callbacks: {
      onProgress: (event: SearchProgressEvent) => void;
      onComplete: (response: SearchFlightsResponse) => void;
      onError: (error: string) => void;
    }
  ): AbortController {
    const abortController = new AbortController();

    fetch(`${API_BASE_URL}/api/flights/search/stream`, {
      method: 'POST',
      headers: buildHeaders(sessionId),
      body: JSON.stringify(request),
      signal: abortController.signal,
    })
      .then(async (response) => {
        if (!response.ok) {
          const errorData: ErrorResponse = await response.json().catch(() => ({
            error: `HTTP ${response.status}: ${response.statusText}`,
          }));
          callbacks.onError(errorData.error);
          return;
        }

        const reader = response.body?.getReader();
        if (!reader) {
          callbacks.onError('No response body');
          return;
        }

        const decoder = new TextDecoder();
        let buffer = '';
        let currentEventType = '';

        const processLine = (line: string) => {
          if (line.startsWith('event: ')) {
            currentEventType = line.slice(7).trim();
          } else if (line.startsWith('data: ')) {
            const data = line.slice(6);
            try {
              const parsed = JSON.parse(data);
              if (currentEventType === 'progress') {
                callbacks.onProgress(parsed as SearchProgressEvent);
              } else if (currentEventType === 'complete') {
                callbacks.onComplete(parsed as SearchFlightsResponse);
              } else if (currentEventType === 'error') {
                callbacks.onError(parsed.error || 'Unknown error');
              }
            } catch {
              // Ignore parse errors for incomplete data
            }
          } else if (line === '') {
            // Empty line marks end of event, reset for next event
            currentEventType = '';
          }
        };

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            // Process any remaining buffer
            if (buffer.trim()) {
              const lines = buffer.split('\n');
              for (const line of lines) {
                processLine(line);
              }
            }
            break;
          }

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            processLine(line);
          }
        }
      })
      .catch((err) => {
        if (err.name !== 'AbortError') {
          callbacks.onError(err.message || 'Stream failed');
        }
      });

    return abortController;
  },

  async getQuotes(
    sessionId: string,
    params?: QuotesQueryParams
  ): Promise<QuotesResponse> {
    const url = new URL(`${API_BASE_URL}/api/flights/quotes`, window.location.origin);
    
    if (params) {
      if (params.airport) url.searchParams.set('airport', params.airport);
      if (params.state) url.searchParams.set('state', params.state);
      if (params.maxPrice !== undefined) url.searchParams.set('maxPrice', params.maxPrice.toString());
      if (params.search) url.searchParams.set('search', params.search);
      if (params.friendsOnly !== undefined) url.searchParams.set('friendsOnly', params.friendsOnly.toString());
      if (params.limit !== undefined) url.searchParams.set('limit', params.limit.toString());
    }

    const response = await fetch(url.toString(), {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<QuotesResponse>(response);
  },

  async getBuckets(sessionId: string): Promise<BucketsResponse> {
    const response = await fetch(`${API_BASE_URL}/api/buckets`, {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<BucketsResponse>(response);
  },

  async getApiKeyUsage(sessionId: string): Promise<ApiKeyUsageResponse> {
    const response = await fetch(`${API_BASE_URL}/api/keys/usage`, {
      method: 'GET',
      headers: buildHeaders(sessionId),
    });
    return handleResponse<ApiKeyUsageResponse>(response);
  },
};

export { ApiError };
