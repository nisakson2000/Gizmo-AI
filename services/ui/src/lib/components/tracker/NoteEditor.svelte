<script lang="ts">
	import { notes, selectedNoteId, updateNote, deleteNote } from '$lib/stores/tracker';
	import type { Note } from '$lib/stores/tracker';

	let note = $derived($notes.find((n) => n.id === $selectedNoteId) ?? null);

	// Local edit state
	let title = $state('');
	let content = $state('');
	let tagsText = $state('');
	let pinned = $state(false);
	let confirmDelete = $state(false);

	// Sync local state only when selected note ID changes (not on every store update)
	let lastSyncedId = $state('');
	$effect(() => {
		const id = $selectedNoteId;
		if (id && id !== lastSyncedId && note) {
			lastSyncedId = id;
			title = note.title;
			content = note.content;
			tagsText = note.tags.join(', ');
			pinned = note.pinned;
			confirmDelete = false;
		}
	});

	function close() {
		selectedNoteId.set(null);
	}

	async function save() {
		if (!note) return;
		await updateNote(note.id, {
			title,
			content,
			tags: tagsText.split(',').map((t) => t.trim()).filter(Boolean),
			pinned,
		});
	}

	async function handleDelete() {
		if (!confirmDelete) {
			confirmDelete = true;
			return;
		}
		if (!note) return;
		await deleteNote(note.id);
		close();
	}
</script>

{#if note}
	<div class="w-96 bg-bg-secondary border-l border-border/40 flex flex-col h-full overflow-y-auto">
		<!-- Header -->
		<div class="flex items-center justify-between px-4 py-3 border-b border-border/40">
			<h3 class="text-sm font-medium text-text-primary">Edit Note</h3>
			<button
				onclick={close}
				class="text-text-dim hover:text-text-secondary transition-colors"
				aria-label="Close"
			>
				<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
				</svg>
			</button>
		</div>

		<div class="flex-1 p-4 space-y-4">
			<!-- Title -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Title</label>
				<input
					bind:value={title}
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none focus:border-accent/60"
				/>
			</div>

			<!-- Content -->
			<div class="flex-1">
				<label class="block text-xs text-text-dim mb-1">Content</label>
				<textarea
					bind:value={content}
					rows={16}
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none focus:border-accent/60 resize-none font-mono"
				></textarea>
			</div>

			<!-- Tags -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Tags (comma-separated)</label>
				<input
					bind:value={tagsText}
					placeholder="meeting, idea, reference"
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none focus:border-accent/60"
				/>
			</div>

			<!-- Pin toggle -->
			<div class="flex items-center gap-2">
				<button
					onclick={() => pinned = !pinned}
					class="flex items-center gap-2 text-sm transition-colors
						{pinned ? 'text-accent' : 'text-text-dim hover:text-text-secondary'}"
				>
					<span class="w-4 h-4 rounded border flex items-center justify-center
						{pinned ? 'bg-accent border-accent' : 'border-text-dim'}">
						{#if pinned}
							<svg class="w-3 h-3 text-bg-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
							</svg>
						{/if}
					</span>
					Pin this note
				</button>
			</div>
		</div>

		<!-- Action buttons -->
		<div class="flex items-center gap-2 px-4 py-3 border-t border-border/40">
			<button
				onclick={save}
				class="flex-1 bg-accent text-bg-primary text-sm font-medium rounded px-3 py-2 hover:bg-accent-dim transition-colors"
			>
				Save
			</button>
			<button
				onclick={handleDelete}
				class="px-3 py-2 text-sm rounded transition-colors
					{confirmDelete ? 'bg-error text-white' : 'text-error hover:bg-error/10'}"
			>
				{confirmDelete ? 'Confirm Delete' : 'Delete'}
			</button>
		</div>
	</div>
{/if}
