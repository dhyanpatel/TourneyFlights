<script setup lang="ts">
import { computed } from 'vue';
import { NConfigProvider, NMessageProvider } from 'naive-ui';
import { useSessionStore } from '@/stores/session';
import ApiKeySetup from '@/views/ApiKeySetup.vue';
import FlightSearch from '@/views/FlightSearch.vue';

const sessionStore = useSessionStore();

const currentView = computed(() => {
  return sessionStore.isAuthenticated ? 'search' : 'setup';
});

function handleSessionCreated(): void {
  // View will automatically switch due to computed property
}

function handleLogout(): void {
  // View will automatically switch due to computed property
}
</script>

<template>
  <NConfigProvider>
    <NMessageProvider>
      <ApiKeySetup
        v-if="currentView === 'setup'"
        @session-created="handleSessionCreated"
      />
      <FlightSearch
        v-else
        @logout="handleLogout"
      />
    </NMessageProvider>
  </NConfigProvider>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen,
    Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif;
}
</style>
