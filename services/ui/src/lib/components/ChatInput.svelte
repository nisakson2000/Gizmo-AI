<script lang="ts">
	import { generating, generatingConversationId, activeConversationId, addUserMessage } from '$lib/stores/chat';
	import { send, stopGeneration } from '$lib/ws/client';
	import { connectionStatus } from '$lib/stores/connection';
	import { pendingSuggestion, thinkingEnabled, voiceStudioOpen, focusTrigger } from '$lib/stores/settings';
	import ModeSelector from './ModeSelector.svelte';
	import { toast } from '$lib/stores/toast';
	import { fetchWithTimeout } from '$lib/utils/fetch';
	import { playSelect } from '$lib/utils/sounds';

	let input = $state('');
	let recording = $state(false);
	let requestingMic = $state(false);
	let pendingImage = $state<{ filename: string; data_url: string } | null>(null);
	let pendingFile = $state<{ filename: string; content: string } | null>(null);
	let pendingVideo = $state<{ filename: string; frames: string[]; duration: number; video_url?: string } | null>(null);
	let uploading = $state(false);
	let textarea: HTMLTextAreaElement;
	let mediaRecorder: MediaRecorder | null = null;

	// Accept suggestion from empty state cards
	$effect(() => {
		const s = $pendingSuggestion;
		if (s) {
			if (s === '__audio_upload__') {
				pendingSuggestion.set('');
				openAudioPicker();
				return;
			}
			input = s;
			pendingSuggestion.set('');
			if (textarea) {
				textarea.focus();
				textarea.style.height = 'auto';
				textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px';
			}
		}
	});

	// Focus input when triggered by keyboard shortcut
	$effect(() => {
		$focusTrigger;
		if ($focusTrigger > 0 && textarea) textarea.focus();
	});

	// Listen for upload trigger from ConsoleButtons
	$effect(() => {
		const handler = () => handleFileUpload();
		document.addEventListener('gizmo:upload', handler);
		return () => document.removeEventListener('gizmo:upload', handler);
	});

	const MAX_DOC_SIZE = 50 * 1024 * 1024;
	const MAX_IMAGE_SIZE = 50 * 1024 * 1024;
	const MAX_VIDEO_SIZE = 500 * 1024 * 1024;

	function handleSubmit() {
		const text = input.trim();
		if (!text && !pendingImage && !pendingFile && !pendingVideo) return;
		if (uploading) return;
		playSelect();
		const generatingHere = $generating && $generatingConversationId === $activeConversationId;
		if (generatingHere) {
			showError('Still generating — wait or click stop.');
			return;
		}
		if ($connectionStatus !== 'connected' && $connectionStatus !== 'generating') {
			showError(`Not connected (${$connectionStatus}). WebSocket may be down — check the status indicator in the header.`);
			return;
		}

		if (pendingVideo) {
			addUserMessage(text || `Analyze this video: ${pendingVideo.filename} (${pendingVideo.duration}s, ${pendingVideo.frames.length} frames)`, undefined, pendingVideo.frames, pendingVideo.video_url);
			send(text || 'Please analyze this video.', undefined, pendingVideo.frames, pendingVideo.video_url);
			pendingVideo = null;
		} else if (pendingImage) {
			addUserMessage(text || `Analyze this image: ${pendingImage.filename}`, pendingImage.data_url);
			send(text || 'Please analyze this image.', pendingImage.data_url);
			pendingImage = null;
		} else if (pendingFile) {
			const preview = pendingFile.content.substring(0, 500);
			addUserMessage(`[Uploaded file: ${pendingFile.filename}]\n\`\`\`\n${preview}\n\`\`\``);
			send(`${text ? text + '\n\n' : ''}I've uploaded a file called "${pendingFile.filename}" with this content:\n\n${pendingFile.content}`);
			pendingFile = null;
		} else {
			addUserMessage(text);
			send(text);
		}
		input = '';
		if (textarea) textarea.style.height = 'auto';
	}

	function autoResize() {
		if (textarea) {
			textarea.style.height = 'auto';
			textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px';
		}
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey && !uploading) {
			e.preventDefault();
			handleSubmit();
		}
	}

	function showError(msg: string) {
		toast(msg, 'error');
	}

	function handleFileUpload() {
		const inp = document.createElement('input');
		inp.type = 'file';
		inp.accept = 'image/*,video/*,audio/*,.pdf,.txt,.md,.py,.js,.ts,.json,.yaml,.yml,.toml,.csv';
		inp.onchange = async (e) => {
			const file = (e.target as HTMLInputElement).files?.[0];
			if (!file) return;

			const isImage = file.type.startsWith('image/');
			const isVideo = file.type.startsWith('video/');
			const isAudio = file.type.startsWith('audio/');
			const maxSize = isVideo ? MAX_VIDEO_SIZE : MAX_DOC_SIZE;
			if (file.size > maxSize) {
				showError(`File too large. Max ${isVideo ? '500MB' : '50MB'}.`);
				return;
			}

			const formData = new FormData();
			formData.append('file', file);

			if (isAudio) {
				// Transcribe audio via Whisper, then stage transcript as a file
				try {
					uploading = true;
					const resp = await fetchWithTimeout('/api/transcribe', { method: 'POST', body: formData });
					uploading = false;
					if (!resp.ok) {
						showError('Audio transcription failed.');
						return;
					}
					const data = await resp.json();
					if (data.text) {
						pendingFile = { filename: file.name, content: `[Transcribed audio from: ${file.name}]\n\n${data.text}` };
					} else {
						showError('No speech detected in audio.');
					}
				} catch {
					uploading = false;
					showError('Whisper service unavailable.');
				}
				return;
			}

			const endpoint = isVideo ? '/api/upload-video' : isImage ? '/api/upload-image' : '/api/upload';

			try {
				uploading = true;
				const resp = await fetchWithTimeout(endpoint, { method: 'POST', body: formData });
				uploading = false;
				if (!resp.ok) {
					const err = await resp.json().catch(() => null);
					showError(err?.error || 'Upload failed. Server returned an error.');
					return;
				}
				const data = await resp.json();
				if (isVideo) {
					pendingVideo = { filename: data.filename, frames: data.frames, duration: data.duration, video_url: data.video_url };
				} else if (isImage) {
					pendingImage = { filename: data.filename, data_url: data.data_url };
				} else {
					pendingFile = { filename: data.filename, content: data.content || '' };
				}
			} catch {
				uploading = false;
				showError('Upload failed. Check your connection.');
			}
		};
		inp.click();
	}

	function openAudioPicker() {
		const inp = document.createElement('input');
		inp.type = 'file';
		inp.accept = 'audio/*';
		inp.onchange = async (e) => {
			const file = (e.target as HTMLInputElement).files?.[0];
			if (!file) return;
			if (file.size > MAX_DOC_SIZE) {
				showError('File too large. Max 50MB.');
				return;
			}
			const formData = new FormData();
			formData.append('file', file);
			try {
				uploading = true;
				const resp = await fetchWithTimeout('/api/transcribe', { method: 'POST', body: formData });
				uploading = false;
				if (!resp.ok) {
					showError('Audio transcription failed.');
					return;
				}
				const data = await resp.json();
				if (data.text) {
					pendingFile = { filename: file.name, content: `[Transcribed audio from: ${file.name}]\n\n${data.text}` };
				} else {
					showError('No speech detected in audio.');
				}
			} catch {
				uploading = false;
				showError('Whisper service unavailable.');
			}
		};
		inp.click();
	}

	async function toggleRecording() {
		if (recording && mediaRecorder) {
			mediaRecorder.stop();
			recording = false;
			return;
		}

		if (requestingMic) return;
		if (!navigator.mediaDevices?.getUserMedia) {
			showError('Microphone requires HTTPS. Access Gizmo via Tailscale HTTPS or localhost for mic support.');
			return;
		}

		requestingMic = true;
		try {
			const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
			mediaRecorder = new MediaRecorder(stream, { mimeType: 'audio/webm' });
			const chunks: Blob[] = [];

			mediaRecorder.ondataavailable = (e) => {
				if (e.data.size > 0) chunks.push(e.data);
			};

			mediaRecorder.onstop = async () => {
				stream.getTracks().forEach((t) => t.stop());
				const blob = new Blob(chunks, { type: 'audio/webm' });
				const formData = new FormData();
				formData.append('file', blob, 'recording.webm');

				try {
					const resp = await fetchWithTimeout('/api/transcribe', { method: 'POST', body: formData });
					if (resp.ok) {
						const data = await resp.json();
						if (data.text) {
							input += (input ? ' ' : '') + data.text;
							autoResize();
						}
					} else {
						showError('Transcription failed.');
					}
				} catch {
					showError('Whisper service unavailable.');
				}
			};

			mediaRecorder.start();
			recording = true;
		} catch {
			showError('Microphone access denied.');
		} finally {
			requestingMic = false;
		}
	}
