<script lang="ts">
	import { notes } from '$lib/stores/tracker';
	import NoteItem from './NoteItem.svelte';

	let searchQuery = $state('');
	let pinnedOnly = $state(false);

	let filteredNotes = $derived.by(() => {
		let result = [...$notes];
		if (searchQuery.trim()) {
			const q = searchQuery.toLowerCase();
			result = result.filter(
				(n) =>
					n.title.toLowerCase().includes(q) ||
					n.content.toLowerCase().includes(q) ||
					n.tags.some((t) => t.toLowerCase().includes(q))
			);
		}
		if (pinnedOnly) result = result.filter((n) => n.pinned);
		result.sort((a, b) => {
			if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
			return b.updated_at.localeCompare(a.updated_at);
		});
		return result;
	});

	let pinnedCount = $derived($notes.filter(n => n.pinned).length);
</script>

<!-- Search / filter bar -->
<div class="flex items-center gap-2 mx-4 mt-2 mb-1 px-3 py-2 bg-bg-tertiary/40 rounded-lg border border-border/20">
	<svg class="w-3.5 h-3.5 text-text-dim/50 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
		<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
	</svg>
	<input
		bind:value={searchQuery}
		placeholder="Search notes..."
		class="flex-1 bg-transparent text-text-primary text-sm outline-none placeholder:text-text-dim/40"
	/>
	<button
		onclick={() => pinnedOnly = !pinnedOnly}
		class="flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-medium rounded-md transition-all
			{pinnedOnly ? 'bg-accent/15 text-accent ring-1 ring-accent/30' : 'text-text-dim hover:bg-bg-hover hover:text-text-secondary'}"
		aria-label="Toggle pinned filter"
	>
		<svg class="w-3.5 h-3.5" fill={pinnedOnly ? 'currentColor' : 'none'} stroke="currentColor" viewBox="0 0 24 24">
			<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
		</svg>
		Pinned {#if pinnedCount > 0}<span class="text-[10px] opacity-60">({pinnedCount})</span>{/if}
	</button>
</div>

<!-- Note list -->
<div class="py-1">
	{#each filteredNotes as note (note.id)}
		<NoteItem {note} />
	{:else}
		<div class="flex flex-col items-center justify-center py-20 text-text-dim">
			<div class="w-16 h-16 rounded-2xl bg-bg-tertiary/50 border border-border/20 flex items-center justify-center mb-4">
				<svg class="w-7 h-7 opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
				</svg>
			</div>
			<p class="text-sm font-medium text-text-secondary">{searchQuery ? 'No matching notes' : 'No notes yet'}</p>
			<p class="text-xs mt-1.5 text-text-dim/50 max-w-[200px] text-center">
				{searchQuery ? 'Try a different search term' : 'Type a title above and press Enter to create your first note'}
			</p>
		</div>
	{/each}
</div>
