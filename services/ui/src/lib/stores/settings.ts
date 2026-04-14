import { writable } from 'svelte/store';
import { persistedWritable } from './persisted';
import type { SuggestionAction } from '$lib/constants/suggestions';

export const thinkingEnabled = persistedWritable('gizmo:thinking', false);
export const ttsEnabled = persistedWritable('gizmo:tts', false);
export const contextLength = persistedWritable('gizmo:contextLength', 32768);
export const sidebarOpen = persistedWritable('gizmo:sidebar', true);
export const settingsOpen = writable(false);
export const pendingAction = writable<SuggestionAction | null>(null);
export const voiceStudioOpen = writable(false);
export const memoryManagerOpen = writable(false);
export const codePlaygroundOpen = writable(false);
export const ttsVoiceId = persistedWritable<string | null>('gizmo:ttsVoiceId', null);
export const ttsSpeed = persistedWritable<number>('gizmo:ttsSpeed', 1.0);
export const ttsLanguage = persistedWritable<string>('gizmo:ttsLanguage', 'Auto');
export const activeMode = persistedWritable<string>('gizmo:mode', 'chat');
export const modeEditorOpen = writable(false);

export interface ModeInfo {
	name: string;
	label: string;
	description: string;
	icon: string;
	order: number;
	builtin: boolean;
}

export const modes = writable<ModeInfo[]>([]);

export async function refreshModes() {
	try {
		const resp = await fetch('/api/modes');
		if (resp.ok) modes.set(await resp.json());
	} catch {
		// Keep existing modes on error
	}
}

export const focusTrigger = writable(0);
