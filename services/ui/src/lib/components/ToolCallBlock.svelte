<script lang="ts">
	import { toast } from '$lib/stores/toast';

	let { tool, status, result }: { tool: string; status: string; result?: string } = $props();
	let expanded = $state(false);
	let copied = $state(false);

	const toolLabels: Record<string, { running: string; done: string }> = {
		web_search: { running: 'Searching the web...', done: 'Web search complete' },
		read_memory: { running: 'Reading memory...', done: 'Memory retrieved' },
		write_memory: { running: 'Saving to memory...', done: 'Memory saved' },
		list_memories: { running: 'Checking memories...', done: 'Memories checked' },
		run_code: { running: 'Running code...', done: 'Code execution complete' },
		generate_document: { running: 'Generating document...', done: 'Document ready' },
		// Future tools — add as capabilities come online
		generate_image: { running: 'Generating image...', done: 'Image generated' },
		generate_video: { running: 'Generating video...', done: 'Video generated' },
		generate_music: { running: 'Composing music...', done: 'Music generated' },
		edit_image: { running: 'Editing image...', done: 'Image edited' },
		analyze_image: { running: 'Analyzing image...', done: 'Analysis complete' },
	};

	function isMediaUrl(r: string, ...exts: string[]): boolean {
		return r.startsWith('/api/media/') && exts.some(e => r.endsWith(e));
	}

	let label = $derived(
		status === 'done'
			? (toolLabels[tool]?.done || `${tool} complete`)
			: (toolLabels[tool]?.running || `Running ${tool}...`)
	);

	async function copyResult() {
		if (!result) return;
		await navigator.clipboard.writeText(result);
		copied = true;
		toast('Copied to clipboard', 'success');
		setTimeout(() => { copied = false; }, 1500);
	}
</script>

<div class="mb-2 rounded border border-border bg-bg-tertiary/50">
	<button
		onclick={() => { if (result) expanded = !expanded; }}
		aria-expanded={expanded}
		aria-label={tool}
		class="flex items-center gap-2 w-full px-3 py-1.5 text-xs text-text-secondary hover:text-text-primary transition-colors"
	>
		{#if status !== 'done'}
			<svg class="w-3.5 h-3.5 text-accent animate-spin" fill="none" viewBox="0 0 24 24">
				<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
				<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
			</svg>
		{:else}
			<svg class="w-3.5 h-3.5 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
			</svg>
		{/if}
		<span>{label}</span>
		{#if result}
			<svg
				class="w-3 h-3 ml-auto transition-transform {expanded ? 'rotate-180' : ''}"
				fill="none"
				stroke="currentColor"
				viewBox="0 0 24 24"
			>
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
			</svg>
		{/if}
	</button>

	{#if expanded && result}
		<div class="relative border-t border-border/50">
			<button
				onclick={copyResult}
				class="absolute top-1.5 right-2 p-1 rounded text-text-dim hover:text-text-primary hover:bg-bg-hover/50 transition-colors z-10"
				aria-label="Copy output"
			>
				{#if copied}
					<svg class="w-3.5 h-3.5 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
					</svg>
				{:else}
					<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9.75a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184" />
					</svg>
				{/if}
			</button>
			{#if isMediaUrl(result, '.png', '.jpg', '.webp')}
				<div class="px-3 py-2">
					<img src={result} alt="Generated image" class="max-w-full rounded-lg" />
				</div>
			{:else if isMediaUrl(result, '.mp4', '.webm')}
				<div class="px-3 py-2">
					<video controls src={result} class="max-w-full rounded-lg">
						<track kind="captions" />
					</video>
				</div>
			{:else if isMediaUrl(result, '.wav', '.mp3')}
				<div class="px-3 py-2">
					<audio controls src={result} class="w-full">
						<track kind="captions" />
					</audio>
				</div>
			{:else}
				<div class="px-3 pr-8 pb-2 pt-1.5 text-xs text-text-secondary font-mono whitespace-pre-wrap leading-relaxed max-h-48 overflow-y-auto">
					{result}
				</div>
			{/if}
		</div>
	{/if}
</div>
