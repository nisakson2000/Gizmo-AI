<script lang="ts">
	import { onMount } from 'svelte';
	import { generating, addUserMessage } from '$lib/stores/chat';
	import { send } from '$lib/ws/client';
	import { connectionStatus } from '$lib/stores/connection';

	let input = $state('');
	let textarea: HTMLTextAreaElement;
	let sendBtn: HTMLButtonElement;
	let uploadBtn: HTMLButtonElement;
	let micBtn: HTMLButtonElement;
	let recording = $state(false);
	let mediaRecorder: MediaRecorder | null = null;

	function handleSubmit() {
		const text = input.trim();
		if (!text || $generating) return;
		addUserMessage(text);
		send(text);
		input = '';
		if (textarea) {
			textarea.style.height = 'auto';
		}
	}

	function autoResize() {
		if (textarea) {
			textarea.style.height = 'auto';
			textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px';
		}
	}

	function handleFileUpload() {
		const inp = document.createElement('input');
		inp.type = 'file';
		inp.accept = 'image/*,.pdf,.txt,.md,.py,.js,.ts,.json,.yaml,.yml,.toml,.csv';
		inp.onchange = async (e) => {
			const file = (e.target as HTMLInputElement).files?.[0];
			if (!file) return;

			const formData = new FormData();
			formData.append('file', file);

			const isImage = file.type.startsWith('image/');
			const endpoint = isImage ? '/api/upload-image' : '/api/upload';

			try {
				const resp = await fetch(endpoint, { method: 'POST', body: formData });
				if (resp.ok) {
					const data = await resp.json();
					if (isImage) {
						addUserMessage(`[Uploaded image: ${data.filename}]`);
						send(`[Image uploaded: ${data.filename}] Please analyze this image.`);
					} else {
						const preview = data.content?.substring(0, 500) || '';
						addUserMessage(`[Uploaded file: ${data.filename}]\n\`\`\`\n${preview}\n\`\`\``);
						send(`I've uploaded a file called "${data.filename}" with this content:\n\n${data.content}`);
					}
				}
			} catch {
				// Upload failed
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

		try {
			const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
			const chunks: Blob[] = [];
			mediaRecorder = new MediaRecorder(stream);

			mediaRecorder.ondataavailable = (e) => chunks.push(e.data);
			mediaRecorder.onstop = async () => {
				stream.getTracks().forEach((t) => t.stop());
				const blob = new Blob(chunks, { type: 'audio/webm' });
				const formData = new FormData();
				formData.append('file', blob, 'recording.webm');

				try {
					const resp = await fetch('/api/transcribe', { method: 'POST', body: formData });
					if (resp.ok) {
						const data = await resp.json();
						input = data.text || '';
						autoResize();
					}
				} catch {
					// Transcription failed
				}
			};

			mediaRecorder.start();
			recording = true;
		} catch {
			// Mic access denied
		}
	}

	// Use direct addEventListener to bypass Svelte 5 event delegation
	onMount(() => {
		textarea.addEventListener('keydown', (e: KeyboardEvent) => {
			if (e.key === 'Enter' && !e.shiftKey) {
				e.preventDefault();
				handleSubmit();
			}
		});
		textarea.addEventListener('input', autoResize);
		sendBtn.addEventListener('click', handleSubmit);
		uploadBtn.addEventListener('click', handleFileUpload);
		micBtn.addEventListener('click', toggleRecording);
	});
</script>

<div class="border-t border-border bg-bg-secondary p-3">
	<div class="flex items-end gap-2 max-w-4xl mx-auto">
		<button
			bind:this={uploadBtn}
			class="p-2 text-text-secondary hover:text-text-primary transition-colors flex-shrink-0"
			aria-label="Upload file"
			disabled={$generating}
		>
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
			</svg>
		</button>

		<button
			bind:this={micBtn}
			class="p-2 flex-shrink-0 transition-colors {recording ? 'text-error animate-pulse' : 'text-text-secondary hover:text-text-primary'}"
			aria-label={recording ? 'Stop recording' : 'Record audio'}
		>
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
			</svg>
		</button>

		<textarea
			bind:this={textarea}
			bind:value={input}
			placeholder={$connectionStatus === 'connected' ? 'Message Gizmo...' : 'Connecting...'}
			disabled={$connectionStatus === 'disconnected'}
			rows="1"
			class="flex-1 resize-none bg-bg-tertiary border border-border rounded-lg px-3 py-2 text-text-primary placeholder:text-text-dim focus:outline-none focus:border-accent-dim text-[15px] leading-[1.5] max-h-[200px]"
		></textarea>

		<button
			bind:this={sendBtn}
			disabled={$generating || !input.trim()}
			class="p-2 rounded-lg flex-shrink-0 transition-colors {$generating || !input.trim()
				? 'text-text-dim bg-bg-tertiary'
				: 'text-white bg-accent hover:bg-accent-dim'}"
			aria-label="Send message"
		>
			{#if $generating}
				<svg class="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24">
					<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
					<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
				</svg>
			{:else}
				<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
				</svg>
			{/if}
		</button>
	</div>
</div>
