<script lang="ts">
	let { content, streaming = false }: { content: string; streaming?: boolean } = $props();
	let expanded = $state(false);

	// Auto-collapse when streaming stops
	$effect(() => {
		if (!streaming && content) expanded = false;
		if (streaming) expanded = true;
	});
</script>

{#if content}
	<div class="mb-2 rounded border border-thinking-border bg-thinking/30">
		<button
			onclick={() => (expanded = !expanded)}
			class="flex items-center gap-2 w-full px-3 py-1.5 text-xs text-text-secondary hover:text-text-primary transition-colors"
		>
			<svg class="w-3.5 h-3.5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
			</svg>
			<span>{streaming ? 'Thinking...' : 'Thought'}</span>
			{#if streaming}
				<span class="animate-pulse">|</span>
			{/if}
			<svg
				class="w-3 h-3 ml-auto transition-transform {expanded ? 'rotate-180' : ''}"
				fill="none"
				stroke="currentColor"
				viewBox="0 0 24 24"
			>
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
			</svg>
		</button>

		{#if expanded}
			<div class="px-3 pb-2 text-xs text-text-secondary font-mono whitespace-pre-wrap leading-relaxed max-h-64 overflow-y-auto">
				{content}
			</div>
		{/if}
	</div>
{/if}
