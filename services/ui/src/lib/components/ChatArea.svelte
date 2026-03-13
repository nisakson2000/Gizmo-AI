<script lang="ts">
	import { messages, generating, streamingThinking, streamingContent } from '$lib/stores/chat';
	import ChatMessage from './ChatMessage.svelte';
	import ThinkingBlock from './ThinkingBlock.svelte';

	let chatContainer: HTMLDivElement;
	let userScrolled = $state(false);

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

	// Auto-scroll on new content
	$effect(() => {
		// Subscribe to these to trigger re-scroll
		$messages;
		$streamingContent;
		$streamingThinking;
		scrollToBottom();
	});
</script>

<div
	bind:this={chatContainer}
	onscroll={handleScroll}
	class="flex-1 overflow-y-auto px-4 py-6"
>
	{#if $messages.length === 0 && !$generating}
		<div class="flex flex-col items-center justify-center h-full text-center">
			<h1 class="text-4xl font-semibold text-text-primary mb-2">Gizmo</h1>
			<p class="text-text-secondary mb-6">Local AI assistant — no cloud, no limits</p>
			<div class="grid grid-cols-1 sm:grid-cols-2 gap-3 max-w-lg text-sm">
				<div class="bg-bg-secondary border border-border rounded-lg p-3 text-left">
					<span class="text-text-dim">Chat</span>
					<p class="text-text-secondary mt-1">Ask anything — coding, analysis, writing</p>
				</div>
				<div class="bg-bg-secondary border border-border rounded-lg p-3 text-left">
					<span class="text-text-dim">Search</span>
					<p class="text-text-secondary mt-1">"Search for the latest AI news"</p>
				</div>
				<div class="bg-bg-secondary border border-border rounded-lg p-3 text-left">
					<span class="text-text-dim">Remember</span>
					<p class="text-text-secondary mt-1">"Remember that my name is Nick"</p>
				</div>
				<div class="bg-bg-secondary border border-border rounded-lg p-3 text-left">
					<span class="text-text-dim">Think</span>
					<p class="text-text-secondary mt-1">Toggle thinking mode for complex problems</p>
				</div>
			</div>
		</div>
	{:else}
		<div class="max-w-4xl mx-auto">
			{#each $messages as message (message.id)}
				<ChatMessage {message} />
			{/each}

			{#if $generating}
				<div class="flex justify-start mb-4">
					<div class="max-w-[80%] bg-bg-secondary border border-border rounded-lg px-4 py-3">
						{#if $streamingThinking}
							<ThinkingBlock content={$streamingThinking} streaming={true} />
						{/if}
						{#if $streamingContent}
							<div class="prose prose-invert max-w-none text-text-primary text-[15px] leading-[1.7]">
								{@html $streamingContent}
							</div>
						{:else if !$streamingThinking}
							<div class="flex gap-1">
								<div class="w-2 h-2 bg-accent rounded-full animate-bounce" style="animation-delay: 0ms"></div>
								<div class="w-2 h-2 bg-accent rounded-full animate-bounce" style="animation-delay: 150ms"></div>
								<div class="w-2 h-2 bg-accent rounded-full animate-bounce" style="animation-delay: 300ms"></div>
							</div>
						{/if}
					</div>
				</div>
			{/if}
		</div>
	{/if}
</div>
