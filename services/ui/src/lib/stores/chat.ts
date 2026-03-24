import { writable, derived, get } from 'svelte/store';

/** crypto.randomUUID() requires a secure context (HTTPS or localhost).
 *  Fall back to a manual v4 UUID for plain HTTP access (e.g. Tailscale IP). */
function uuid(): string {
	if (typeof crypto !== 'undefined' && crypto.randomUUID) {
		return crypto.randomUUID();
	}
	return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) =>
		(+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16)
	);
}

export interface MessageVariant {
	content: string;
	thinking: string;
	traceId?: string;
	timestamp: string;
	toolCalls?: { tool: string; status: string; result?: string }[];
	audioUrl?: string;
	promptVariantIndex?: number; // which user prompt variant generated this response
}

export interface Message {
	id: string;
	role: 'user' | 'assistant';
	content: string;
	thinking: string;
	timestamp: string;
	traceId?: string;
	audioUrl?: string;
	imageUrl?: string;
	videoUrl?: string;
	videoFrames?: string[];
	toolCalls?: { tool: string; status: string; result?: string }[];
	ttsInfo?: string;
	variants?: MessageVariant[];
	variantIndex?: number;
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
export const streamingToolCalls = writable<{ tool: string; status: string; result?: string }[]>([]);
export const currentTraceId = writable('');
export const generatingConversationId = writable<string | null>(null);
export const pendingVariants = writable<MessageVariant[]>([]);
export const pendingPromptIndex = writable<number>(0);
export const pendingTtsInfo = writable<string>('');

// Derived: ID of the last assistant message (avoids O(N) scan per ChatMessage)
export const lastAssistantId = derived(messages, ($msgs) => {
	for (let i = $msgs.length - 1; i >= 0; i--) {
		if ($msgs[i].role === 'assistant') return $msgs[i].id;
	}
	return null;
});

export function newConversation() {
	activeConversationId.set(null);
	messages.set([]);
}

export function addUserMessage(content: string, imageUrl?: string, videoFrames?: string[], videoUrl?: string, variants?: MessageVariant[], variantIndex?: number): string {
	const id = uuid();
	const msg: Message = {
		id,
		role: 'user',
		content,
		thinking: '',
		timestamp: new Date().toISOString(),
		imageUrl: imageUrl || (videoFrames?.length ? videoFrames[0] : undefined),
		videoUrl,
		videoFrames,
		variants,
		variantIndex,
	};
	messages.update((msgs) => [...msgs, msg]);
	return id;
}

export function finalizeAssistantMessage(traceId: string, audioUrl?: string) {
	const thinking = get(streamingThinking);
	const content = get(streamingContent);
	const toolCalls = get(streamingToolCalls);
	const pending = get(pendingVariants);
	const promptIdx = get(pendingPromptIndex);

	const newVariant: MessageVariant = {
		content,
		thinking,
		traceId,
		timestamp: new Date().toISOString(),
		toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
		audioUrl,
		promptVariantIndex: pending.length > 0 ? promptIdx : undefined,
	};

	let variants: MessageVariant[] | undefined;
	let variantIndex: number | undefined;
	if (pending.length > 0) {
		variants = [...pending, newVariant];
		variantIndex = variants.length - 1;
		pendingVariants.set([]);
		pendingPromptIndex.set(0);
	}

	const ttsInfo = get(pendingTtsInfo) || undefined;

	const msg: Message = {
		id: uuid(),
		role: 'assistant',
		content,
		thinking,
		timestamp: new Date().toISOString(),
		traceId,
		audioUrl,
		toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
		ttsInfo,
		variants,
		variantIndex,
	};
	messages.update((msgs) => [...msgs, msg]);
	streamingThinking.set('');
	streamingContent.set('');
	streamingToolCalls.set([]);
	pendingTtsInfo.set('');
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
					id: uuid(),
					role: m.role,
					content: m.content,
					thinking: m.thinking || '',
					timestamp: m.timestamp,
					audioUrl: m.audio_url || undefined,
					imageUrl: m.image_url || undefined,
					videoUrl: m.video_url || undefined,
					toolCalls: m.tool_calls ? JSON.parse(m.tool_calls) : undefined,
				}))
			);
		}
	} catch {
		// Service unavailable
	}
}

export function updateConversationTitle(id: string, title: string) {
	conversations.update((convs) =>
		convs.map((c) => (c.id === id ? { ...c, title } : c))
	);
}

export function setVariantIndex(messageId: string, index: number) {
	messages.update((msgs) =>
		msgs.map((m) => (m.id === messageId ? { ...m, variantIndex: index } : m))
	);
}

export async function truncateMessagesFrom(index: number): Promise<boolean> {
	const convId = get(activeConversationId);
	if (!convId) return false;
	try {
		const resp = await fetch(`/api/conversations/${convId}/messages-from/${index}`, { method: 'DELETE' });
		return resp.ok;
	} catch {
		return false;
	}
}

export async function renameConversation(id: string, title: string): Promise<boolean> {
	try {
		const resp = await fetch(`/api/conversations/${id}`, {
			method: 'PATCH',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ title }),
		});
		if (resp.ok) {
			conversations.update((convs) =>
				convs.map((c) => (c.id === id ? { ...c, title } : c))
			);
			return true;
		}
	} catch {}
	return false;
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
