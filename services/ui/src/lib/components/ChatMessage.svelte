<script lang="ts">
	import { marked } from 'marked';
	import ThinkingBlock from './ThinkingBlock.svelte';
	import CodeBlock from './CodeBlock.svelte';
	import type { Message } from '$lib/stores/chat';

	let { message }: { message: Message } = $props();
	let copied = $state(false);

	// Configure marked for security
	marked.setOptions({
		breaks: true,
		gfm: true,
	});

	// Custom renderer to extract code blocks for our component
	let renderedHtml = $derived(() => {
		try {
			return marked.parse(message.content) as string;
		} catch {
			return message.content;
		}
	});

	async function copyMessage() {
		await navigator.clipboard.writeText(message.content);
		copied = true;
		setTimeout(() => (copied = false), 2000);
	}

	function formatTime(ts: string): string {
		try {
			return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		} catch {
			return '';
		}
	}
</script>

<div class="flex {message.role === 'user' ? 'justify-end' : 'justify-start'} mb-4 group">
	<div
		class="max-w-[80%] {message.role === 'user'
			? 'bg-accent/20 border-accent/30'
			: 'bg-bg-secondary border-border'} border rounded-lg px-4 py-3"
	>
		{#if message.role === 'assistant' && message.thinking}
			<ThinkingBlock content={message.thinking} />
		{/if}

		<div class="prose prose-invert max-w-none text-text-primary text-[15px] leading-[1.7]">
			{@html renderedHtml()}
		</div>

		{#if message.audioUrl}
			<div class="mt-2">
				<audio controls src={message.audioUrl} class="w-full h-8">
					<track kind="captions" />
				</audio>
			</div>
		{/if}

		<div class="flex items-center justify-between mt-2 pt-1 border-t border-border/50">
			<span class="text-xs text-text-dim font-mono">
				{formatTime(message.timestamp)}
				{#if message.traceId}
					<span class="ml-2">{message.traceId}</span>
				{/if}
			</span>
			<button
				onclick={copyMessage}
				class="text-xs text-text-dim hover:text-text-primary opacity-0 group-hover:opacity-100 transition-opacity"
			>
				{copied ? 'Copied' : 'Copy'}
			</button>
		</div>
	</div>
</div>
