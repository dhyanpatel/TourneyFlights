<script setup lang="ts">
import { ref, onMounted, computed, watch, h } from 'vue';
import {
  NLayout,
  NLayoutHeader,
  NLayoutContent,
  NCard,
  NButton,
  NInput,
  NSelect,
  NInputNumber,
  NSwitch,
  NSpace,
  NGrid,
  NGi,
  NAlert,
  NEmpty,
  NSpin,
  NIcon,
  NText,
  NDivider,
  NTag,
  NCollapse,
  NCollapseItem,
  NDataTable,
  NPopover,
  NProgress,
  NTooltip,
  NDatePicker,
} from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import {
  SearchOutline,
  AirplaneOutline,
  LogOutOutline,
  FilterOutline,
  PlayOutline,
  TimeOutline,
  InformationCircleOutline,
  StopOutline,
  CheckmarkCircleOutline,
  OpenOutline,
} from '@vicons/ionicons5';
import { useSessionStore } from '@/stores/session';
import { useThemeStore } from '@/stores/theme';
import { MoonOutline, SunnyOutline } from '@vicons/ionicons5';
import type { QuotesQueryParams, SearchFlightsRequest, SessionConfig, FlightQuote, WeekendBucket, CacheInfo } from '@/types/api';

// Flattened row type for displaying individual quotes
interface FlatQuoteRow {
  bucket: WeekendBucket;
  quote: FlightQuote;
  isFriendAirport: boolean;
  cacheInfo?: CacheInfo;
}

const emit = defineEmits<{
  (e: 'logout'): void;
}>();

const sessionStore = useSessionStore();
const themeStore = useThemeStore();

// Filter state
const filters = ref<QuotesQueryParams>({
  airport: undefined,
  state: undefined,
  maxPrice: undefined,
  search: '',
  friendsOnly: false,
  limit: undefined,
});

// Config editing state (always editable, no toggle)
const editingConfig = ref<SessionConfig>({});

// Mobile sorting and pagination
const mobileSortBy = ref<'price' | 'date' | 'airport'>('price');
const mobileSortOrder = ref<'asc' | 'desc'>('asc');
const mobilePageSize = 20;
const mobilePage = ref(1);

// Flight search state (inline in card, no modal)
const searchRequest = ref<SearchFlightsRequest>({
  skipCache: false,
  maxResults: undefined,
  destinationAirport: undefined,
  departureDate: undefined,
});

// Debounce timer for search
let searchTimeout: ReturnType<typeof setTimeout> | null = null;

// Computed options for selects
const airportOptions = computed(() =>
  sessionStore.airports.map((a) => ({ label: a, value: a }))
);

const stateOptions = computed(() =>
  sessionStore.states.map((s) => ({ label: s, value: s }))
);

// Total individual quotes from API (before frontend filtering)
const totalQuotesFromApi = computed(() => {
  let total = 0;
  for (const wq of sessionStore.quotes) {
    total += wq.quotes.length;
  }
  return total;
});

// Flatten quotes for table display - each row is one quote
// Compute isFriendAirport dynamically based on editingConfig (the live editable state)
const flattenedQuotes = computed<FlatQuoteRow[]>(() => {
  const friendAirports = new Set(editingConfig.value.friendAirports || []);
  const rows: FlatQuoteRow[] = [];
  for (const wq of sessionStore.quotes) {
    // Compute isFriendAirport based on current config
    const isFriend = friendAirports.has(wq.bucket.key.airport);
    
    // Apply friendsOnly filter on frontend
    if (filters.value.friendsOnly && !isFriend) {
      continue;
    }
    
    for (const quote of wq.quotes) {
      rows.push({
        bucket: wq.bucket,
        quote,
        isFriendAirport: isFriend,
        cacheInfo: wq.cacheInfo,
      });
    }
  }
  return rows;
});

// Sorted and paginated quotes for mobile view
const sortedMobileQuotes = computed(() => {
  const sorted = [...flattenedQuotes.value].sort((a, b) => {
    let comparison = 0;
    switch (mobileSortBy.value) {
      case 'price':
        comparison = a.quote.priceUsd - b.quote.priceUsd;
        break;
      case 'date':
        comparison = new Date(a.bucket.key.weekendStart).getTime() - new Date(b.bucket.key.weekendStart).getTime();
        break;
      case 'airport':
        comparison = a.bucket.key.airport.localeCompare(b.bucket.key.airport);
        break;
    }
    return mobileSortOrder.value === 'asc' ? comparison : -comparison;
  });
  return sorted;
});

