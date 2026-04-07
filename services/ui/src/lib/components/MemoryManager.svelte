<script lang="ts">
	import { memoryManagerOpen } from '$lib/stores/settings';
	import { focusTrap } from '$lib/actions/focusTrap';

	interface MemoryFile {
		filename: string;
		subdir: string;
		size: number;
		modified: number;
	}

	let memories = $state<MemoryFile[]>([]);
	let filter = $state('all');
	let selectedMemory = $state<{ filename: string; subdir: string; content: string } | null>(null);
	let showAddForm = $state(false);
	let newFilename = $state('');
	let newSubdir = $state('facts');
	let newContent = $state('');
	let saving = $state(false);
	let error = $state('');
	let confirmClear = $state(false);

	async function loadMemories() {
		try {
			const resp = await fetch('/api/memory/list');
			if (resp.ok) memories = await resp.json();
		} catch {
			memories = [];
		}
	}

	$effect(() => {
		if ($memoryManagerOpen) {
			loadMemories();
			selectedMemory = null;
			showAddForm = false;
			confirmClear = false;
		}
	});

	let filtered = $derived(
		filter === 'all' ? memories : memories.filter((m) => m.subdir === filter)
	);

	async function readMemory(m: MemoryFile) {
		try {
			const resp = await fetch(`/api/memory/read?filename=${encodeURIComponent(m.filename)}&subdir=${encodeURIComponent(m.subdir)}`);
			if (resp.ok) {
				const data = await resp.json();
				selectedMemory = data;
			}
		} catch {
			error = 'Failed to read memory.';
			setTimeout(() => (error = ''), 5000);
		}
	}

	async function deleteMemory(m: MemoryFile) {
		try {
			await fetch(`/api/memory/${m.subdir}/${m.filename}`, { method: 'DELETE' });
			memories = memories.filter((x) => !(x.filename === m.filename && x.subdir === m.subdir));
			if (selectedMemory?.filename === m.filename && selectedMemory?.subdir === m.subdir) {
				selectedMemory = null;
			}
		} catch {
			error = 'Failed to delete memory.';
			setTimeout(() => (error = ''), 5000);
		}
	}

	async function addMemory() {
		if (!newFilename.trim() || !newContent.trim()) return;
		saving = true;
		try {
			const formData = new FormData();
			let fname = newFilename.trim();
			if (!fname.endsWith('.txt')) fname += '.txt';
			formData.append('filename', fname);
			formData.append('content', newContent.trim());
			formData.append('subdir', newSubdir);
			const resp = await fetch('/api/memory/write', { method: 'POST', body: formData });
			if (resp.ok) {
				showAddForm = false;
				newFilename = '';
				newContent = '';
				await loadMemories();
			} else {
				error = 'Failed to save memory.';
				setTimeout(() => (error = ''), 5000);
			}
		} catch {
			error = 'Failed to save memory.';
			setTimeout(() => (error = ''), 5000);
		} finally {
			saving = false;
		}
	}

	async function clearAll() {
		try {
			await fetch('/api/memory/clear', { method: 'DELETE' });
			memories = [];
			selectedMemory = null;
			confirmClear = false;
		} catch {
			error = 'Failed to clear memories.';
			setTimeout(() => (error = ''), 5000);
		}
	}

	function close() {
		memoryManagerOpen.set(false);
	}

	function formatDate(ts: number): string {
		return new Date(ts * 1000).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
	}
</script>

