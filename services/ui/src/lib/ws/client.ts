import { get } from 'svelte/store';
import {
	generating,
	streamingThinking,
	streamingContent,
	streamingToolCalls,
	currentTraceId,
	finalizeAssistantMessage,
	activeConversationId,
	loadConversations,
	updateConversationTitle,
	pendingTtsInfo,
	generatingConversationId,
} from '$lib/stores/chat';
import { connectionStatus } from '$lib/stores/connection';
import { thinkingEnabled, ttsEnabled, ttsVoiceId, contextLength } from '$lib/stores/settings';

let ws: WebSocket | null = null;
let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
let reconnectDelay = 1000;
let audioFinalized = false;
const MAX_RECONNECT_DELAY = 30000;

function getWsUrl(): string {
	const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
	return `${proto}//${window.location.host}/ws/chat`;
}

export function connect() {
	if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
		return;
	}

	connectionStatus.set('connecting');

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
	connectionStatus.set('connecting');
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
			streamingToolCalls.update((calls) => [
				...calls,
				{ tool: data.tool, status: data.status || 'running' },
			]);
			break;
		case 'tool_result':
			streamingToolCalls.update((calls) => {
				let matched = false;
				return calls.map((tc) => {
					if (!matched && tc.tool === data.tool && tc.status === 'running') {
						matched = true;
						return { ...tc, status: 'done', result: data.result };
					}
					return tc;
				});
			});
			break;
		case 'tts_info':
			pendingTtsInfo.set(data.message || '');
			break;
		case 'audio': {
			// Finalize with audio — mark as finalized to prevent double-finalize in done handler
			audioFinalized = true;
			finalizeAssistantMessage(get(currentTraceId), data.url);
			generating.set(false);
			connectionStatus.set('connected');
			loadConversations();
			return; // done event will handle conversation_id and cleanup
		}
		case 'done': {
			// Always set conversation ID if user is still on this conversation
			if (data.conversation_id) {
				const curId = get(activeConversationId);
				if (curId === null || curId === data.conversation_id) {
					activeConversationId.set(data.conversation_id);
				}
			}
			// Finalize if still generating and audio handler didn't already do it
			if (get(generating) && !audioFinalized) {
				finalizeAssistantMessage(get(currentTraceId));
				generating.set(false);
				connectionStatus.set('connected');
			}
			// Always cleanup
			audioFinalized = false;
			generatingConversationId.set(null);
			streamingThinking.set('');
			streamingContent.set('');
			streamingToolCalls.set([]);
			loadConversations();
			break;
		}
		case 'title':
			if (data.conversation_id && data.title) {
				updateConversationTitle(data.conversation_id, data.title);
			}
			break;
		case 'error':
			generating.set(false);
			connectionStatus.set('connected');
			streamingContent.update((c) => c + `\n\n**Error:** ${data.error}`);
			finalizeAssistantMessage(data.trace_id || '');
			break;
	}
}

export function send(message: string, imageDataUrl?: string, videoFrames?: string[], videoUrl?: string, options?: { regenerate?: boolean }) {
	if (!ws || ws.readyState !== WebSocket.OPEN) return;

	generating.set(true);
	generatingConversationId.set(get(activeConversationId));
	connectionStatus.set('generating');
	audioFinalized = false;
	streamingThinking.set('');
	streamingContent.set('');
	streamingToolCalls.set([]);

	const payload: Record<string, unknown> = {
		message,
		thinking: get(thinkingEnabled),
		conversation_id: get(activeConversationId),
		tts: get(ttsEnabled),
		context_length: get(contextLength),
	};
	const voiceId = get(ttsVoiceId);
	if (voiceId) {
		payload.voice_id = voiceId;
	}
	if (options?.regenerate) {
		payload.regenerate = true;
	}
	if (videoFrames && videoFrames.length > 0) {
		payload.video_frames = videoFrames;
		if (videoUrl) payload.video_url = videoUrl;
	} else if (imageDataUrl) {
		payload.image = imageDataUrl;
	}
	ws.send(JSON.stringify(payload));
}

export function stopGeneration() {
	if (!get(generating)) return;
	finalizeAssistantMessage(get(currentTraceId));
	generating.set(false);
	connectionStatus.set('connecting');
	// Close and reconnect — backend handles WebSocketDisconnect cleanly
	if (ws) {
		ws.onclose = null; // Prevent double reconnect
		ws.close();
		ws = null;
	}
	reconnectDelay = 1000;
	connect();
}

export function disconnect() {
	if (reconnectTimeout) clearTimeout(reconnectTimeout);
	if (ws) ws.close();
	ws = null;
}

export function isConnected(): boolean {
	return ws !== null && ws.readyState === WebSocket.OPEN;
}