const paginatedMobileQuotes = computed(() => {
  const start = (mobilePage.value - 1) * mobilePageSize;
  return sortedMobileQuotes.value.slice(start, start + mobilePageSize);
});

const mobileTotalPages = computed(() => Math.ceil(flattenedQuotes.value.length / mobilePageSize));

// Table columns
const columns: DataTableColumns<FlatQuoteRow> = [
  {
    title: 'Weekend',
    key: 'weekend',
    width: 120,
    render(row) {
      const date = new Date(row.bucket.key.weekendStart);
      return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      });
    },
  },
  {
    title: 'Airport',
    key: 'airport',
    width: 100,
    render(row) {
      return row.bucket.key.airport;
    },
  },
  {
    title: 'Tournaments',
    key: 'tournaments',
    ellipsis: {
      tooltip: true,
    },
    render(row) {
      return row.bucket.tournaments.map((t) => t.name).join(', ');
    },
  },
  {
    title: 'Location',
    key: 'location',
    width: 150,
    render(row) {
      const t = row.bucket.tournaments[0];
      return t ? `${t.city}, ${t.stateOrRegion}` : '';
    },
  },
  {
    title: 'Price',
    key: 'price',
    width: 100,
    sorter: (a, b) => a.quote.priceUsd - b.quote.priceUsd,
    render(row) {
      return `$${row.quote.priceUsd.toFixed(0)}`;
    },
  },
  {
    title: 'Airline',
    key: 'airline',
    width: 140,
    render(row) {
      return row.quote.airline;
    },
  },
  {
    title: 'Departure',
    key: 'departure',
    width: 100,
    render(row) {
      return row.quote.outboundDepartureTime;
    },
  },
  {
    title: 'Friend',
    key: 'friend',
    width: 80,
    render(row) {
      return row.isFriendAirport ? '✓' : '';
    },
  },
  {
    title: 'Cache',
    key: 'cache',
    width: 80,
    render(row) {
      if (!row.cacheInfo) return '-';
      return row.cacheInfo.fromCache ? formatCacheAge(row.cacheInfo.cacheAgeSeconds) : 'Fresh';
    },
  },
  {
    title: '',
    key: 'link',
    width: 50,
    render(row) {
      if (!row.quote.googleFlightsUrl) return '';
      return h(
        'a',
        {
          href: row.quote.googleFlightsUrl,
          target: '_blank',
          rel: 'noopener noreferrer',
          style: 'color: #18a058; display: flex; align-items: center;',
        },
        h(NIcon, { size: 18 }, () => h(OpenOutline))
      );
    },
  },
];

// Fetch quotes with current filters
// Note: friendsOnly is handled on frontend to work with dynamically updated friend airports
async function fetchQuotes(): Promise<void> {
  const params: QuotesQueryParams = {};
  
  if (filters.value.airport) params.airport = filters.value.airport;
  if (filters.value.state) params.state = filters.value.state;
  if (filters.value.maxPrice) params.maxPrice = filters.value.maxPrice;
  if (filters.value.search) params.search = filters.value.search;
  if (filters.value.limit) params.limit = filters.value.limit;
  // friendsOnly is filtered on frontend in flattenedQuotes computed

  await sessionStore.fetchQuotes(params);
}

// Watch search input with debounce
watch(
  () => filters.value.search,
  () => {
    if (searchTimeout) clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      fetchQuotes();
    }, 300);
  }
);

// Watch other filters for immediate fetch (friendsOnly handled on frontend, no API call needed)
watch(
  () => [
    filters.value.airport,
    filters.value.state,
    filters.value.maxPrice,
    filters.value.limit,
  ],
  () => {
    fetchQuotes();
  }
);