{#if $memoryManagerOpen}
	<div
		class="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center"
		role="dialog"
		aria-label="Memory Manager"
		onkeydown={(e) => { if (e.key === 'Escape') close(); }}
		use:focusTrap
	>
		<button class="absolute inset-0 cursor-default" onclick={close} aria-label="Close memory manager"></button>
		<div class="relative bg-bg-secondary border border-border/60 rounded-2xl w-full max-w-lg mx-4 shadow-2xl max-h-[85vh] overflow-y-auto">
			<!-- Header -->
			<div class="flex items-center justify-between p-5 border-b border-border/40">
				<div class="flex items-center gap-2">
					<svg class="w-5 h-5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M20.25 6.375c0 2.278-3.694 4.125-8.25 4.125S3.75 8.653 3.75 6.375m16.5 0c0-2.278-3.694-4.125-8.25-4.125S3.75 4.097 3.75 6.375m16.5 0v11.25c0 2.278-3.694 4.125-8.25 4.125s-8.25-1.847-8.25-4.125V6.375m16.5 0v3.75m-16.5-3.75v3.75m16.5 0v3.75C20.25 16.153 16.556 18 12 18s-8.25-1.847-8.25-4.125v-3.75m16.5 0c0 2.278-3.694 4.125-8.25 4.125s-8.25-1.847-8.25-4.125" />
					</svg>
					<h2 class="text-base font-semibold">Memory Manager</h2>
				</div>
				<button
					onclick={close}
					class="text-text-dim hover:text-text-secondary transition-colors p-1"
					aria-label="Close"
				>
					<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M6 18L18 6M6 6l12 12" />
					</svg>
				</button>
			</div>

			<div class="p-5 space-y-4">
				<!-- Toolbar: filter tabs + add button -->
				<div class="flex items-center gap-2 flex-wrap">
					{#each ['all', 'facts', 'notes', 'conversations'] as tab}
						<button
							onclick={() => filter = tab}
							class="px-2.5 py-1 rounded-full text-xs font-medium transition-all {filter === tab
								? 'bg-accent text-white'
								: 'bg-bg-tertiary/50 text-text-dim border border-border/40 hover:border-border'}"
						>
							{tab.charAt(0).toUpperCase() + tab.slice(1)}
						</button>
					{/each}
					<span class="flex-1"></span>
					<button
						onclick={() => showAddForm = !showAddForm}
						class="text-xs text-accent hover:text-accent-dim transition-colors"
					>
						+ Add Memory
					</button>
				</div>

				<!-- Add form -->
				{#if showAddForm}
					<div class="bg-bg-tertiary/50 rounded-lg p-3 space-y-2">
						<div class="flex gap-2">
							<input
								type="text"
								bind:value={newFilename}
								placeholder="filename (e.g. favorite_color)"
								class="flex-1 bg-bg-primary border border-border/40 rounded-lg px-3 py-1.5 text-sm text-text-primary placeholder:text-text-dim focus:outline-none focus:border-accent/40"
							/>
							<select
								bind:value={newSubdir}
								class="bg-bg-primary border border-border/40 rounded-lg px-2 py-1.5 text-sm text-text-primary focus:outline-none focus:border-accent/40"
							>
								<option value="facts">Facts</option>
								<option value="notes">Notes</option>
								<option value="conversations">Conversations</option>
							</select>
						</div>
						<textarea
							bind:value={newContent}
							placeholder="Memory content..."
							rows="3"
							class="w-full resize-none bg-bg-primary border border-border/40 rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-dim focus:outline-none focus:border-accent/40"
						></textarea>
						<div class="flex gap-2">
							<button
								onclick={addMemory}
								disabled={saving || !newFilename.trim() || !newContent.trim()}
								class="flex-1 py-1.5 rounded-lg text-xs font-medium transition-all {newFilename.trim() && newContent.trim() && !saving
									? 'bg-accent text-white hover:bg-accent-dim'
									: 'bg-bg-tertiary text-text-dim cursor-not-allowed'}"
							>
								{saving ? 'Saving...' : 'Save'}
							</button>
							<button
								onclick={() => showAddForm = false}
								class="px-3 py-1.5 rounded-lg text-xs text-text-dim hover:text-text-secondary bg-bg-primary border border-border/40"
							>
								Cancel
							</button>
						</div>
					</div>
				{/if}

				<!-- Error -->
				{#if error}
					<div class="px-3 py-2 bg-error/10 border border-error/20 rounded-lg text-xs text-error">{error}</div>
				{/if}

				<!-- Memory list -->
				<div class="space-y-1">
					{#each filtered as m (`${m.subdir}/${m.filename}`)}
						<div class="flex items-center gap-1">
							<button
								onclick={() => readMemory(m)}
								class="flex-1 flex items-center gap-2 px-3 py-2 rounded-lg text-xs text-left transition-all {selectedMemory?.filename === m.filename && selectedMemory?.subdir === m.subdir
									? 'bg-accent/15 border border-accent/30 text-text-primary'
									: 'bg-bg-tertiary/30 border border-transparent text-text-secondary hover:bg-bg-tertiary/50'}"
							>
								<span class="text-text-dim font-mono">{m.subdir}/</span>
								<span class="flex-1 truncate">{m.filename}</span>
								<span class="text-text-dim text-[11px]">{m.modified ? formatDate(m.modified) : ''}</span>
							</button>
							<button
								onclick={() => deleteMemory(m)}
								class="p-1.5 text-text-dim hover:text-error transition-colors rounded flex-shrink-0"
								aria-label="Delete memory"
							>
								<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
								</svg>
							</button>
						</div>
					{/each}
					{#if filtered.length === 0}
						<p class="text-xs text-text-dim text-center py-4">No memories saved yet.</p>
					{/if}
				</div>

				<!-- Selected memory content -->
				{#if selectedMemory}
					<div class="bg-bg-tertiary/50 rounded-lg p-3">
						<p class="text-xs text-text-dim mb-1">{selectedMemory.subdir}/{selectedMemory.filename}</p>
						<pre class="text-sm text-text-primary whitespace-pre-wrap font-mono leading-relaxed max-h-48 overflow-y-auto">{selectedMemory.content}</pre>
					</div>
				{/if}

				<!-- Clear all -->
				<div class="flex justify-end">
					{#if confirmClear}
						<div class="flex items-center gap-2">
							<span class="text-xs text-error">Delete all memories?</span>
							<button
								onclick={clearAll}
								class="px-3 py-1 rounded-lg text-xs font-medium bg-error text-white hover:bg-error/80 transition-colors"
							>
								Confirm
							</button>
							<button
								onclick={() => confirmClear = false}
								class="px-3 py-1 rounded-lg text-xs text-text-dim hover:text-text-secondary bg-bg-tertiary border border-border/40"
							>
								Cancel
							</button>
						</div>
					{:else}
						<button
							onclick={() => confirmClear = true}
							class="text-xs text-text-dim hover:text-error transition-colors"
							disabled={memories.length === 0}
						>
							Clear All
						</button>
					{/if}
				</div>
			</div>
		</div>
	</div>
{/if}
