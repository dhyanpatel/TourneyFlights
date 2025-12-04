import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import { api, ApiError } from '@/services/api';
import type {
  SessionResponse,
  SessionInfoResponse,
  SessionConfig,
  UpdateConfigRequest,
  QuotesResponse,
  QuotesQueryParams,
  SearchFlightsRequest,
  SearchFlightsResponse,
  SearchProgressEvent,
  WeekendQuote,
  WeekendBucket,
  ApiKeyUsage,
} from '@/types/api';

export const useSessionStore = defineStore('session', () => {
  // State
  const sessionId = ref<string | null>(null);
  const sessionData = ref<SessionResponse | null>(null);
  const sessionInfo = ref<SessionInfoResponse | null>(null);
  const config = ref<SessionConfig>({});
  const airports = ref<string[]>([]);
  const states = ref<string[]>([]);
  const tournaments = ref<string[]>([]);
  const buckets = ref<WeekendBucket[]>([]);
  const quotes = ref<WeekendQuote[]>([]);
  const quotesCount = ref(0);
  const apiKeyUsage = ref<ApiKeyUsage[]>([]);
  const lastSearchResponse = ref<SearchFlightsResponse | null>(null);
  
  const isLoading = ref(false);
  const isLoadingQuotes = ref(false);
  const isSearching = ref(false);
  const isUpdatingConfig = ref(false);
  const error = ref<string | null>(null);

  // Search progress state
  const searchProgress = ref<SearchProgressEvent | null>(null);
  const searchAbortController = ref<AbortController | null>(null);

  // Getters
  const isAuthenticated = computed(() => sessionId.value !== null);
  const hasSession = computed(() => sessionData.value !== null);
  const totalBuckets = computed(() => sessionInfo.value?.totalBuckets ?? sessionData.value?.totalBuckets ?? 0);
  const totalTournaments = computed(() => sessionInfo.value?.totalTournaments ?? sessionData.value?.totalTournaments ?? 0);
  const quotesLoaded = computed(() => sessionInfo.value?.quotesLoaded ?? 0);
  const totalRemainingSearches = computed(() => 
    apiKeyUsage.value.reduce((sum, key) => sum + (key.totalSearchesLeft ?? 0), 0)
  );

  // Actions
  async function createSession(apiKeys: string[], initialConfig?: SessionConfig): Promise<boolean> {
    isLoading.value = true;
    error.value = null;

    try {
      const response = await api.createSession({ apiKeys, config: initialConfig });
      sessionId.value = response.sessionId;
      sessionData.value = response;
      config.value = response.config;
      
      // Load initial data after session creation
      await loadInitialData();
      
      return true;
    } catch (e) {
      if (e instanceof ApiError) {
        error.value = e.message;
      } else if (e instanceof Error) {
        error.value = e.message;
      } else {
        error.value = 'An unknown error occurred';
      }
      return false;
    } finally {
      isLoading.value = false;
    }
  }

  async function updateConfig(newConfig: UpdateConfigRequest): Promise<boolean> {
    if (!sessionId.value) return false;

    isUpdatingConfig.value = true;
    error.value = null;

    try {
      const response = await api.updateConfig(sessionId.value, newConfig);
      config.value = response.config;
      return true;
    } catch (e) {
      if (e instanceof ApiError) {
        error.value = e.message;
      } else if (e instanceof Error) {
        error.value = e.message;
      } else {
        error.value = 'Failed to update configuration';
      }
      return false;
    } finally {
      isUpdatingConfig.value = false;
    }
  }

  async function searchFlights(request: SearchFlightsRequest): Promise<boolean> {
    if (!sessionId.value) return false;

    isSearching.value = true;
    error.value = null;

    try {
      const response = await api.searchFlights(sessionId.value, request);
      lastSearchResponse.value = response;
      // Refresh session info to get updated quotesLoaded count
      await refreshSessionInfo();
      // Refresh API key usage after search
      await refreshApiKeyUsage();
      return true;
    } catch (e) {
      if (e instanceof ApiError) {
        error.value = e.message;
      } else if (e instanceof Error) {
        error.value = e.message;
      } else {
        error.value = 'Flight search failed';
      }
      return false;
    } finally {
      isSearching.value = false;
    }
  }

  function searchFlightsStream(
    request: SearchFlightsRequest,
    onComplete?: () => void
  ): void {
    if (!sessionId.value) return;

    // Cancel any existing search
    if (searchAbortController.value) {
      searchAbortController.value.abort();
    }

    isSearching.value = true;
    searchProgress.value = null;
    error.value = null;

    searchAbortController.value = api.searchFlightsStream(
      sessionId.value,
      request,
      {
        onProgress: (event: SearchProgressEvent) => {
          searchProgress.value = event;
        },
        onComplete: async (response: SearchFlightsResponse) => {
          lastSearchResponse.value = response;
          isSearching.value = false;
          searchProgress.value = null;
          // Refresh session info to get updated quotesLoaded count
          await refreshSessionInfo();
          // Refresh API key usage after search
          await refreshApiKeyUsage();
          if (onComplete) onComplete();
        },
        onError: (errorMsg: string) => {
          error.value = errorMsg;
          isSearching.value = false;
          searchProgress.value = null;
        },
      }
    );
  }

  function cancelSearch(): void {
    if (searchAbortController.value) {
      searchAbortController.value.abort();
      searchAbortController.value = null;
      isSearching.value = false;
      searchProgress.value = null;
    }
  }

  async function refreshSessionInfo(): Promise<void> {
    if (!sessionId.value) return;

    try {
      sessionInfo.value = await api.getSession(sessionId.value);
      config.value = sessionInfo.value.config;
    } catch {
      // Silently fail - not critical
    }
  }

  async function refreshApiKeyUsage(): Promise<void> {
    if (!sessionId.value) return;

    try {
      const response = await api.getApiKeyUsage(sessionId.value);
      apiKeyUsage.value = response.keys;
    } catch {
      // Silently fail - usage display is not critical
    }
  }

  async function loadInitialData(): Promise<void> {
    if (!sessionId.value) return;

    try {
      const [sessionInfoRes, airportsRes, statesRes, tournamentsRes, bucketsRes, usageRes] = await Promise.all([
        api.getSession(sessionId.value),
        api.getAirports(sessionId.value),
        api.getStates(sessionId.value),
        api.getTournaments(sessionId.value),
        api.getBuckets(sessionId.value),
        api.getApiKeyUsage(sessionId.value),
      ]);

      sessionInfo.value = sessionInfoRes;
      config.value = sessionInfoRes.config;
      airports.value = airportsRes.airports;
      states.value = statesRes.states;
      tournaments.value = tournamentsRes.tournaments;
      buckets.value = bucketsRes.buckets;
      apiKeyUsage.value = usageRes.keys;
    } catch (e) {
      if (e instanceof ApiError) {
        error.value = e.message;
      } else if (e instanceof Error) {
        error.value = e.message;
      }
    }
  }

  async function fetchQuotes(params?: QuotesQueryParams): Promise<void> {
    if (!sessionId.value) return;

    isLoadingQuotes.value = true;
    error.value = null;

    try {
      const response: QuotesResponse = await api.getQuotes(sessionId.value, params);
      quotes.value = response.quotes;
      quotesCount.value = response.count;
      // Refresh API key usage after each request
      await refreshApiKeyUsage();
    } catch (e) {
      if (e instanceof ApiError) {
        error.value = e.message;
      } else if (e instanceof Error) {
        error.value = e.message;
      } else {
        error.value = 'Failed to fetch quotes';
      }
    } finally {
      isLoadingQuotes.value = false;
    }
  }

  async function endSession(): Promise<void> {
    if (!sessionId.value) return;

    try {
      await api.deleteSession(sessionId.value);
    } catch {
      // Ignore errors when ending session
    } finally {
      // Reset all state
      sessionId.value = null;
      sessionData.value = null;
      sessionInfo.value = null;
      config.value = {};
      airports.value = [];
      states.value = [];
      tournaments.value = [];
      buckets.value = [];
      quotes.value = [];
      quotesCount.value = 0;
      apiKeyUsage.value = [];
      lastSearchResponse.value = null;
      error.value = null;
    }
  }

  function clearError(): void {
    error.value = null;
  }

  return {
    // State
    sessionId,
    sessionData,
    sessionInfo,
    config,
    airports,
    states,
    tournaments,
    buckets,
    quotes,
    quotesCount,
    apiKeyUsage,
    lastSearchResponse,
    searchProgress,
    isLoading,
    isLoadingQuotes,
    isSearching,
    isUpdatingConfig,
    error,
    // Getters
    isAuthenticated,
    hasSession,
    totalBuckets,
    totalTournaments,
    quotesLoaded,
    totalRemainingSearches,
    // Actions
    createSession,
    updateConfig,
    searchFlights,
    searchFlightsStream,
    cancelSearch,
    refreshSessionInfo,
    loadInitialData,
    fetchQuotes,
    endSession,
    clearError,
    refreshApiKeyUsage,
  };
});
