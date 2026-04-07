<script lang="ts">
	import { marked } from 'marked';
	import { tick } from 'svelte';
	import { messages, generating, streamingThinking, streamingContent, streamingToolCalls, activeConversationId, generatingConversationId, loadingConversation } from '$lib/stores/chat';
	import { pendingSuggestion, voiceStudioOpen } from '$lib/stores/settings';
	import { sanitize } from '$lib/utils/sanitize';
	import { highlightCode } from '$lib/actions/highlight';
	import ChatMessage from './ChatMessage.svelte';
	import ThinkingBlock from './ThinkingBlock.svelte';
	import ToolCallBlock from './ToolCallBlock.svelte';

	const suggestions = [
		{ icon: 'eye', label: 'Vision', prompt: 'Describe what you see in the image I upload', desc: 'Analyze images, screenshots, diagrams' },
		{ icon: 'video', label: 'Video', prompt: 'Describe what happens in this video', desc: 'Upload videos for frame-by-frame analysis' },
		{ icon: 'audio', label: 'Audio', prompt: '__audio_upload__', desc: 'Transcribe & analyze audio files' },
		{ icon: 'search', label: 'Search', prompt: 'Search the web for the latest news today', desc: 'Real-time web search via SearXNG' },
		{ icon: 'brain', label: 'Reason', prompt: 'Think step by step: if a train travels 60 mph for 2.5 hours, how far does it go?', desc: 'Extended thinking for complex problems' },
		{ icon: 'code', label: 'Code', prompt: 'Write code that ', desc: 'Ask Gizmo to write and run code' },
		{ icon: 'mic', label: 'Voice Studio', prompt: '__voice_studio__', desc: 'Clone voices & text-to-speech' },
		{ icon: 'file', label: 'Files', prompt: 'Summarize the document I upload', desc: 'Upload PDFs, code, text for analysis' },
	];

	function useSuggestion(prompt: string) {
		if (prompt === '__voice_studio__') {
			voiceStudioOpen.set(true);
			return;
		}
		if (prompt === '__audio_upload__') {
			pendingSuggestion.set('__audio_upload__');
			return;
		}
		if (prompt) pendingSuggestion.set(prompt);
	}

	let chatContainer: HTMLDivElement;
	let userScrolled = $state(false);
	let parsedStreamingHtml = $state('');
	let parseTimer: ReturnType<typeof setTimeout> | null = null;
	let showLoadingSpinner = $state(false);
	let loadingTimer: ReturnType<typeof setTimeout> | null = null;

	// Show spinner after 150ms delay to avoid flash on fast loads
	$effect(() => {
		if ($loadingConversation) {
			loadingTimer = setTimeout(() => { showLoadingSpinner = true; }, 150);
		} else {
			if (loadingTimer) { clearTimeout(loadingTimer); loadingTimer = null; }
			showLoadingSpinner = false;
		}
		return () => { if (loadingTimer) { clearTimeout(loadingTimer); loadingTimer = null; } };
	});

	function scrollToBottom() {
		if (chatContainer && !userScrolled) {
			chatContainer.scrollTop = chatContainer.scrollHeight;
		}
	}

	function handleScroll() {
		if (!chatContainer) return;
		const { scrollTop, scrollHeight, clientHeight } = chatContainer;
		userScrolled = scrollHeight - scrollTop - clientHeight > 100;
	}

	function parseStreamingContent(raw: string) {
		try {
			parsedStreamingHtml = sanitize(marked.parse(raw) as string);
		} catch {
			parsedStreamingHtml = raw;
		}
	}

	// Debounced markdown parsing — ~5-7 parses/sec instead of ~60
	$effect(() => {
		const raw = $streamingContent;
		if (!raw) {
			parsedStreamingHtml = '';
			if (parseTimer) { clearTimeout(parseTimer); parseTimer = null; }
			return;
		}
		if (parseTimer) clearTimeout(parseTimer);
		parseTimer = setTimeout(() => {
			parseStreamingContent(raw);
			parseTimer = null;
		}, 150);
	});

	// Final parse when streaming ends
	$effect(() => {
		if (!$generating && $streamingContent) {
			if (parseTimer) { clearTimeout(parseTimer); parseTimer = null; }
			parseStreamingContent($streamingContent);
		}
	});

	// Scroll to bottom when loading a conversation
	$effect(() => {
		$activeConversationId;
		tick().then(() => {
			if (chatContainer) {
				userScrolled = false;
				chatContainer.scrollTop = chatContainer.scrollHeight;
			}
		});
	});

	// Auto-scroll on new content
	$effect(() => {
		$messages;
		$streamingContent;
		$streamingThinking;
		scrollToBottom();
	});
</script>

<div
	bind:this={chatContainer}
	onscroll={handleScroll}
	class="flex-1 overflow-y-auto relative"
