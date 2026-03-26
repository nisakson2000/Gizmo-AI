import { writable, get } from 'svelte/store';
import { loadTasks, loadNotes } from '$lib/stores/tracker';

// ── Stores ────────────────────────────────────────────────────────────

export interface TrackerMessage {
	id: string;
	role: 'user' | 'assistant';
	content: string;
	thinking: string;
	toolCalls?: { tool: string; status: string; result?: string }[];
}

export const trackerMessages = writable<TrackerMessage[]>([]);
export const trackerGenerating = writable(false);
export const trackerStreamingContent = writable('');
export const trackerStreamingThinking = writable('');

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
	return `${proto}//${window.location.host}/ws/tracker`;
}

export function connectTracker() {
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
		trackerGenerating.set(false);
		scheduleReconnect();
	};

	ws.onerror = () => {
		// Connection error
	};

	ws.onmessage = (event) => {
		try {
			const data = JSON.parse(event.data);
			handleEvent(data);
		} catch {
			// Invalid JSON
		}
	};
}

function scheduleReconnect() {
	if (reconnectTimeout) clearTimeout(reconnectTimeout);
	reconnectTimeout = setTimeout(() => {
		reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY);
		connectTracker();
	}, reconnectDelay);
}

function handleEvent(data: any) {
	switch (data.type) {
		case 'trace_id':
			currentTraceId = data.trace_id;
			break;
		case 'thinking':
			trackerStreamingThinking.update((t) => t + data.content);
			break;
		case 'token':
			trackerStreamingContent.update((c) => c + data.content);
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
			finalizeTrackerMessage();
			trackerGenerating.set(false);
			// Refresh task/note lists once after all tool calls complete
			loadTasks();
			loadNotes();
			break;
		case 'error':
			trackerStreamingContent.update((c) => c + `\n\n**Error:** ${data.error}`);
			finalizeTrackerMessage();
			trackerGenerating.set(false);
			break;
	}
}

export function finalizeTrackerMessage() {
	const thinking = get(trackerStreamingThinking);
	const content = get(trackerStreamingContent);

	if (!content && !thinking) return;

	const msg: TrackerMessage = {
		id: uuid(),
		role: 'assistant',
		content,
		thinking,
		toolCalls: streamingToolCalls.length > 0 ? [...streamingToolCalls] : undefined,
	};

	trackerMessages.update((msgs) => [...msgs, msg]);
	trackerStreamingThinking.set('');
	trackerStreamingContent.set('');
	streamingToolCalls = [];
	currentTraceId = '';
}

export function sendTrackerMessage(message: string, thinking: boolean = false) {
	if (!ws || ws.readyState !== WebSocket.OPEN) return;

	trackerGenerating.set(true);
	trackerStreamingThinking.set('');
	trackerStreamingContent.set('');
	streamingToolCalls = [];

	// Add user message to history
	trackerMessages.update((msgs) => [
		...msgs,
		{
			id: uuid(),
			role: 'user',
			content: message,
			thinking: '',
		},
	]);

	ws.send(JSON.stringify({ message, thinking }));
}

export function disconnectTracker() {
	if (reconnectTimeout) { clearTimeout(reconnectTimeout); reconnectTimeout = null; }
	reconnectDelay = 1000;
	// Clear any in-flight streaming state
	const content = get(trackerStreamingContent);
	const thinking = get(trackerStreamingThinking);
	if (content || thinking) {
		finalizeTrackerMessage();
	}
	trackerGenerating.set(false);
	if (ws) ws.close();
	ws = null;
}
