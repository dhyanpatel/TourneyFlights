<script setup lang="ts">
import { ref } from 'vue';
import {
  NCard,
  NButton,
  NInput,
  NSpace,
  NAlert,
  NSpin,
  NIcon,
  NText,
  NDivider,
} from 'naive-ui';
import { AddOutline, TrashOutline, KeyOutline } from '@vicons/ionicons5';
import { useSessionStore } from '@/stores/session';

const emit = defineEmits<{
  (e: 'session-created'): void;
}>();

const sessionStore = useSessionStore();

const apiKeys = ref<string[]>(['']);

function addApiKey(): void {
  apiKeys.value.push('');
}

function removeApiKey(index: number): void {
  if (apiKeys.value.length > 1) {
    apiKeys.value.splice(index, 1);
  }
}

function updateApiKey(index: number, value: string): void {
  apiKeys.value[index] = value;
}

const validApiKeys = () => apiKeys.value.filter((key) => key.trim().length > 0);

const canSubmit = () => validApiKeys().length > 0;

async function handleSubmit(): Promise<void> {
  const keys = validApiKeys();
  if (keys.length === 0) return;

  const success = await sessionStore.createSession(keys);
  if (success) {
    emit('session-created');
  }
}
</script>

<template>
  <div class="setup-container">
    <NCard class="setup-card">
      <template #header>
        <div class="card-header">
          <NIcon size="32" color="#18a058">
            <KeyOutline />
          </NIcon>
          <span class="header-title">TourneyFlights</span>
        </div>
      </template>

      <NSpace vertical size="large">
        <NText depth="3">
          Enter your SerpAPI key(s) to search for flight quotes to table tennis
          tournaments. You need at least one API key to continue.
        </NText>

        <NDivider />

        <div class="api-keys-section">
          <NText strong>API Keys</NText>
          <NSpace vertical size="medium" class="keys-list">
            <div
              v-for="(key, index) in apiKeys"
              :key="index"
              class="key-row"
            >
              <NInput
                :value="key"
                @update:value="(val: string) => updateApiKey(index, val)"
                placeholder="Enter SerpAPI key..."
                type="password"
                show-password-on="click"
                :disabled="sessionStore.isLoading"
              />
              <NButton
                v-if="apiKeys.length > 1"
                quaternary
                circle
                type="error"
                :disabled="sessionStore.isLoading"
                @click="removeApiKey(index)"
              >
                <template #icon>
                  <NIcon>
                    <TrashOutline />
                  </NIcon>
                </template>
              </NButton>
            </div>
          </NSpace>

          <NButton
            dashed
            block
            :disabled="sessionStore.isLoading"
            @click="addApiKey"
          >
            <template #icon>
              <NIcon>
                <AddOutline />
              </NIcon>
            </template>
            Add Another API Key
          </NButton>
        </div>

        <NAlert
          v-if="sessionStore.error"
          type="error"
          closable
          @close="sessionStore.clearError"
        >
          {{ sessionStore.error }}
        </NAlert>

        <NButton
          type="primary"
          size="large"
          block
          :disabled="!canSubmit() || sessionStore.isLoading"
          :loading="sessionStore.isLoading"
          @click="handleSubmit"
        >
          <template v-if="sessionStore.isLoading">
            <NSpin size="small" />
            <span style="margin-left: 8px">Creating Session...</span>
          </template>
          <template v-else>
            Start Searching Flights
          </template>
        </NButton>

        <NText depth="3" style="font-size: 12px; text-align: center; display: block;">
          Session creation may take a few seconds as it loads tournament data
          and fetches flight quotes.
        </NText>
      </NSpace>
    </NCard>
  </div>
</template>

<style scoped>
.setup-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.setup-card {
  width: 100%;
  max-width: 480px;
  border-radius: 12px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 24px;
  font-weight: 600;
}

.api-keys-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.keys-list {
  width: 100%;
}

.key-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.key-row :deep(.n-input) {
  flex: 1;
}
</style>
