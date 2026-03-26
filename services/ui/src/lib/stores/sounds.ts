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

export const soundsEnabled = persistedWritable<boolean>('gizmo:sounds', false);
export const bootAnimationsEnabled = persistedWritable<boolean>('gizmo:bootAnimations', true);
