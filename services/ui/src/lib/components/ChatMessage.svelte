<script lang="ts">
	import { marked } from 'marked';
	import { sanitize } from '$lib/utils/sanitize';
	import { highlightCode } from '$lib/actions/highlight';
	import ThinkingBlock from './ThinkingBlock.svelte';
	import ToolCallBlock from './ToolCallBlock.svelte';
	import { messages, generating, truncateMessagesFrom, addUserMessage, pendingVariants, pendingPromptIndex, setVariantIndex, lastAssistantId } from '$lib/stores/chat';
	import { send } from '$lib/ws/client';
	import { get } from 'svelte/store';
	import { toast } from '$lib/stores/toast';
	import type { Message, MessageVariant } from '$lib/stores/chat';

	let { message }: { message: Message } = $props();
	let editing = $state(false);
	let editText = $state('');

	marked.setOptions({ breaks: true, gfm: true });

	let isLastAssistant = $derived($lastAssistantId === message.id);

	// Variant-aware display: use variant data if available
	let activeVariant = $derived.by((): MessageVariant | null => {
		if (message.variants && message.variantIndex !== undefined) {
			return message.variants[message.variantIndex] ?? null;
		}
		return null;
	});

	let displayContent = $derived(activeVariant?.content ?? message.content);
	let displayThinking = $derived(activeVariant?.thinking ?? message.thinking);
	let displayToolCalls = $derived(activeVariant?.toolCalls ?? message.toolCalls);
	let displayAudioUrl = $derived(activeVariant?.audioUrl ?? message.audioUrl);

	let renderedHtml = $derived.by(() => {
		try {
			return sanitize(marked.parse(displayContent) as string);
		} catch {
			return displayContent;
		}
	});

	async function copyMessage() {
		await navigator.clipboard.writeText(displayContent);
		toast('Copied to clipboard', 'success');
	}

	async function regenerate() {
		const msgs = get(messages);
		const idx = msgs.findIndex((m) => m.id === message.id);
		if (idx < 0) return;
		// Find the user message before this assistant message
		let userMsg: Message | null = null;
		for (let i = idx - 1; i >= 0; i--) {
			if (msgs[i].role === 'user') { userMsg = msgs[i]; break; }
		}
		if (!userMsg) return;

		const userPromptIdx = userMsg.variantIndex ?? 0;

		// Preserve existing variants with promptVariantIndex, or save current as first
		let allVariants: MessageVariant[];
		if (message.variants && message.variants.length > 0) {
			allVariants = message.variants.map((v) => ({
				...v,
				promptVariantIndex: v.promptVariantIndex ?? userPromptIdx,
			}));
		} else {
			allVariants = [{
				content: message.content,
				thinking: message.thinking,
				traceId: message.traceId,
				timestamp: message.timestamp,
				toolCalls: message.toolCalls,
				audioUrl: message.audioUrl,
				promptVariantIndex: userPromptIdx,
			}];
		}
		pendingVariants.set(allVariants);
		pendingPromptIndex.set(userPromptIdx);

		const ok = await truncateMessagesFrom(idx);
		if (!ok) { pendingVariants.set([]); return; }
		messages.update((m) => m.slice(0, idx));
		send(userMsg.content, userMsg.imageUrl, userMsg.videoFrames, userMsg.videoUrl, { regenerate: true });
	}

	function startEdit() {
		editText = message.content;
		editing = true;
	}

	function cancelEdit() {
		editing = false;
		editText = '';
	}

	async function saveEdit() {
		const msgs = get(messages);
		const idx = msgs.findIndex((m) => m.id === message.id);
		if (idx < 0) return;
		const text = editText.trim();
		if (!text) return;

		// Preserve assistant response variants if there's a response after this message
		const assistantMsg = msgs[idx + 1];
		// Build user message variants (old prompts + new edit)
		let userVariants: MessageVariant[];
		if (message.variants && message.variants.length > 0) {
			userVariants = [...message.variants, { content: text, thinking: '', timestamp: new Date().toISOString() }];
		} else {
			userVariants = [
				{ content: message.content, thinking: '', timestamp: message.timestamp },
				{ content: text, thinking: '', timestamp: new Date().toISOString() },
			];
		}
		const newPromptIdx = userVariants.length - 1;

		if (assistantMsg && assistantMsg.role === 'assistant') {
			let allVariants: MessageVariant[];
			if (assistantMsg.variants && assistantMsg.variants.length > 0) {
				allVariants = assistantMsg.variants.map((v) => ({
					...v,
					promptVariantIndex: v.promptVariantIndex ?? 0,
				}));
			} else {
				allVariants = [{
					content: assistantMsg.content,
					thinking: assistantMsg.thinking,
					traceId: assistantMsg.traceId,
					timestamp: assistantMsg.timestamp,
					toolCalls: assistantMsg.toolCalls,
					audioUrl: assistantMsg.audioUrl,
					promptVariantIndex: message.variantIndex ?? 0,
				}];
			}
			pendingVariants.set(allVariants);
			pendingPromptIndex.set(newPromptIdx);
		}

		const ok = await truncateMessagesFrom(idx);
		if (!ok) { pendingVariants.set([]); return; }
		messages.update((m) => m.slice(0, idx));
		// Preserve original media attachments, pass user variants
		addUserMessage(text, message.imageUrl, message.videoFrames, message.videoUrl, userVariants, userVariants.length - 1);
		send(text, message.imageUrl, message.videoFrames, message.videoUrl);
		editing = false;
		editText = '';
	}

	function navigateVariant(delta: number) {
		if (!message.variants) return;
		const newIdx = (message.variantIndex ?? 0) + delta;
		if (newIdx < 0 || newIdx >= message.variants.length) return;
		setVariantIndex(message.id, newIdx);

		// Sync adjacent message using promptVariantIndex mapping
		const msgs = get(messages);
		const myPos = msgs.findIndex((m) => m.id === message.id);
		if (message.role === 'user') {
			// User navigated prompts → find last assistant variant for this prompt
			const next = msgs[myPos + 1];
			if (next?.variants) {
				let targetIdx = -1;
				for (let i = next.variants.length - 1; i >= 0; i--) {
					if ((next.variants[i].promptVariantIndex ?? 0) === newIdx) {
						targetIdx = i;
						break;
					}
				}
				if (targetIdx >= 0) setVariantIndex(next.id, targetIdx);
			}
		} else {
			// Assistant navigated responses → show matching prompt
			const prev = msgs[myPos - 1];
			if (prev?.variants) {
				const variant = message.variants[newIdx];
				const promptIdx = variant?.promptVariantIndex ?? 0;
				if (promptIdx < prev.variants.length) {
					setVariantIndex(prev.id, promptIdx);
				}
			}
		}
	}

	function handleEditKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			cancelEdit();
		}
	}

	function formatTime(ts: string): string {
		try {
			return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		} catch {
			return '';
		}
	}