>
	{#if showLoadingSpinner && $messages.length === 0}
		<div class="flex items-center justify-center h-full">
			<svg class="w-6 h-6 text-accent animate-spin" fill="none" viewBox="0 0 24 24">
				<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
				<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
			</svg>
		</div>
	{:else if $messages.length === 0 && !$generating}
		<!-- Empty state -->
		<div class="flex flex-col items-center justify-center h-full text-center px-4">
			<div class="w-14 h-14 rounded-2xl bg-accent/10 flex items-center justify-center mb-5">
				<svg class="w-7 h-7 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456z" />
				</svg>
			</div>
			<h1 class="text-2xl font-semibold text-text-primary mb-1">Gizmo</h1>
			<p class="text-sm text-text-dim mb-2">Powered by Qwen3.5 9B</p>
			<p class="text-xs text-text-dim/60 mb-8">100% local — nothing leaves your machine</p>

			<div class="grid grid-cols-2 sm:grid-cols-4 gap-2 max-w-2xl w-full text-sm">
				{#each suggestions as s}
					<button
						onclick={() => useSuggestion(s.prompt)}
						class="bg-bg-secondary/60 border border-border/50 rounded-xl px-4 py-3 text-left hover:border-accent/40 hover:bg-bg-secondary hover:scale-[1.02] active:scale-[0.98] transition-all cursor-pointer"
					>
						<div class="flex items-center gap-2 mb-1">
							{#if s.icon === 'eye'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
							{:else if s.icon === 'video'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="m15.75 10.5 4.72-4.72a.75.75 0 0 1 1.28.53v11.38a.75.75 0 0 1-1.28.53l-4.72-4.72M4.5 18.75h9a2.25 2.25 0 0 0 2.25-2.25v-9a2.25 2.25 0 0 0-2.25-2.25h-9A2.25 2.25 0 0 0 2.25 7.5v9a2.25 2.25 0 0 0 2.25 2.25z" /></svg>
							{:else if s.icon === 'audio'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" /></svg>
							{:else if s.icon === 'search'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" /></svg>
							{:else if s.icon === 'brain'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" /></svg>
							{:else if s.icon === 'code'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.25 6.75L22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3l-4.5 16.5" /></svg>
							{:else if s.icon === 'mic'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.009 9.009 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" /></svg>
							{:else if s.icon === 'file'}
								<svg class="w-3.5 h-3.5 text-text-secondary" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" /></svg>
							{/if}
							<p class="text-text-secondary text-xs">{s.label}</p>
						</div>
						<p class="text-text-secondary text-[13px]">{s.desc}</p>
					</button>
				{/each}
			</div>
		</div>
	{:else}
		<div class="max-w-3xl mx-auto px-4 py-6">
			{#each $messages as message, i (message.id)}
				<div class={$messages.length <= 20 || i >= $messages.length - 3 ? 'msg-appear' : ''} data-role={message.role}>
					<ChatMessage {message} />
				</div>
			{/each}

			{#if $generating && $generatingConversationId === $activeConversationId}
				<div class="mb-6 msg-appear" data-role="assistant">
					{#if $streamingThinking}
						<ThinkingBlock content={$streamingThinking} streaming={true} />
					{/if}

					{#each $streamingToolCalls as tc, i (i)}
						<ToolCallBlock tool={tc.tool} status={tc.status} result={tc.result} />
					{/each}

					{#if parsedStreamingHtml}
						<div class="prose-chat" use:highlightCode={parsedStreamingHtml}>
							{@html parsedStreamingHtml}
							<span class="streaming-cursor">|</span>
						</div>
					{:else if !$streamingThinking && $streamingToolCalls.length === 0}
						<div class="flex items-center gap-2 py-2">
							<div class="flex gap-1">
								<div class="w-1.5 h-1.5 bg-accent-dim rounded-full thinking-dot"></div>
								<div class="w-1.5 h-1.5 bg-accent-dim rounded-full thinking-dot" style="animation-delay: 150ms"></div>
								<div class="w-1.5 h-1.5 bg-accent-dim rounded-full thinking-dot" style="animation-delay: 300ms"></div>
							</div>
							<span class="text-xs text-accent-dim thinking-pulse">Gizmo is thinking...</span>
						</div>
					{/if}
				</div>
			{/if}
		</div>
	{/if}

	{#if userScrolled}
		<button
			onclick={() => { userScrolled = false; scrollToBottom(); }}
			class="absolute bottom-6 right-6 z-10 w-9 h-9 rounded-full bg-accent text-white shadow-lg flex items-center justify-center hover:bg-accent-dim transition-opacity duration-200"
			aria-label="Scroll to bottom"
		>
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
			</svg>
		</button>
	{/if}
</div>
