<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { NConfigProvider, NMessageProvider, darkTheme } from 'naive-ui';
import { useSessionStore } from '@/stores/session';
import { useThemeStore } from '@/stores/theme';
import ApiKeySetup from '@/views/ApiKeySetup.vue';
import FlightSearch from '@/views/FlightSearch.vue';

const sessionStore = useSessionStore();
const themeStore = useThemeStore();

onMounted(() => {
  themeStore.initTheme();
});

const currentView = computed(() => {
  return sessionStore.isAuthenticated ? 'search' : 'setup';
});

const theme = computed(() => themeStore.isDark ? darkTheme : null);

function handleSessionCreated(): void {
  // View will automatically switch due to computed property
}

function handleLogout(): void {
  // View will automatically switch due to computed property
}
</script>

<template>
  <NConfigProvider :theme="theme">
    <NMessageProvider>
      <div :class="{ 'dark-mode': themeStore.isDark }">
        <ApiKeySetup
          v-if="currentView === 'setup'"
          @session-created="handleSessionCreated"
        />
        <FlightSearch
          v-else
          @logout="handleLogout"
        />
      </div>
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
  transition: background-color 0.3s, color 0.3s;
}

.dark-mode {
  background-color: #18181c;
  color: #fff;
  min-height: 100vh;
}
</style>