function clearFilters(): void {
  filters.value.airport = null as unknown as undefined;
  filters.value.state = null as unknown as undefined;
  filters.value.maxPrice = null as unknown as undefined;
  filters.value.search = '';
  filters.value.friendsOnly = false;
  filters.value.limit = null as unknown as undefined;
}

// Format date for display
function formatDate(dateStr: string | undefined): string {
  if (!dateStr) return 'N/A';
  return new Date(dateStr).toLocaleString();
}

// Get return day name based on trip duration (Friday + N days)
function getReturnDayName(tripDays: number): string {
  const days = ['Friday', 'Saturday', 'Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday'];
  return days[tripDays % 7] || 'Unknown';
}

// Computed return day for editing
const editingReturnDayName = computed(() => {
  const days = editingConfig.value.tripDurationDays ?? 2;
  return getReturnDayName(days);
});

// Flight search
function runFlightSearch(): void {
  sessionStore.searchFlightsStream(searchRequest.value, async () => {
    // Refresh quotes after search completes
    await fetchQuotes();
  });
}

function cancelSearch(): void {
  sessionStore.cancelSearch();
}

// Computed for progress percentage
const searchProgressPercent = computed(() => {
  if (!sessionStore.searchProgress) return 0;
  return Math.round((sessionStore.searchProgress.current / sessionStore.searchProgress.total) * 100);
});