</script>

{#if message.role === 'user'}
	<!-- User message: right-aligned, accent tinted -->
	<div class="flex justify-end mb-5 group">
		<div class="max-w-[75%]">
			<div class="bg-user-msg rounded-2xl rounded-br-md px-4 py-2.5">
				{#if message.videoUrl}
					<video
						src={message.videoUrl}
						controls
						class="max-w-full max-h-64 rounded-lg mb-2"
					>
						<track kind="captions" />
					</video>
				{:else if message.imageUrl}
					<img
						src={message.imageUrl}
						alt="Uploaded image"
						class="max-w-full max-h-64 rounded-lg mb-2"
					/>
				{/if}
				{#if editing}
					<textarea
						bind:value={editText}
						onkeydown={handleEditKeydown}
						class="w-full bg-bg-primary border border-border/60 rounded-lg px-3 py-2 text-text-primary text-[15px] leading-[1.5] resize-none focus:outline-none focus:border-accent/40 min-h-[60px]"
						rows="3"
					></textarea>
					<div class="flex gap-2 mt-2">
						<button onclick={saveEdit} class="px-3 py-1 bg-accent text-white text-xs rounded-lg hover:bg-accent-dim transition-colors">Save</button>
						<button onclick={cancelEdit} class="px-3 py-1 bg-bg-tertiary text-text-secondary text-xs rounded-lg hover:bg-bg-hover transition-colors">Cancel</button>
					</div>
				{:else}
					<div class="prose-chat text-[15px]">
						{@html renderedHtml}
					</div>
				{/if}
			</div>
			{#if !editing && !$generating}
				<div class="flex justify-end items-center gap-3 mt-1 opacity-0 group-hover:opacity-100 transition-opacity">
					{#if message.variants && message.variants.length > 1}
						<div class="flex items-center gap-1 text-xs text-text-dim">
							<button
								onclick={() => navigateVariant(-1)}
								disabled={(message.variantIndex ?? 0) <= 0}
								class="hover:text-text-secondary transition-colors disabled:opacity-30"
								aria-label="Previous edit"
							>
								<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.75 19.5L8.25 12l7.5-7.5" />
								</svg>
							</button>
							<span class="font-mono tabular-nums">{(message.variantIndex ?? 0) + 1}/{message.variants.length}</span>
							<button
								onclick={() => navigateVariant(1)}
								disabled={(message.variantIndex ?? 0) >= message.variants.length - 1}
								class="hover:text-text-secondary transition-colors disabled:opacity-30"
								aria-label="Next edit"
							>
								<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
								</svg>
							</button>
						</div>
					{/if}
					<button
						onclick={startEdit}
						class="text-xs text-text-dim hover:text-text-secondary transition-colors flex items-center gap-1"
					>
						<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L10.582 16.07a4.5 4.5 0 01-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 011.13-1.897l8.932-8.931zm0 0L19.5 7.125" />
						</svg>
						Edit
					</button>
				</div>
			{/if}
		</div>
	</div>
{:else}
	<!-- Assistant message: full-width, no bubble -->
	<div class="mb-6 group">
		{#if displayThinking}
			<ThinkingBlock content={displayThinking} />
		{/if}

		{#if displayToolCalls?.length}
			{#each displayToolCalls as tc, i (i)}
				<ToolCallBlock tool={tc.tool} status={tc.status} result={tc.result} />
			{/each}
		{/if}

		<div class="prose-chat" use:highlightCode={renderedHtml}>
			{@html renderedHtml}
		</div>

		{#if displayAudioUrl}
			<div class="mt-3">
				<audio controls src={displayAudioUrl} class="w-full h-9 rounded-lg" aria-label="TTS audio">
					<track kind="captions" src="" default />
				</audio>
				{#if message.ttsInfo}
					<p class="text-xs text-text-dim mt-1">{message.ttsInfo}</p>
				{/if}
			</div>
		{/if}

		<!-- Actions row: visible on hover -->
		<div class="flex items-center gap-3 mt-1.5 opacity-0 group-hover:opacity-100 transition-opacity">
			<button
				onclick={copyMessage}
				class="text-xs text-text-dim hover:text-text-secondary transition-colors flex items-center gap-1"
			>
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
				</svg>
				Copy
			</button>
			{#if isLastAssistant && !$generating}
				<button
					onclick={regenerate}
					class="text-xs text-text-dim hover:text-text-secondary transition-colors flex items-center gap-1"
				>
					<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182" />
					</svg>
					Regenerate
				</button>
			{/if}
			{#if message.variants && message.variants.length > 1}
				<div class="flex items-center gap-1 text-xs text-text-dim">
					<button
						onclick={() => navigateVariant(-1)}
						disabled={(message.variantIndex ?? 0) <= 0}
						class="hover:text-text-secondary transition-colors disabled:opacity-30"
						aria-label="Previous response"
					>
						<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.75 19.5L8.25 12l7.5-7.5" />
						</svg>
					</button>
					<span class="font-mono tabular-nums">{(message.variantIndex ?? 0) + 1}/{message.variants.length}</span>
					<button
						onclick={() => navigateVariant(1)}
						disabled={(message.variantIndex ?? 0) >= message.variants.length - 1}
						class="hover:text-text-secondary transition-colors disabled:opacity-30"
						aria-label="Next response"
					>
						<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
						</svg>
					</button>
				</div>
			{/if}
			<span class="text-[11px] text-text-dim font-mono">
				{formatTime(activeVariant?.timestamp ?? message.timestamp)}
			</span>
			{#if (activeVariant?.traceId ?? message.traceId)}
				<span class="text-[11px] text-text-dim font-mono">{activeVariant?.traceId ?? message.traceId}</span>
			{/if}
		</div>
	</div>
{/if}
