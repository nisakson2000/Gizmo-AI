import { writable } from 'svelte/store';

function persistedWritable<T>(key: string, defaultValue: T) {
	const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null;
	const initial = stored !== null ? JSON.parse(stored) : defaultValue;
	const store = writable<T>(initial);
	if (typeof localStorage !== 'undefined') {
		store.subscribe((value) => localStorage.setItem(key, JSON.stringify(value)));
	}
	return store;
}

export const thinkingEnabled = persistedWritable('gizmo:thinking', false);
export const ttsEnabled = persistedWritable('gizmo:tts', false);
export const contextLength = persistedWritable('gizmo:contextLength', 32768);
export const sidebarOpen = persistedWritable('gizmo:sidebar', true);
export const settingsOpen = writable(false);
export const pendingSuggestion = writable('');
export const voiceStudioOpen = writable(false);
export const memoryManagerOpen = writable(false);
export const codePlaygroundOpen = writable(false);
export const ttsVoiceId = persistedWritable<string | null>('gizmo:ttsVoiceId', null);
export const focusTrigger = writable(0);