// Format cache age for display
function formatCacheAge(seconds: number | null): string {
  if (seconds === null) return 'N/A';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

async function handleLogout(): Promise<void> {
  await sessionStore.endSession();
  emit('logout');
}

onMounted(() => {
  // Initialize editingConfig with current session config
  editingConfig.value = { ...sessionStore.config };
  fetchQuotes();
});
</script>

<template>
  <NLayout :class="['flight-search-layout', { 'dark-mode': themeStore.isDark }]">
    <NLayoutHeader class="header" bordered>
      <div class="header-content">
        <div class="header-left">
          <NIcon size="28" color="#18a058">
            <AirplaneOutline />
          </NIcon>
          <span class="app-title">TourneyFlights</span>
        </div>
        <div class="header-right">
          <!-- Dark Mode Toggle -->
          <NButton quaternary circle @click="themeStore.toggleTheme">
            <template #icon>
              <NIcon>
                <MoonOutline v-if="!themeStore.isDark" />
                <SunnyOutline v-else />
              </NIcon>
            </template>
          </NButton>

          <!-- Remaining Searches Counter with API Key Usage Popover -->
          <NPopover trigger="hover" placement="bottom-end">
            <template #trigger>
              <NTag 
                :type="sessionStore.totalRemainingSearches <= 100 ? 'warning' : 'success'" 
                size="medium"
                round
                style="cursor: pointer;"
              >
                {{ sessionStore.totalRemainingSearches }} searches left
              </NTag>
            </template>
            <div class="api-usage-popover">
              <div class="api-usage-title">API Key Usage</div>
              <div
                v-for="(key, index) in sessionStore.apiKeyUsage"
                :key="index"
                class="api-key-item"
              >
                <div class="api-key-header">
                  <code class="masked-key">{{ key.maskedKey }}</code>
                  <NTag v-if="key.planName" size="tiny" type="info">
                    {{ key.planName }}
                  </NTag>
                </div>
                <div v-if="key.error" class="api-key-error">
                  <NText type="error" depth="1">{{ key.error }}</NText>
                </div>
                <template v-else>
                  <div class="api-key-stats">
                    <NSpace justify="space-between" align="center">
                      <NText depth="3">Used this month:</NText>
                      <NText strong>{{ key.thisMonthUsage ?? 0 }} / {{ key.searchesPerMonth ?? '?' }}</NText>
                    </NSpace>
                    <NProgress
                      type="line"
                      :percentage="key.searchesPerMonth ? Math.round(((key.thisMonthUsage ?? 0) / key.searchesPerMonth) * 100) : 0"
                      :status="(key.searchesPerMonth && (key.thisMonthUsage ?? 0) >= key.searchesPerMonth) ? 'error' : 'success'"
                      :show-indicator="false"
                      style="margin-top: 4px"
                    />
                  </div>
                  <NSpace justify="space-between" style="margin-top: 8px">
                    <NText depth="3">Remaining:</NText>
                    <NText
                      :type="(key.totalSearchesLeft ?? 0) <= 10 ? 'warning' : 'success'"
                      strong
                    >
                      {{ key.totalSearchesLeft ?? 0 }}
                    </NText>
                  </NSpace>
                </template>
              </div>
              <div v-if="sessionStore.apiKeyUsage.length === 0" class="no-keys">
                <NText depth="3">No API key data available</NText>
              </div>
            </div>
          </NPopover>

          <!-- Session Info Popover -->
          <NPopover trigger="hover" placement="bottom-end">
            <template #trigger>
              <NButton quaternary>
                <template #icon>
                  <NIcon>
                    <InformationCircleOutline />
                  </NIcon>
                </template>
                Session
              </NButton>
            </template>
            <div class="session-info-popover">
              <div class="session-info-title">Session Info</div>
              <div class="session-info-content">
                <NSpace vertical size="small">
                  <NSpace justify="space-between">
                    <NText depth="3">Session ID:</NText>
                    <code class="session-id">{{ sessionStore.sessionId?.slice(0, 8) }}...</code>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">Created:</NText>
                    <NText>{{ formatDate(sessionStore.sessionInfo?.createdAt) }}</NText>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">Expires:</NText>
                    <NText>{{ formatDate(sessionStore.sessionInfo?.expiresAt) }}</NText>
                  </NSpace>
                  <NDivider style="margin: 8px 0" />
                  <NSpace justify="space-between">
                    <NText depth="3">Origin Airport:</NText>
                    <NTag size="small" type="info">{{ sessionStore.config.originAirport || 'Not set' }}</NTag>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">Filter Months:</NText>
                    <NText>{{ sessionStore.config.filterMonths ?? 'N/A' }}</NText>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">Trip Duration:</NText>
                    <NText>{{ sessionStore.config.tripDurationDays ?? 'N/A' }} days</NText>
                  </NSpace>
                  <NDivider style="margin: 8px 0" />
                  <NSpace justify="space-between">
                    <NText depth="3">Friend Airports:</NText>
                    <NSpace size="small">
                      <NTag
                        v-for="airport in sessionStore.config.friendAirports"
                        :key="airport"
                        size="tiny"
                        type="success"
                      >
                        {{ airport }}
                      </NTag>
                      <NText v-if="!sessionStore.config.friendAirports?.length" depth="3">None</NText>
                    </NSpace>
                  </NSpace>
                  <NDivider style="margin: 8px 0" />
                  <NSpace justify="space-between">
                    <NText depth="3">Tournaments:</NText>
                    <NText strong>{{ sessionStore.totalTournaments }}</NText>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">Weekend Buckets:</NText>
                    <NText strong>{{ sessionStore.totalBuckets }}</NText>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">Quotes Loaded:</NText>
                    <NText strong>{{ sessionStore.quotesLoaded }}</NText>
                  </NSpace>
                  <NSpace justify="space-between">
                    <NText depth="3">API Keys:</NText>
                    <NText strong>{{ sessionStore.sessionInfo?.apiKeyCount ?? 0 }}</NText>
                  </NSpace>
                </NSpace>
              </div>
            </div>
          </NPopover>

          <NButton quaternary type="error" @click="handleLogout">
            <template #icon>
              <NIcon>
                <LogOutOutline />
              </NIcon>
            </template>
            End Session
          </NButton>
        </div>
      </div>
    </NLayoutHeader>

    <NLayoutContent class="content">
      <!-- Flight Search Card -->
      <NCard class="search-card" size="small">
        <NSpace vertical size="medium">
          <NSpace justify="space-between" align="center">
            <NSpace align="center">
              <NIcon size="20" color="#18a058">
                <PlayOutline />
              </NIcon>
              <NText strong>Flight Search</NText>
            </NSpace>
            <NSpace>
              <NButton
                v-if="!sessionStore.isSearching"
                type="primary"
                @click="runFlightSearch"
              >
                <template #icon>
                  <NIcon>
                    <SearchOutline />
                  </NIcon>
                </template>
                Search Flights
              </NButton>
              <NButton
                v-if="sessionStore.isSearching"
                type="error"
                @click="cancelSearch"
              >
                <template #icon>
                  <NIcon>
                    <StopOutline />
                  </NIcon>
                </template>
                Cancel
              </NButton>
            </NSpace>
          </NSpace>

          <!-- Search Settings -->
          <div class="search-settings">
            <NGrid :cols="24" :x-gap="12" :y-gap="12">
              <!-- Config fields -->
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Origin Airport</NText>
                  <NInput v-model:value="editingConfig.originAirport" placeholder="e.g., ORD" size="small" />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Months Ahead</NText>
                  <NInputNumber v-model:value="editingConfig.filterMonths" :min="1" :max="12" size="small" style="width: 100%;" />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Trip Days (Fri → {{ editingReturnDayName }})</NText>
                  <NInputNumber v-model:value="editingConfig.tripDurationDays" :min="1" :max="14" size="small" style="width: 100%;" />
                </NSpace>
              </NGi>
              <!-- Search request fields -->
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Destination (optional)</NText>
                  <NSelect
                    v-model:value="searchRequest.destinationAirport"
                    :options="airportOptions"
                    placeholder="All"
                    clearable
                    size="small"
                    :consistent-menu-width="false"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Departure Date (optional)</NText>
                  <NDatePicker
                    v-model:formatted-value="searchRequest.departureDate"
                    type="date"
                    value-format="yyyy-MM-dd"
                    clearable
                    placeholder="Any"
                    size="small"
                    style="width: 100%"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Max Results</NText>
                  <NInputNumber
                    v-model:value="searchRequest.maxResults"
                    :min="1"
                    placeholder="No limit"
                    clearable
                    size="small"
                    style="width: 100%;"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Skip Cache</NText>
                  <NSpace align="center">
                    <NSwitch v-model:value="searchRequest.skipCache" size="small" />
                    <NTooltip>
                      <template #trigger>
                        <NIcon size="14" depth="3">
                          <TimeOutline />
                        </NIcon>
                      </template>
                      Force fresh data from API (ignore 24h cache)
                    </NTooltip>
                  </NSpace>
                </NSpace>
              </NGi>
            </NGrid>
          </div>

          <!-- Progress Display -->
          <div v-if="sessionStore.isSearching" class="search-progress">
            <NSpace justify="space-between" align="center" style="margin-bottom: 8px;">
              <NSpace align="center" size="small">
                <NSpin size="small" />
                <NText v-if="sessionStore.searchProgress">
                  Searching {{ sessionStore.searchProgress.current }} of {{ sessionStore.searchProgress.total }}...
                </NText>
                <NText v-else>
                  Starting search...
                </NText>
              </NSpace>
              <NText v-if="sessionStore.searchProgress" strong>
                {{ searchProgressPercent }}%
              </NText>
            </NSpace>
            <NProgress
              type="line"
              :percentage="searchProgressPercent"
              :show-indicator="false"
              status="success"
            />
            <div v-if="sessionStore.searchProgress" class="progress-details">
              <NSpace justify="space-between" style="margin-top: 8px;">
                <NSpace size="small" align="center">
                  <NIcon size="16">
                    <AirplaneOutline />
                  </NIcon>
                  <NText depth="2">{{ sessionStore.searchProgress.destination }}</NText>
                  <NText depth="3">{{ sessionStore.searchProgress.departureDate }}</NText>
                </NSpace>
                <NSpace size="small" align="center">
                  <NTag
                    v-if="sessionStore.searchProgress.priceUsd"
                    size="small"
                    type="success"
                  >
                    ${{ sessionStore.searchProgress.priceUsd }}
                  </NTag>
                  <NTag v-else size="small" type="warning">No price</NTag>
                  <NTag
                    size="tiny"
                    :type="sessionStore.searchProgress.fromCache ? 'info' : 'default'"
                  >
                    {{ sessionStore.searchProgress.fromCache ? 'Cached' : 'Fresh' }}
                  </NTag>
                </NSpace>
              </NSpace>
            </div>
          </div>

          <!-- Last Search Results Summary -->
          <div v-if="!sessionStore.isSearching && sessionStore.lastSearchResponse" class="last-search-summary">
            <NSpace align="center" size="small">
              <NIcon size="16" color="#18a058">
                <CheckmarkCircleOutline />
              </NIcon>
              <NText depth="2">
                Last search: {{ sessionStore.lastSearchResponse.results.length }} routes, 
                {{ sessionStore.lastSearchResponse.totalQuotes }} quotes found
              </NText>
            </NSpace>
          </div>
        </NSpace>
      </NCard>

      <!-- Filters -->
      <NCard class="filters-card" size="small">
        <NCollapse default-expanded-names="filters">
          <NCollapseItem title="Filters" name="filters">
            <template #header-extra>
              <NIcon>
                <FilterOutline />
              </NIcon>
            </template>
            <NGrid :cols="24" :x-gap="12" :y-gap="12">
              <NGi span="24 m:12 l:6">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Search Tournaments</NText>
                  <NInput
                    v-model:value="filters.search"
                    placeholder="Search by name..."
                    clearable
                    size="small"
                  >
                    <template #prefix>
                      <NIcon>
                        <SearchOutline />
                      </NIcon>
                    </template>
                  </NInput>
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Airport</NText>
                  <NSelect
                    v-model:value="filters.airport"
                    :options="airportOptions"
                    placeholder="All"
                    clearable
                    filterable
                    size="small"
                    :consistent-menu-width="false"
                    @clear="filters.airport = undefined"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">State</NText>
                  <NSelect
                    v-model:value="filters.state"
                    :options="stateOptions"
                    placeholder="All"
                    clearable
                    filterable
                    size="small"
                    :consistent-menu-width="false"
                    @clear="filters.state = undefined"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Max Price ($)</NText>
                  <NInputNumber
                    v-model:value="filters.maxPrice"
                    :min="0"
                    placeholder="No limit"
                    clearable
                    size="small"
                    style="width: 100%;"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Limit Results</NText>
                  <NInputNumber
                    v-model:value="filters.limit"
                    :min="1"
                    placeholder="No limit"
                    clearable
                    size="small"
                    style="width: 100%;"
                  />
                </NSpace>
              </NGi>
              <NGi span="12 m:6 l:3">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Friends Only</NText>
                  <NSwitch v-model:value="filters.friendsOnly" size="small" />
                </NSpace>
              </NGi>
              <NGi span="24 m:12 l:6">
                <NSpace vertical size="small">
                  <NText depth="3" style="font-size: 11px;">Friend Airports</NText>
                  <NSelect
                    v-model:value="editingConfig.friendAirports"
                    :options="airportOptions"
                    placeholder="Select friend airports..."
                    multiple
                    filterable
                    clearable
                    size="small"
                    :consistent-menu-width="false"
                  />
                </NSpace>
              </NGi>
            </NGrid>
            <NDivider style="margin: 16px 0 8px 0" />
            <NSpace justify="space-between" align="center">
              <NSpace align="center" size="small">
                <NText>Showing</NText>
                <NTag size="small" round type="info">
                  {{ flattenedQuotes.length }} shown<template v-if="totalQuotesFromApi > flattenedQuotes.length">, {{ totalQuotesFromApi - flattenedQuotes.length }} hidden</template>
                </NTag>
              </NSpace>
              <NButton size="small" @click="clearFilters">Clear Filters</NButton>
            </NSpace>
          </NCollapseItem>
        </NCollapse>
      </NCard>

      <!-- Error Alert -->
      <NAlert
        v-if="sessionStore.error"
        type="error"
        closable
        style="margin-bottom: 16px"
        @close="sessionStore.clearError"
      >
        {{ sessionStore.error }}
      </NAlert>

      <!-- Results Table (Desktop) -->
      <NCard class="results-card desktop-table">
        <template #header>
          <NSpace align="center" justify="space-between">
            <NSpace align="center" size="small">
              <span>Flight Quotes</span>
              <NTag v-if="flattenedQuotes.length > 0" size="small" type="info">
                {{ flattenedQuotes.length }} quotes
              </NTag>
            </NSpace>
            <NSpin v-if="sessionStore.isLoadingQuotes" size="small" />
          </NSpace>
        </template>

        <NDataTable
          :columns="columns"
          :data="flattenedQuotes"
          :loading="sessionStore.isLoadingQuotes"
          :pagination="{ pageSize: 50 }"
          :bordered="false"
          striped
          size="small"
          :row-key="(row: FlatQuoteRow) => `${row.bucket.key.airport}-${row.bucket.key.weekendStart}-${row.quote.airline}-${row.quote.outboundDepartureTime}`"
        />

        <NEmpty
          v-if="!sessionStore.isLoadingQuotes && flattenedQuotes.length === 0"
          description="No flight quotes found matching your filters"
          style="padding: 48px 0"
        />
      </NCard>

      <!-- Results Cards (Mobile) -->
      <div class="mobile-cards">
        <NSpace vertical size="small" style="margin-bottom: 12px;">
          <NSpace align="center" justify="space-between">
            <NSpace align="center" size="small">
              <NText strong>Flight Quotes</NText>
              <NTag v-if="flattenedQuotes.length > 0" size="small" type="info">
                {{ flattenedQuotes.length }} quotes
              </NTag>
            </NSpace>
            <NSpin v-if="sessionStore.isLoadingQuotes" size="small" />
          </NSpace>
          <!-- Sort controls -->
          <NSpace v-if="flattenedQuotes.length > 0" align="center" size="small">
            <NText depth="3" style="font-size: 12px;">Sort:</NText>
            <NSelect
              v-model:value="mobileSortBy"
              size="tiny"
              style="width: 90px;"
              :options="[
                { label: 'Price', value: 'price' },
                { label: 'Date', value: 'date' },
                { label: 'Airport', value: 'airport' },
              ]"
              @update:value="mobilePage = 1"
            />
            <NButton 
              size="tiny" 
              quaternary 
              @click="mobileSortOrder = mobileSortOrder === 'asc' ? 'desc' : 'asc'; mobilePage = 1"
            >
              {{ mobileSortOrder === 'asc' ? '↑' : '↓' }}
            </NButton>
          </NSpace>
        </NSpace>

        <NSpin v-if="sessionStore.isLoadingQuotes" style="display: flex; justify-content: center; padding: 48px 0;" />

        <div v-else-if="flattenedQuotes.length > 0" class="quote-cards">
          <div 
            v-for="(row, index) in paginatedMobileQuotes" 
            :key="`${row.bucket.key.airport}-${row.bucket.key.weekendStart}-${row.quote.airline}-${index}`"
            class="quote-card"
          >
            <div class="quote-card-header">
              <div class="quote-price">${{ row.quote.priceUsd.toFixed(0) }}</div>
              <div class="quote-meta">
                <NTag size="tiny" :type="row.isFriendAirport ? 'success' : 'default'">
                  {{ row.bucket.key.airport }}
                </NTag>
                <NTag v-if="row.cacheInfo?.fromCache" size="tiny" type="info">
                  {{ formatCacheAge(row.cacheInfo.cacheAgeSeconds) }}
                </NTag>
              </div>
            </div>
            <div class="quote-card-body">
              <div class="quote-tournament">{{ row.bucket.tournaments.map((t) => t.name).join(', ') }}</div>
              <div class="quote-details">
                <span>{{ row.bucket.tournaments[0]?.city }}, {{ row.bucket.tournaments[0]?.stateOrRegion }}</span>
                <span>•</span>
                <span>{{ new Date(row.bucket.key.weekendStart).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) }}</span>
              </div>
              <div class="quote-flight">
                <span>{{ row.quote.airline }}</span>
                <span>•</span>
                <span>Departs {{ row.quote.outboundDepartureTime }}</span>
                <a 
                  v-if="row.quote.googleFlightsUrl" 
                  :href="row.quote.googleFlightsUrl" 
                  target="_blank" 
                  rel="noopener noreferrer"
                  class="quote-link"
                >
                  <NIcon size="14"><OpenOutline /></NIcon>
                </a>
              </div>
            </div>
          </div>
          
          <!-- Pagination -->
          <div v-if="mobileTotalPages > 1" class="mobile-pagination">
            <NButton 
              size="small" 
              :disabled="mobilePage <= 1"
              @click="mobilePage--"
            >
              Previous
            </NButton>
            <NText depth="2" style="font-size: 13px;">
              {{ mobilePage }} / {{ mobileTotalPages }}
            </NText>
            <NButton 
              size="small" 
              :disabled="mobilePage >= mobileTotalPages"
              @click="mobilePage++"
            >
              Next
            </NButton>
          </div>
        </div>

        <NEmpty
          v-else
          description="No flight quotes found matching your filters"
          style="padding: 48px 0"
        />
      </div>

    </NLayoutContent>
  </NLayout>
