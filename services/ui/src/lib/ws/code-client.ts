import { writable, get } from 'svelte/store';

// ── Stores ────────────────────────────────────────────────────────────

export interface CodeMessage {
	id: string;
	role: 'user' | 'assistant';
	content: string;
	thinking: string;
	toolCalls?: { tool: string; status: string; result?: string }[];
}

export const codeMessages = writable<CodeMessage[]>([]);
export const codeGenerating = writable(false);
export const codeStreamingContent = writable('');
export const codeStreamingThinking = writable('');

let streamingToolCalls: { tool: string; status: string; result?: string }[] = [];
let currentTraceId = '';

// ── WebSocket ─────────────────────────────────────────────────────────

let ws: WebSocket | null = null;
let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
let reconnectDelay = 1000;
const MAX_RECONNECT_DELAY = 30000;

function uuid(): string {
	if (typeof crypto !== 'undefined' && crypto.randomUUID) {
		return crypto.randomUUID();
	}
	return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) =>
		(+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16)
	);
}

function getWsUrl(): string {
	const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
	return `${proto}//${window.location.host}/ws/code-chat`;
}

export function connectCodeChat() {
	if (reconnectTimeout) { clearTimeout(reconnectTimeout); reconnectTimeout = null; }
	if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
		return;
	}

	try {
		ws = new WebSocket(getWsUrl());
	} catch {
		scheduleReconnect();
		return;
	}

	ws.onopen = () => {
		reconnectDelay = 1000;
	};

	ws.onclose = () => {
		codeGenerating.set(false);
		scheduleReconnect();
	};

	ws.onerror = () => {};

	ws.onmessage = (event) => {
		try {
			const data = JSON.parse(event.data);
			handleEvent(data);
		} catch {}
	};
}

function scheduleReconnect() {
	if (reconnectTimeout) clearTimeout(reconnectTimeout);
	reconnectTimeout = setTimeout(() => {
		reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
		connectCodeChat();
	}, reconnectDelay);
}

function handleEvent(data: any) {
	switch (data.type) {
		case 'trace_id':
			currentTraceId = data.trace_id;
			break;
		case 'thinking':
			codeStreamingThinking.update((t) => t + data.content);
			break;
		case 'token':
			codeStreamingContent.update((c) => c + data.content);
			break;
		case 'tool_call':
			streamingToolCalls = [
				...streamingToolCalls,
				{ tool: data.tool, status: data.status || 'running' },
			];
			break;
		case 'tool_result': {
			let matched = false;
			streamingToolCalls = streamingToolCalls.map((tc) => {
				if (!matched && tc.tool === data.tool && tc.status === 'running') {
					matched = true;
					return { ...tc, status: 'done', result: data.result };
				}
				return tc;
			});
			break;
		}
		case 'done':
			finalizeCodeMessage();
			codeGenerating.set(false);
			break;
		case 'error':
			codeStreamingContent.update((c) => c + `\n\n**Error:** ${data.error}`);
			finalizeCodeMessage();
			codeGenerating.set(false);
			break;
	}
}

function finalizeCodeMessage() {
	const thinking = get(codeStreamingThinking);
	const content = get(codeStreamingContent);

	if (!content && !thinking) return;

	const msg: CodeMessage = {
		id: uuid(),
		role: 'assistant',
		content,
		thinking,
		toolCalls: streamingToolCalls.length > 0 ? [...streamingToolCalls] : undefined,
	};

	codeMessages.update((msgs) => [...msgs, msg]);
	codeStreamingThinking.set('');
	codeStreamingContent.set('');
	streamingToolCalls = [];
	currentTraceId = '';
}

export function sendCodeMessage(message: string, code: string = '', language: string = 'python') {
	if (!ws || ws.readyState !== WebSocket.OPEN) return;

	codeGenerating.set(true);
	codeStreamingThinking.set('');
	codeStreamingContent.set('');
	streamingToolCalls = [];

	codeMessages.update((msgs) => [
		...msgs,
		{
			id: uuid(),
			role: 'user',
			content: message,
			thinking: '',
		},
	]);

	ws.send(JSON.stringify({ message, code, language }));
}

export function disconnectCodeChat() {
	if (reconnectTimeout) { clearTimeout(reconnectTimeout); reconnectTimeout = null; }
	reconnectDelay = 1000;
	const content = get(codeStreamingContent);
	const thinking = get(codeStreamingThinking);
	if (content || thinking) {
		finalizeCodeMessage();
	}
	codeGenerating.set(false);
	if (ws) ws.close();
	ws = null;
}
