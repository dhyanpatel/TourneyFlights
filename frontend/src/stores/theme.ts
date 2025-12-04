import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

export const useThemeStore = defineStore('theme', () => {
  const isDark = ref(false);

  // Initialize from localStorage or system preference
  function initTheme(): void {
    const stored = localStorage.getItem('theme');
    if (stored) {
      isDark.value = stored === 'dark';
    } else {
      isDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches;
    }
  }

  // Watch for changes and persist
  watch(isDark, (value) => {
    localStorage.setItem('theme', value ? 'dark' : 'light');
  });

  function toggleTheme(): void {
    isDark.value = !isDark.value;
  }

  return {
    isDark,
    initTheme,
    toggleTheme,
  };
});
