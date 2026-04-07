<script lang="ts">
	import { tick } from 'svelte';
	import { marked } from 'marked';
	import { sanitize } from '$lib/utils/sanitize';
	import { trackerChatOpen } from '$lib/stores/tracker';
	import ThinkingBlock from '$lib/components/ThinkingBlock.svelte';
	import {
		trackerMessages,
		trackerGenerating,
		trackerStreamingContent,
		trackerStreamingThinking,
		sendTrackerMessage,
	} from '$lib/ws/tracker-client';

	let inputText = $state('');
	let messagesEl: HTMLDivElement | undefined = $state();

	marked.setOptions({ breaks: true, gfm: true });

	function renderMarkdown(text: string): string {
		try {
			return sanitize(marked.parse(text) as string);
		} catch {
			return text;
		}
	}

	function handleSend() {
		if (!inputText.trim() || $trackerGenerating) return;
		sendTrackerMessage(inputText.trim());
		inputText = '';
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			handleSend();
		}
	}

	// Auto-scroll on new messages
	$effect(() => {
		$trackerMessages;
		$trackerStreamingContent;
		tick().then(() => {
			if (messagesEl) {
				messagesEl.scrollTop = messagesEl.scrollHeight;
			}
		});
	});
</script>

<div class="flex flex-col h-full">
	<!-- Header -->
	<div class="flex items-center justify-between px-4 py-3 border-b border-border/40 shrink-0">
		<div class="flex items-center gap-2">
			<svg class="w-4 h-4 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
			</svg>
			<span class="text-sm font-semibold text-text-primary">Tracker Chat</span>
		</div>
		<button
			onclick={() => trackerChatOpen.set(false)}
			class="text-text-dim hover:text-text-secondary transition-colors"
			aria-label="Close chat"
		>
			<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
			</svg>
		</button>
	</div>

	<!-- Messages -->
	<div bind:this={messagesEl} class="flex-1 overflow-y-auto p-4 space-y-3">
		{#each $trackerMessages as msg (msg.id)}
			<div class="flex flex-col gap-1 {msg.role === 'user' ? 'items-end' : 'items-start'}">
				{#if msg.role === 'user'}
					<div class="bg-user-msg text-text-primary text-sm rounded-lg px-3 py-2 max-w-[90%]">
						{msg.content}
					</div>
				{:else}
					{#if msg.thinking}
						<div class="max-w-[90%]">
							<ThinkingBlock content={msg.thinking} />
						</div>
					{/if}
					{#if msg.toolCalls}
						{#each msg.toolCalls as tc}
							<div class="flex items-center gap-1.5 px-2.5 py-1.5 rounded-md text-[11px] max-w-[90%]
								{tc.status === 'done' ? 'bg-success/10 text-success' : 'bg-accent/10 text-accent'}">
								{#if tc.status === 'done'}
									<svg class="w-3 h-3 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
									</svg>
								{:else}
									<span class="w-1.5 h-1.5 rounded-full bg-current animate-pulse shrink-0"></span>
								{/if}
								<span class="font-medium">{tc.tool}</span>
							</div>
						{/each}
					{/if}
					<div class="prose-sm text-text-primary text-sm max-w-[90%] [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1 [&_code]:bg-code-bg [&_code]:px-1 [&_code]:rounded [&_code]:text-xs">
						{@html renderMarkdown(msg.content)}
					</div>
				{/if}
			</div>
		{/each}

		<!-- Streaming content -->
		{#if $trackerGenerating}
			<div class="flex flex-col gap-1 items-start">
				{#if $trackerStreamingThinking}
					<div class="max-w-[90%]">
						<ThinkingBlock content={$trackerStreamingThinking} streaming={true} />
					</div>
				{/if}
				{#if $trackerStreamingContent}
					<div class="prose-sm text-text-primary text-sm max-w-[90%] [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1 [&_code]:bg-code-bg [&_code]:px-1 [&_code]:rounded [&_code]:text-xs">
						{@html renderMarkdown($trackerStreamingContent)}
					</div>
				{:else}
					<div class="flex gap-1 px-2 py-2">
						<span class="w-1.5 h-1.5 rounded-full bg-text-dim animate-pulse"></span>
						<span class="w-1.5 h-1.5 rounded-full bg-text-dim animate-pulse" style="animation-delay: 0.15s"></span>
						<span class="w-1.5 h-1.5 rounded-full bg-text-dim animate-pulse" style="animation-delay: 0.3s"></span>
					</div>
				{/if}
			</div>
		{/if}
	</div>

	<!-- Input -->
	<div class="border-t border-border/40 p-3 shrink-0">
		<div class="flex items-end gap-2">
			<textarea
				bind:value={inputText}
				onkeydown={handleKeydown}
				placeholder="Ask about tasks, notes..."
				rows={1}
				class="flex-1 bg-bg-tertiary text-text-primary text-sm rounded-lg px-3 py-2 border border-border/40 outline-none focus:border-accent/60 resize-none max-h-24"
			></textarea>
			<button
				onclick={handleSend}
				disabled={!inputText.trim() || $trackerGenerating}
				class="p-2 rounded-lg transition-colors flex-shrink-0
					{inputText.trim() && !$trackerGenerating ? 'bg-accent text-bg-primary hover:bg-accent-dim' : 'bg-bg-tertiary text-text-dim cursor-not-allowed'}"
				aria-label="Send"
			>
				<svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
					<path d="M3.478 2.404a.75.75 0 0 0-.926.941l2.432 7.905H13.5a.75.75 0 0 1 0 1.5H4.984l-2.432 7.905a.75.75 0 0 0 .926.94 60.519 60.519 0 0 0 18.445-8.986.75.75 0 0 0 0-1.218A60.517 60.517 0 0 0 3.478 2.404Z" />
				</svg>
			</button>
		</div>
	</div>
</div>
