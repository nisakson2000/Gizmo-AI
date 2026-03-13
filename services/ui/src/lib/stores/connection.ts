import { writable } from 'svelte/store';

export type ConnectionStatus = 'connected' | 'disconnected' | 'generating';
export const connectionStatus = writable<ConnectionStatus>('disconnected');
