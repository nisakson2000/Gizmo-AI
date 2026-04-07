<script lang="ts">
	import { selectedNoteId, updateNote, deleteNote } from '$lib/stores/tracker';
	import type { Note } from '$lib/stores/tracker';

	let { note }: { note: Note } = $props();

	let isSelected = $derived($selectedNoteId === note.id);

	function togglePin(e: Event) {
		e.stopPropagation();
		updateNote(note.id, { pinned: !note.pinned });
	}

	function handleDelete(e: Event) {
		e.stopPropagation();
		deleteNote(note.id);
	}

	function formatDate(dateStr: string): string {
		const d = new Date(dateStr);
		const now = new Date();
		const diff = now.getTime() - d.getTime();
		const mins = Math.floor(diff / 60000);
		if (mins < 1) return 'Just now';
		if (mins < 60) return `${mins}m ago`;
		const hours = Math.floor(mins / 60);
		if (hours < 24) return `${hours}h ago`;
		const days = Math.floor(hours / 24);
		if (days < 7) return `${days}d ago`;
		return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
	}
</script>

<div
	onclick={() => selectedNoteId.set(isSelected ? null : note.id)}
	onkeydown={(e) => { if (e.key === 'Enter') selectedNoteId.set(isSelected ? null : note.id); }}
	role="button"
	tabindex="0"
	class="group mx-4 my-1.5 rounded-lg border transition-all cursor-pointer
		{isSelected
			? 'bg-bg-hover/80 border-accent/40 shadow-sm'
			: note.pinned
				? 'bg-accent/[0.03] border-accent/15 hover:border-accent/25 hover:shadow-sm'
				: 'border-border/20 hover:border-border/40 hover:shadow-sm'}"
>
	<div class="flex items-start gap-3 px-3.5 py-3">
		<!-- Pin indicator -->
		<div class="shrink-0 mt-0.5">
			{#if note.pinned}
				<svg class="w-3.5 h-3.5 text-accent" fill="currentColor" viewBox="0 0 24 24">
					<path d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
				</svg>
			{:else}
				<svg class="w-3.5 h-3.5 text-text-dim/30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
				</svg>
			{/if}
		</div>

		<!-- Content -->
		<div class="flex-1 min-w-0">
			<div class="flex items-center gap-2">
				<span class="text-sm text-text-primary font-medium truncate">{note.title}</span>
				<span class="shrink-0 text-[10px] text-text-dim/60">{formatDate(note.updated_at)}</span>
			</div>
			{#if note.content}
				<p class="text-xs text-text-dim mt-1 line-clamp-2 leading-relaxed">{note.content}</p>
			{/if}
			{#if note.tags.length > 0}
				<div class="flex gap-1 mt-2">
					{#each note.tags.slice(0, 4) as tag}
						<span class="px-1.5 py-0.5 text-[10px] rounded-full bg-bg-tertiary/80 text-text-dim">{tag}</span>
					{/each}
				</div>
			{/if}
		</div>

		<!-- Actions (hover) -->
		<div class="shrink-0 flex gap-1 opacity-0 group-hover:opacity-100 max-sm:opacity-40 transition-opacity">
			<button
				onclick={togglePin}
				class="p-1 text-text-dim hover:text-accent transition-colors rounded"
				title={note.pinned ? 'Unpin' : 'Pin'}
			>
				<svg class="w-3.5 h-3.5" fill={note.pinned ? 'currentColor' : 'none'} stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
				</svg>
			</button>
			<button
				onclick={handleDelete}
				class="p-1 text-text-dim hover:text-error transition-colors rounded"
				title="Delete"
			>
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
				</svg>
			</button>
		</div>
	</div>
</div>
