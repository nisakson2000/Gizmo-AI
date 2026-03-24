<script lang="ts">
	let { open = $bindable(false) }: { open: boolean } = $props();

	interface SavedVoice {
		id: string;
		name: string;
		filename: string;
		size: number;
	}

	let text = $state('');
	let synthesizing = $state(false);
	let error = $state('');
	let audioUrl = $state('');
	let audioEl: HTMLAudioElement;

	// Revoke object URL when component closes to prevent memory leak
	$effect(() => {
		if (!open && audioUrl) {
			URL.revokeObjectURL(audioUrl);
			audioUrl = '';
		}
	});
	let voices = $state<SavedVoice[]>([]);
	let selectedVoiceId = $state<string | null>(null);
	let uploading = $state(false);
	let voiceName = $state('');
	let clipDuration = $state(30);
	let showUploadForm = $state(false);
	let pendingFile = $state<File | null>(null);

	async function loadVoices() {
		try {
			const resp = await fetch('/api/voices');
			if (resp.ok) voices = await resp.json();
		} catch {
			voices = [];
		}
	}

	$effect(() => {
		if (open) loadVoices();
	});

	function pickFile() {
		const inp = document.createElement('input');
		inp.type = 'file';
		inp.accept = 'audio/*';
		inp.onchange = (e) => {
			const file = (e.target as HTMLInputElement).files?.[0];
			if (!file) return;
			if (file.size > 50 * 1024 * 1024) {
				error = 'File too large. Max 50MB.';
				setTimeout(() => (error = ''), 5000);
				return;
			}
			pendingFile = file;
			voiceName = file.name.replace(/\.[^.]+$/, '');
			showUploadForm = true;
		};
		inp.click();
	}

	async function saveVoice() {
		if (!pendingFile || !voiceName.trim()) return;
		uploading = true;
		error = '';
		try {
			const formData = new FormData();
			formData.append('file', pendingFile);
			formData.append('name', voiceName.trim());
			formData.append('max_duration', String(clipDuration));
			const resp = await fetch('/api/voices', { method: 'POST', body: formData });
			if (!resp.ok) {
				error = 'Failed to save voice.';
				return;
			}
			const voice = await resp.json();
			voices = [...voices, voice];
			selectedVoiceId = voice.id;
			showUploadForm = false;
			pendingFile = null;
			voiceName = '';
		} catch {
			error = 'Upload failed. Check your connection.';
		} finally {
			uploading = false;
		}
	}

	async function deleteVoice(id: string) {
		try {
			await fetch(`/api/voices/${id}`, { method: 'DELETE' });
			voices = voices.filter((v) => v.id !== id);
			if (selectedVoiceId === id) selectedVoiceId = null;
		} catch {
			error = 'Failed to delete voice.';
		}
	}

	async function synthesize() {
		if (!text.trim()) return;
		synthesizing = true;
		error = '';

		try {
			const body: Record<string, string> = { text: text.trim() };
			if (selectedVoiceId) {
				body.voice_id = selectedVoiceId;
			}

			const resp = await fetch('/api/tts', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify(body),
			});

			if (!resp.ok) {
				const err = await resp.json().catch(() => null);
				error = err?.error || `TTS failed (${resp.status})`;
				return;
			}

			const blob = await resp.blob();
			if (audioUrl) URL.revokeObjectURL(audioUrl);
			audioUrl = URL.createObjectURL(blob);

			await new Promise((r) => setTimeout(r, 50));
			if (audioEl) audioEl.play();
		} catch {
			error = 'TTS service unavailable. Is it running?';
		} finally {
			synthesizing = false;
		}
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			synthesize();
		}
		if (e.key === 'Escape') {
			open = false;
		}
	}

	function close() {
		open = false;
	}

	function formatSize(bytes: number): string {
		if (bytes < 1024) return `${bytes}B`;
		if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`;
		return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center"
		role="dialog"
		aria-label="Voice Studio"
		onkeydown={(e) => { if (e.key === 'Escape') close(); }}
	>
		<!-- svelte-ignore a11y_click_events_have_key_events -->
		<div class="absolute inset-0" onclick={close}></div>
		<div class="relative bg-bg-secondary border border-border/60 rounded-2xl w-full max-w-lg mx-4 shadow-2xl max-h-[85vh] overflow-y-auto">
			<!-- Header -->
			<div class="flex items-center justify-between p-5 border-b border-border/40">
				<div class="flex items-center gap-2">
					<svg class="w-5 h-5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.009 9.009 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" />
					</svg>
					<h2 class="text-base font-semibold">Voice Studio</h2>
				</div>
				<button
					onclick={close}
					class="text-text-dim hover:text-text-secondary transition-colors p-1"
					aria-label="Close voice studio"
				>
					<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M6 18L18 6M6 6l12 12" />
					</svg>
				</button>
			</div>

			<div class="p-5 space-y-4">
				<!-- Saved Voices -->
				<div>
					<div class="flex items-center justify-between mb-2">
						<p class="text-sm font-medium">Voices</p>
						<button
							onclick={pickFile}
							class="text-xs text-accent hover:text-accent-dim transition-colors"
						>
							+ Add Voice
						</button>
					</div>

					{#if showUploadForm}
						<div class="bg-bg-tertiary/50 rounded-lg p-3 mb-2 space-y-2">
							<p class="text-xs text-text-dim">Name this voice:</p>
							<input
								type="text"
								bind:value={voiceName}
								placeholder="e.g. Morgan Freeman"
								class="w-full bg-bg-primary border border-border/40 rounded-lg px-3 py-1.5 text-sm text-text-primary placeholder:text-text-dim focus:outline-none focus:border-accent/40"
							/>
							<div class="flex items-center gap-2 text-xs text-text-dim">
								<span>{pendingFile?.name}</span>
								<span>({pendingFile ? formatSize(pendingFile.size) : ''})</span>
							</div>
							<div>
								<p class="text-xs text-text-dim mb-1">Clip duration (first N seconds used):</p>
								<div class="flex gap-1">
									{#each [30, 60, 90, 120] as dur}
										<button
											onclick={() => clipDuration = dur}
											class="flex-1 py-1 rounded-md text-xs font-medium transition-all {clipDuration === dur
												? 'bg-accent text-white'
												: 'bg-bg-primary text-text-dim border border-border/40 hover:border-border'}"
										>
											{dur}s
										</button>
									{/each}
								</div>
							</div>
							<div class="flex gap-2">
								<button
									onclick={saveVoice}
									disabled={uploading || !voiceName.trim()}
									class="flex-1 py-1.5 rounded-lg text-xs font-medium transition-all {voiceName.trim() && !uploading
										? 'bg-accent text-white hover:bg-accent-dim'
										: 'bg-bg-tertiary text-text-dim cursor-not-allowed'}"
								>
									{uploading ? 'Saving...' : 'Save Voice'}
								</button>
								<button
									onclick={() => { showUploadForm = false; pendingFile = null; }}
									class="px-3 py-1.5 rounded-lg text-xs text-text-dim hover:text-text-secondary bg-bg-primary border border-border/40"
								>
									Cancel
								</button>
							</div>
						</div>
					{/if}

					<!-- Voice List -->
					<div class="space-y-1">
						<button
							onclick={() => selectedVoiceId = null}
							class="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-xs text-left transition-all {selectedVoiceId === null
								? 'bg-accent/15 border border-accent/30 text-text-primary'
								: 'bg-bg-tertiary/30 border border-transparent text-text-secondary hover:bg-bg-tertiary/50'}"
						>
							<svg class="w-3.5 h-3.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.009 9.009 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" />
							</svg>
							<span class="flex-1">Default Voice</span>
						</button>
						{#each voices as voice (voice.id)}
							<div class="flex items-center gap-1">
								<button
									onclick={() => selectedVoiceId = voice.id}
									class="flex-1 flex items-center gap-2 px-3 py-2 rounded-lg text-xs text-left transition-all {selectedVoiceId === voice.id
										? 'bg-accent/15 border border-accent/30 text-text-primary'
										: 'bg-bg-tertiary/30 border border-transparent text-text-secondary hover:bg-bg-tertiary/50'}"
								>
									<svg class="w-3.5 h-3.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.536 8.464a5 5 0 010 7.072M12 6a6 6 0 00-4.243 10.243" />
									</svg>
									<span class="flex-1 truncate">{voice.name}</span>
									<span class="text-text-dim">{formatSize(voice.size)}</span>
								</button>
								<button
									onclick={() => deleteVoice(voice.id)}
									class="p-1.5 text-text-dim hover:text-error transition-colors rounded"
									aria-label="Delete voice"
								>
									<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
									</svg>
								</button>
							</div>
						{/each}
					</div>
					{#if voices.length === 0 && !showUploadForm}
						<p class="text-xs text-text-dim text-center py-2">No saved voices yet. Click "+ Add Voice" to upload one.</p>
					{/if}
				</div>

				<!-- Text Input -->
				<div>
					<p class="text-sm font-medium mb-1">Text</p>
					<textarea
						bind:value={text}
						onkeydown={handleKeydown}
						placeholder="Type what you want spoken..."
						rows="3"
						class="w-full resize-none bg-bg-tertiary/50 border border-border/40 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-dim focus:outline-none focus:border-accent/40 transition-colors"
					></textarea>
				</div>

				<!-- Error -->
				{#if error}
					<div class="px-3 py-2 bg-error/10 border border-error/20 rounded-lg text-xs text-error">
						{error}
					</div>
				{/if}

				<!-- Synthesize Button -->
				<button
					onclick={synthesize}
					disabled={!text.trim() || synthesizing}
					class="w-full py-2.5 rounded-xl text-sm font-medium transition-all {text.trim() && !synthesizing
						? 'bg-accent text-white hover:bg-accent-dim'
						: 'bg-bg-tertiary text-text-dim cursor-not-allowed'}"
				>
					{#if synthesizing}
						<span class="flex items-center justify-center gap-2">
							<svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
								<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
								<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
							</svg>
							Synthesizing...
						</span>
					{:else}
						{#if selectedVoiceId}Speak as {voices.find(v => v.id === selectedVoiceId)?.name}{:else}Speak It{/if}
					{/if}
				</button>

				<!-- Audio Player -->
				{#if audioUrl}
					<div class="bg-bg-tertiary/50 rounded-lg p-3">
						<audio bind:this={audioEl} src={audioUrl} controls class="w-full h-8" style="color-scheme: dark;">
							<track kind="captions" />
						</audio>
					</div>
				{/if}

				<p class="text-[11px] text-text-dim text-center">Powered by Qwen3-TTS — runs locally on your GPU</p>
			</div>
		</div>
	</div>
{/if}
