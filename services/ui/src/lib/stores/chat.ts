import { writable, derived, get } from 'svelte/store';

export interface Message {
	id: string;
	role: 'user' | 'assistant';
	content: string;
	thinking: string;
	timestamp: string;
	traceId?: string;
	audioUrl?: string;
	toolCalls?: { tool: string; status: string; result?: string }[];
}

export interface Conversation {
	id: string;
	title: string;
	created_at: string;
	updated_at: string;
}

export const conversations = writable<Conversation[]>([]);
export const activeConversationId = writable<string | null>(null);
export const messages = writable<Message[]>([]);
export const generating = writable(false);
export const streamingThinking = writable('');
export const streamingContent = writable('');
export const currentTraceId = writable('');

export function newConversation() {
	activeConversationId.set(null);
	messages.set([]);
	streamingThinking.set('');
	streamingContent.set('');
}

export function addUserMessage(content: string): string {
	const id = crypto.randomUUID();
	const msg: Message = {
		id,
		role: 'user',
		content,
		thinking: '',
		timestamp: new Date().toISOString(),
	};
	messages.update((msgs) => [...msgs, msg]);
	return id;
}

export function finalizeAssistantMessage(traceId: string, audioUrl?: string) {
	const thinking = get(streamingThinking);
	const content = get(streamingContent);
	const msg: Message = {
		id: crypto.randomUUID(),
		role: 'assistant',
		content,
		thinking,
		timestamp: new Date().toISOString(),
		traceId,
		audioUrl,
	};
	messages.update((msgs) => [...msgs, msg]);
	streamingThinking.set('');
	streamingContent.set('');
}

export async function loadConversations() {
	try {
		const resp = await fetch('/api/conversations');
		if (resp.ok) {
			const data = await resp.json();
			conversations.set(data);
		}
	} catch {
		// Service unavailable
	}
}

export async function loadConversation(id: string) {
	try {
		const resp = await fetch(`/api/conversations/${id}`);
		if (resp.ok) {
			const data = await resp.json();
			activeConversationId.set(id);
			messages.set(
				data.messages.map((m: any) => ({
					id: crypto.randomUUID(),
					role: m.role,
					content: m.content,
					thinking: m.thinking || '',
					timestamp: m.timestamp,
				}))
			);
		}
	} catch {
		// Service unavailable
	}
}

export async function deleteConversation(id: string) {
	try {
		await fetch(`/api/conversations/${id}`, { method: 'DELETE' });
		conversations.update((convs) => convs.filter((c) => c.id !== id));
		if (get(activeConversationId) === id) {
			newConversation();
		}
	} catch {
		// Ignore
	}
}
