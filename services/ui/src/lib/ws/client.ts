import { get } from 'svelte/store';
import {
	generating,
	streamingThinking,
	streamingContent,
	currentTraceId,
	finalizeAssistantMessage,
	activeConversationId,
	loadConversations,
} from '$lib/stores/chat';
import { connectionStatus } from '$lib/stores/connection';
import { thinkingEnabled, ttsEnabled } from '$lib/stores/settings';

let ws: WebSocket | null = null;
let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
let reconnectDelay = 1000;
const MAX_RECONNECT_DELAY = 30000;

function getWsUrl(): string {
	const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
	return `${proto}//${window.location.host}/ws/chat`;
}

export function connect() {
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
		connectionStatus.set('connected');
		reconnectDelay = 1000;
	};

	ws.onclose = () => {
		connectionStatus.set('disconnected');
		generating.set(false);
		scheduleReconnect();
	};

	ws.onerror = () => {
		connectionStatus.set('disconnected');
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
		connect();
	}, reconnectDelay);
}

function handleEvent(data: any) {
	switch (data.type) {
		case 'trace_id':
			currentTraceId.set(data.trace_id);
			break;
		case 'thinking':
			streamingThinking.update((t) => t + data.content);
			break;
		case 'token':
			streamingContent.update((c) => c + data.content);
			break;
		case 'tool_call':
			// Tool call status — could show in UI
			break;
		case 'tool_result':
			// Tool result — could show in UI
			break;
		case 'audio':
			// Audio will be attached to finalized message
			finalizeAssistantMessage(get(currentTraceId), data.url);
			generating.set(false);
			connectionStatus.set('connected');
			loadConversations();
			return; // Don't finalize again in 'done'
		case 'done':
			if (data.conversation_id) {
				activeConversationId.set(data.conversation_id);
			}
			finalizeAssistantMessage(get(currentTraceId));
			generating.set(false);
			connectionStatus.set('connected');
			loadConversations();
			break;
		case 'error':
			generating.set(false);
			connectionStatus.set('connected');
			streamingContent.update((c) => c + `\n\n**Error:** ${data.error}`);
			finalizeAssistantMessage(data.trace_id || '');
			break;
	}
}

export function send(message: string) {
	if (!ws || ws.readyState !== WebSocket.OPEN) return;

	generating.set(true);
	connectionStatus.set('generating');
	streamingThinking.set('');
	streamingContent.set('');

	ws.send(
		JSON.stringify({
			message,
			thinking: get(thinkingEnabled),
			conversation_id: get(activeConversationId),
			tts: get(ttsEnabled),
		})
	);
}

export function disconnect() {
	if (reconnectTimeout) clearTimeout(reconnectTimeout);
	if (ws) ws.close();
	ws = null;
}

export function isConnected(): boolean {
	return ws !== null && ws.readyState === WebSocket.OPEN;
}
