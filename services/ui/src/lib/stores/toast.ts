import { writable } from 'svelte/store';

export interface ToastAction {
	label: string;
	onclick: () => void;
}

export interface ToastItem {
	id: string;
	message: string;
	type: 'info' | 'success' | 'error';
	duration: number;
	action?: ToastAction;
}

export const toasts = writable<ToastItem[]>([]);

let counter = 0;

export function toast(message: string, type: 'info' | 'success' | 'error' = 'info', duration?: number, action?: ToastAction) {
	const id = `toast-${++counter}`;
	const dur = duration ?? (type === 'error' ? 6000 : 3000);
	toasts.update((t) => {
		const next = [...t, { id, message, type, duration: dur, action }];
		// Max 3 visible — dismiss oldest
		while (next.length > 3) next.shift();
		return next;
	});
	setTimeout(() => dismissToast(id), dur);
	return id;
}

export function dismissToast(id: string) {
	toasts.update((t) => t.filter((item) => item.id !== id));
}