</script>

<div class="border-t border-border/40 bg-bg-primary px-4 pb-4 pt-3">
	{#if $connectionStatus !== 'connected' && $connectionStatus !== 'generating'}
		<div class="max-w-3xl mx-auto mb-2 px-3 py-2 bg-amber-500/10 border border-amber-500/30 rounded-lg text-xs text-amber-400 flex items-center gap-2">
			<svg class="w-4 h-4 flex-shrink-0 animate-spin" fill="none" viewBox="0 0 24 24">
				<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
				<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
			</svg>
			{$connectionStatus === 'connecting' ? 'Connecting to server...' : 'Disconnected — attempting to reconnect...'}
		</div>
	{/if}
	{#if pendingImage}
		<div class="max-w-3xl mx-auto mb-2 px-3 py-2 bg-bg-secondary border border-border/40 rounded-lg text-xs text-text-secondary flex items-center gap-2">
			<svg class="w-4 h-4 flex-shrink-0 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M3.75 21h16.5A2.25 2.25 0 0022.5 18.75V5.25A2.25 2.25 0 0020.25 3H3.75A2.25 2.25 0 001.5 5.25v13.5A2.25 2.25 0 003.75 21z" />
			</svg>
			<span class="truncate flex-1">{pendingImage.filename} attached</span>
			<button onclick={() => (pendingImage = null)} class="text-text-dim hover:text-error transition-colors">
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
				</svg>
			</button>
		</div>
	{/if}
	{#if pendingFile}
		<div class="max-w-3xl mx-auto mb-2 px-3 py-2 bg-bg-secondary border border-border/40 rounded-lg text-xs text-text-secondary flex items-center gap-2">
			<svg class="w-4 h-4 flex-shrink-0 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
			</svg>
			<span class="truncate flex-1">{pendingFile.filename} attached</span>
			<button onclick={() => (pendingFile = null)} class="text-text-dim hover:text-error transition-colors">
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
				</svg>
			</button>
		</div>
	{/if}
	{#if pendingVideo}
		<div class="max-w-3xl mx-auto mb-2 px-3 py-2 bg-bg-secondary border border-border/40 rounded-lg text-xs text-text-secondary flex items-center gap-2">
			<svg class="w-4 h-4 flex-shrink-0 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="m15.75 10.5 4.72-4.72a.75.75 0 0 1 1.28.53v11.38a.75.75 0 0 1-1.28.53l-4.72-4.72M4.5 18.75h9a2.25 2.25 0 0 0 2.25-2.25v-9a2.25 2.25 0 0 0-2.25-2.25h-9A2.25 2.25 0 0 0 2.25 7.5v9a2.25 2.25 0 0 0 2.25 2.25z" />
			</svg>
			<span class="truncate flex-1">{pendingVideo.filename} — {pendingVideo.duration}s, {pendingVideo.frames.length} frames</span>
			<button onclick={() => (pendingVideo = null)} class="text-text-dim hover:text-error transition-colors">
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
				</svg>
			</button>
		</div>
	{/if}
	{#if uploading}
		<div class="max-w-3xl mx-auto mb-2 px-3 py-2 bg-bg-secondary border border-border/40 rounded-lg text-xs text-text-secondary flex items-center gap-2">
			<svg class="w-4 h-4 flex-shrink-0 animate-spin text-accent" fill="none" viewBox="0 0 24 24">
				<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
				<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
			</svg>
			Processing upload...
		</div>
	{/if}
	<div class="max-w-3xl mx-auto">
		<div class="flex items-end gap-2 bg-bg-secondary border border-border/60 rounded-2xl px-4 py-2.5 focus-within:border-accent/40 transition-colors">
			<button
				onclick={handleFileUpload}
				class="p-1.5 text-text-dim hover:text-text-secondary transition-colors flex-shrink-0 mb-0.5"
				aria-label="Upload file"
				disabled={$generating && $generatingConversationId === $activeConversationId}
			>
				<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M18.375 12.739l-7.693 7.693a4.5 4.5 0 01-6.364-6.364l10.94-10.94A3 3 0 1119.5 7.372L8.552 18.32m.009-.01l-.01.01m5.699-9.941l-7.81 7.81a1.5 1.5 0 002.112 2.13" />
				</svg>
			</button>

			<textarea
				bind:this={textarea}
				bind:value={input}
				onkeydown={handleKeydown}
				oninput={autoResize}
				placeholder={$connectionStatus === 'connected' || $connectionStatus === 'generating' ? (pendingVideo ? 'Ask about the video...' : pendingImage ? 'Ask about the image...' : pendingFile ? 'Ask about the file...' : 'Message Gizmo...') : 'Connecting...'}
				disabled={$connectionStatus === 'disconnected'}
				rows="1"
				class="flex-1 resize-none bg-transparent text-text-primary placeholder:text-text-dim focus:outline-none text-[16px] leading-[1.5] max-h-[200px] py-1 pl-1"
			></textarea>

			<button
				onclick={toggleRecording}
				class="p-1.5 rounded-lg flex-shrink-0 mb-0.5 transition-all {recording
					? 'bg-error/20 text-error animate-pulse'
					: 'text-text-dim hover:text-text-secondary'}"
				aria-label={recording ? 'Stop recording' : 'Voice input'}
				disabled={$generating && $generatingConversationId === $activeConversationId}
			>
				<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 18.75a6 6 0 006-6v-1.5m-6 7.5a6 6 0 01-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15.75a3 3 0 01-3-3V4.5a3 3 0 116 0v8.25a3 3 0 01-3 3z" />
				</svg>
			</button>

			{#if $generating && $generatingConversationId === $activeConversationId}
				<button
					onclick={() => stopGeneration()}
					class="p-1.5 rounded-lg bg-text-dim/20 text-text-secondary hover:bg-text-dim/30 transition-colors flex-shrink-0 mb-0.5"
					aria-label="Stop generation"
				>
					<svg class="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
						<rect x="7" y="7" width="10" height="10" rx="1.5" />
					</svg>
				</button>
			{:else}
				<button
					onclick={handleSubmit}
					disabled={uploading || (!input.trim() && !pendingImage && !pendingFile && !pendingVideo)}
					class="p-1.5 rounded-lg flex-shrink-0 mb-0.5 transition-all {uploading
						? 'bg-transparent text-text-dim animate-pulse'
						: input.trim() || pendingImage || pendingFile || pendingVideo
							? 'bg-accent text-white hover:bg-accent-dim'
							: 'bg-transparent text-text-dim'}"
					aria-label={uploading ? 'Uploading...' : 'Send message'}
				>
					<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4.5 10.5L12 3m0 0l7.5 7.5M12 3v18" />
					</svg>
				</button>
			{/if}
		</div>
		<!-- Toolbar: Think toggle + Voice Studio -->
		<div class="flex items-center gap-2 mt-2 px-1">
			<button
				onclick={() => thinkingEnabled.update((v) => !v)}
				class="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium transition-all {$thinkingEnabled
					? 'bg-accent text-white'
					: 'bg-bg-secondary text-text-dim border border-border/50 hover:border-border hover:text-text-secondary'}"
				aria-label="Toggle thinking mode"
			>
				<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
				</svg>
				Think
			</button>
			<ModeSelector />
			<button
				onclick={() => voiceStudioOpen.set(true)}
				class="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-bg-secondary text-text-dim border border-border/50 hover:border-border hover:text-text-secondary transition-all"
				aria-label="Open voice studio"
			>
				<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.009 9.009 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" />
				</svg>
				Voice Studio
			</button>
			<span class="flex-1"></span>
			<p class="text-[11px] text-text-dim">Gizmo runs entirely on your machine.</p>
		</div>
	</div>
</div>
