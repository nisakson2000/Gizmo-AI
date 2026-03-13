import { writable } from 'svelte/store';

export const thinkingEnabled = writable(false);
export const ttsEnabled = writable(false);
export const ttsVoice = writable('af_heart');
export const contextLength = writable(16384);
export const sidebarOpen = writable(true);
export const settingsOpen = writable(false);