</template>

<style scoped>
/* Light mode defaults */
.flight-search-layout {
  --bg-color: #f5f7fa;
  --header-bg: #fff;
  --card-bg: #f9f9f9;
  --border-color: #e8e8e8;
  --text-color: #333;
  
  min-height: 100vh;
  background: var(--bg-color);
  transition: background-color 0.3s, color 0.3s;
}

/* Dark mode overrides */
.flight-search-layout.dark-mode {
  --bg-color: #18181c;
  --header-bg: #1e1e22;
  --card-bg: #2a2a2e;
  --border-color: #3a3a3e;
  --text-color: #fff;
}

:deep(.n-layout) {
  background: var(--bg-color) !important;
}

:deep(.n-layout-header) {
  background: var(--header-bg) !important;
}

:deep(.n-layout-content) {
  background: transparent;
}

:deep(.n-layout-sider) {
  background: var(--header-bg) !important;
}

.header {
  padding: 0 24px;
  height: 64px;
  display: flex;
  align-items: center;
}

.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-color);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.content {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

@media (max-width: 640px) {
  .content {
    padding: 12px;
  }
}

.filters-card {
  margin-bottom: 16px;
}

/* Desktop table, hidden on mobile */
.desktop-table {
  min-height: 400px;
}

/* Mobile cards, hidden on desktop */
.mobile-cards {
  display: none;
}

@media (max-width: 768px) {
  .desktop-table {
    display: none;
  }
  
  .mobile-cards {
    display: block;
  }
}

.quote-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.quote-card {
  background: var(--card-bg);
  border-radius: 8px;
  padding: 12px;
  border: 1px solid var(--border-color);
}

.quote-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.quote-price {
  font-size: 20px;
  font-weight: 700;
  color: #18a058;
}

.quote-meta {
  display: flex;
  gap: 6px;
}

.quote-card-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.quote-tournament {
  font-weight: 500;
  font-size: 14px;
}

.quote-details {
  font-size: 12px;
  color: #666;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.dark-mode .quote-details {
  color: #aaa;
}

.quote-flight {
  font-size: 12px;
  color: #888;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

.dark-mode .quote-flight {
  color: #999;
}

.quote-link {
  color: #18a058;
  display: flex;
  align-items: center;
  margin-left: auto;
}

.mobile-pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 16px;
  padding: 16px 0;
  margin-top: 8px;
}

.results-card {
  min-height: 400px;
}

.config-display {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.search-card {
  margin-bottom: 16px;
}

.api-usage-popover {
  min-width: 280px;
  max-width: 320px;
}

.api-usage-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color);
}

.api-key-item {
  padding: 12px;
  background: var(--card-bg);
  border-radius: 6px;
  margin-bottom: 8px;
}

.api-key-item:last-child {
  margin-bottom: 0;
}

.api-key-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.masked-key {
  font-size: 12px;
  background: var(--card-bg);
  padding: 2px 6px;
  border-radius: 4px;
}

.api-key-stats {
  font-size: 13px;
}

.api-key-error {
  font-size: 12px;
}

.no-keys {
  text-align: center;
  padding: 16px;
}

.session-info-popover {
  min-width: 300px;
  max-width: 360px;
}

.session-info-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color);
}

.session-info-content {
  font-size: 13px;
}

.session-id {
  font-size: 11px;
  background: var(--card-bg);
  padding: 2px 6px;
  border-radius: 4px;
}

.search-progress {
  padding: 12px;
  background: var(--card-bg);
  border-radius: 6px;
}

.progress-details {
  font-size: 13px;
}

.last-search-summary {
  padding: 8px 12px;
  background: rgba(24, 160, 88, 0.1);
  border-radius: 6px;
  font-size: 13px;
}

.search-settings {
  padding: 8px 12px;
  background: var(--card-bg);
  border-radius: 6px;
}
</style>
