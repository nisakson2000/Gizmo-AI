<script lang="ts">
	import { onDestroy } from 'svelte';
	import { notes, selectedNoteId, updateNote, deleteNote } from '$lib/stores/tracker';
	import type { Note } from '$lib/stores/tracker';

	let note = $derived($notes.find((n) => n.id === $selectedNoteId) ?? null);

	// Local edit state
	let title = $state('');
	let content = $state('');
	let tagsList = $state<string[]>([]);
	let tagInput = $state('');
	let pinned = $state(false);
	let confirmDelete = $state(false);

	// Auto-save state
	let dirty = $state(false);
	let saveTimer: ReturnType<typeof setTimeout> | null = null;
	let saveStatus = $state<'idle' | 'saving' | 'saved'>('idle');
	let capturedNoteId = $state<string | null>(null);

	// Sync local state only when selected note ID changes
	let lastSyncedId = $state('');
	$effect(() => {
		const id = $selectedNoteId;
		if (id && id !== lastSyncedId && note) {
			lastSyncedId = id;
			capturedNoteId = id;
			title = note.title;
			content = note.content;
			tagsList = [...note.tags];
			tagInput = '';
			pinned = note.pinned;
			confirmDelete = false;
			dirty = false;
			saveStatus = 'idle';
			if (saveTimer) clearTimeout(saveTimer);
		}
	});

	$effect(() => {
		if (note) capturedNoteId = note.id;
	});

	function markDirty() {
		dirty = true;
		saveStatus = 'idle';
		if (saveTimer) clearTimeout(saveTimer);
		saveTimer = setTimeout(doSave, 800);
	}

	async function doSave() {
		if (!dirty || !note) return;
		saveStatus = 'saving';
		await updateNote(note.id, {
			title, content,
			tags: tagsList,
			pinned,
		});
		dirty = false;
		saveStatus = 'saved';
		setTimeout(() => { if (saveStatus === 'saved') saveStatus = 'idle'; }, 2000);
	}

	function close() {
		if (saveTimer) clearTimeout(saveTimer);
		if (dirty && capturedNoteId) {
			updateNote(capturedNoteId, { title, content, tags: tagsList, pinned });
		}
		selectedNoteId.set(null);
	}

	onDestroy(() => {
		if (saveTimer) clearTimeout(saveTimer);
		if (dirty && capturedNoteId) {
			updateNote(capturedNoteId, { title, content, tags: tagsList, pinned });
		}
	});

	async function handleDelete() {
		if (!confirmDelete) {
			confirmDelete = true;
			return;
		}
		if (!note) return;
		await deleteNote(note.id);
		selectedNoteId.set(null);
	}

	function removeTag(index: number) {
		tagsList = tagsList.filter((_, i) => i !== index);
		markDirty();
	}

	function handleTagKeydown(e: KeyboardEvent) {
		if ((e.key === 'Enter' || e.key === ',') && tagInput.trim()) {
			e.preventDefault();
			const trimmed = tagInput.trim();
			if (!tagsList.includes(trimmed)) {
				tagsList = [...tagsList, trimmed];
				markDirty();
			}
			tagInput = '';
		} else if (e.key === 'Backspace' && !tagInput && tagsList.length > 0) {
			tagsList = tagsList.slice(0, -1);
			markDirty();
		}
	}
</script>

{#if note}
	<div class="flex flex-col h-full">
		<!-- Header -->
		<div class="flex items-center justify-between px-5 py-3 border-b border-border/40 shrink-0">
			<h3 class="text-xs font-medium text-text-dim uppercase tracking-wider">Edit Note</h3>
			<div class="flex items-center gap-3">
				{#if saveStatus === 'saving'}
					<span class="text-[11px] text-text-dim">Saving...</span>
				{:else if saveStatus === 'saved'}
					<span class="text-[11px] text-success">Saved</span>
				{/if}
				<button onclick={close} class="text-text-dim hover:text-text-secondary transition-colors" aria-label="Close">
					<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
					</svg>
				</button>
			</div>
		</div>

		<div class="flex-1 flex flex-col overflow-y-auto p-5 space-y-4">
			<!-- Title (heading-style) -->
			<input
				bind:value={title}
				oninput={markDirty}
				placeholder="Note title"
				class="w-full bg-transparent text-text-primary text-lg font-semibold outline-none border-b border-transparent focus:border-border/40 pb-1 transition-colors shrink-0"
			/>

			<!-- Pin toggle button -->
			<button
				onclick={() => { pinned = !pinned; markDirty(); }}
				class="shrink-0 flex items-center gap-2 px-3 py-2 rounded-lg border transition-all self-start
					{pinned ? 'bg-accent/10 border-accent/30 text-accent' : 'border-border/20 text-text-dim hover:border-border/40'}"
			>
				<svg class="w-4 h-4" fill={pinned ? 'currentColor' : 'none'} stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
				</svg>
				<span class="text-xs font-medium">{pinned ? 'Pinned' : 'Pin this note'}</span>
			</button>

			<!-- Content -->
			<textarea
				bind:value={content}
				oninput={markDirty}
				placeholder="Write your note..."
				class="flex-1 min-h-[200px] bg-bg-tertiary/30 text-text-primary text-sm rounded-lg px-3 py-2.5 border border-border/20 outline-none focus:border-accent/40 resize-none font-mono transition-colors"
			></textarea>

			<!-- Tags (pills) -->
			<div class="shrink-0">
				<label class="block text-[11px] text-text-dim uppercase tracking-wider mb-2">Tags</label>
				<div class="flex flex-wrap gap-1.5 items-center bg-bg-tertiary/30 rounded-lg px-3 py-2 border border-border/20 focus-within:border-accent/40 transition-colors min-h-[36px]">
					{#each tagsList as tag, i}
						<span class="bg-accent/10 text-accent text-xs rounded-full px-2.5 py-0.5 flex items-center gap-1">
							{tag}
							<button onclick={() => removeTag(i)} class="hover:text-error transition-colors text-[10px] leading-none">&times;</button>
						</span>
					{/each}
					<input
						bind:value={tagInput}
						onkeydown={handleTagKeydown}
						placeholder={tagsList.length === 0 ? 'Add tags...' : ''}
						class="flex-1 bg-transparent text-text-primary text-sm outline-none min-w-[60px]"
					/>
				</div>
			</div>
		</div>

		<!-- Bottom bar -->
		<div class="flex items-center justify-between px-5 py-3 border-t border-border/40 shrink-0">
			<button
				onclick={handleDelete}
				class="px-3 py-1.5 text-xs rounded-md transition-colors
					{confirmDelete ? 'bg-error text-white' : 'text-error hover:bg-error/10'}"
			>
				{confirmDelete ? 'Confirm Delete' : 'Delete'}
			</button>
		</div>
	</div>
{/if}
