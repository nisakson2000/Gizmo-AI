<script lang="ts">
	import { modeEditorOpen, modes, refreshModes, type ModeInfo } from '$lib/stores/settings';
	import { focusTrap } from '$lib/actions/focusTrap';
	import { toast } from '$lib/stores/toast';

	interface ModeFull extends ModeInfo {
		system_prompt: string;
	}

	const BUILTIN = new Set(['chat', 'brainstorm', 'coder', 'research', 'planner', 'roleplay']);

	let selected = $state<string | null>(null);
	let detail = $state<ModeFull | null>(null);
	let editPrompt = $state('');
	let editLabel = $state('');
	let editDescription = $state('');
	let saving = $state(false);
	let creating = $state(false);
	let newName = $state('');
	let newLabel = $state('');
	let newDescription = $state('');
	let newPrompt = $state('');

	$effect(() => {
		if ($modeEditorOpen) {
			refreshModes();
			creating = false;
		}
	});

	async function selectMode(name: string) {
		creating = false;
		selected = name;
		try {
			const resp = await fetch(`/api/modes/${name}`);
			if (resp.ok) {
				detail = await resp.json();
				editPrompt = detail!.system_prompt;
				editLabel = detail!.label;
				editDescription = detail!.description;
			}
		} catch {
			toast('Failed to load mode', 'error');
		}
	}

	async function saveMode() {
		if (!detail) return;
		saving = true;
		try {
			const resp = await fetch(`/api/modes/${detail.name}`, {
				method: 'PUT',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					system_prompt: editPrompt,
					label: editLabel,
					description: editDescription,
				}),
			});
			if (resp.ok) {
				toast('Mode saved', 'success');
				detail.system_prompt = editPrompt;
				detail.label = editLabel;
				detail.description = editDescription;
				await refreshModes();
			} else {
				toast('Save failed', 'error');
			}
		} catch {
			toast('Save failed', 'error');
		}
		saving = false;
	}

	function resetMode() {
		if (detail) {
			editPrompt = detail.system_prompt;
			editLabel = detail.label;
			editDescription = detail.description;
		}
	}

	async function deleteMode() {
		if (!detail || BUILTIN.has(detail.name)) return;
		try {
			const resp = await fetch(`/api/modes/${detail.name}`, { method: 'DELETE' });
			if (resp.ok) {
				toast('Mode deleted', 'success');
				selected = null;
				detail = null;
				await refreshModes();
			} else {
				toast('Delete failed', 'error');
			}
		} catch {
			toast('Delete failed', 'error');
		}
	}

	function startCreate() {
		creating = true;
		selected = null;
		detail = null;
		newName = '';
		newLabel = '';
		newDescription = '';
		newPrompt = '';
	}

	async function createMode() {
		if (!newName.trim() || !newLabel.trim()) {
			toast('Name and label are required', 'error');
			return;
		}
		const slug = newName.trim().toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '');
		saving = true;
		try {
			const resp = await fetch('/api/modes', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({
					name: slug,
					label: newLabel.trim(),
					description: newDescription.trim(),
					system_prompt: newPrompt,
				}),
			});
			if (resp.ok) {
				toast('Mode created', 'success');
				creating = false;
				await refreshModes();
				await selectMode(slug);
			} else {
				const err = await resp.json().catch(() => null);
				toast(err?.detail || 'Create failed', 'error');
			}
		} catch {
			toast('Create failed', 'error');
		}
		saving = false;
	}

	let hasChanges = $derived(
		detail != null && (editPrompt !== detail.system_prompt || editLabel !== detail.label || editDescription !== detail.description)
	);
</script>

{#if $modeEditorOpen}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center"
		role="dialog"
		aria-label="Mode Editor"
		onkeydown={(e) => { if (e.key === 'Escape') modeEditorOpen.set(false); }}
		use:focusTrap
	>
		<button class="absolute inset-0 cursor-default" onclick={() => modeEditorOpen.set(false)} aria-label="Close mode editor"></button>
		<div class="relative bg-bg-secondary border border-border/60 rounded-2xl w-full max-w-3xl mx-4 max-h-[85vh] overflow-hidden shadow-2xl flex flex-col">
			<!-- Header -->
			<div class="flex items-center justify-between p-5 border-b border-border/40 flex-shrink-0">
				<h2 class="text-base font-semibold">Mode Editor</h2>
				<button
					onclick={() => modeEditorOpen.set(false)}
					class="text-text-dim hover:text-text-secondary transition-colors p-1"
					aria-label="Close"
				>
					<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M6 18L18 6M6 6l12 12" />
					</svg>
				</button>
			</div>

			<!-- Body: two-column layout -->
			<div class="flex flex-1 overflow-hidden">
				<!-- Left: mode list -->
				<div class="w-48 border-r border-border/40 overflow-y-auto flex-shrink-0 p-3 space-y-1">
					{#each $modes as m (m.name)}
						<button
							onclick={() => selectMode(m.name)}
							class="w-full text-left px-3 py-2 rounded-lg text-sm transition-all {selected === m.name
								? 'bg-accent/15 text-accent border border-accent/30'
								: 'text-text-secondary hover:bg-bg-tertiary/50 border border-transparent'}"
						>
							<span class="font-medium">{m.label}</span>
							{#if !BUILTIN.has(m.name)}
								<span class="text-[10px] text-text-dim ml-1">custom</span>
							{/if}
						</button>
					{/each}
					<button
						onclick={startCreate}
						class="w-full text-left px-3 py-2 rounded-lg text-sm text-accent hover:bg-accent/10 transition-all border border-dashed border-accent/30"
					>
						+ New Mode
					</button>
				</div>

				<!-- Right: editor -->
				<div class="flex-1 overflow-y-auto p-5">
					{#if creating}
						<div class="space-y-4">
							<div>
								<label class="block text-xs text-text-dim mb-1" for="new-name">Name <span class="text-text-dim">(lowercase, no spaces)</span></label>
								<input id="new-name" bind:value={newName} class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent/40" placeholder="my-mode" />
							</div>
							<div>
								<label class="block text-xs text-text-dim mb-1" for="new-label">Label</label>
								<input id="new-label" bind:value={newLabel} class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent/40" placeholder="My Mode" />
							</div>
							<div>
								<label class="block text-xs text-text-dim mb-1" for="new-desc">Description</label>
								<input id="new-desc" bind:value={newDescription} class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent/40" placeholder="What this mode does" />
							</div>
							<div>
								<label class="block text-xs text-text-dim mb-1" for="new-prompt">System Prompt</label>
								<textarea id="new-prompt" bind:value={newPrompt} rows="10" class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary font-mono focus:outline-none focus:border-accent/40 resize-y" placeholder="Behavioral instructions for this mode..."></textarea>
							</div>
							<div class="flex gap-2">
								<button onclick={createMode} disabled={saving} class="px-4 py-2 rounded-lg text-sm font-medium bg-accent text-white hover:bg-accent-dim transition-colors disabled:opacity-50">
									{saving ? 'Creating...' : 'Create Mode'}
								</button>
								<button onclick={() => (creating = false)} class="px-4 py-2 rounded-lg text-sm font-medium bg-bg-tertiary/50 text-text-secondary hover:bg-bg-tertiary transition-colors">
									Cancel
								</button>
							</div>
						</div>
					{:else if detail}
						<div class="space-y-4">
							<div class="flex gap-4">
								<div class="flex-1">
									<label class="block text-xs text-text-dim mb-1" for="edit-label">Label</label>
									<input id="edit-label" bind:value={editLabel} class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent/40" />
								</div>
								<div class="flex-1">
									<label class="block text-xs text-text-dim mb-1" for="edit-desc">Description</label>
									<input id="edit-desc" bind:value={editDescription} class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent/40" />
								</div>
							</div>
							<div>
								<label class="block text-xs text-text-dim mb-1" for="edit-prompt">System Prompt {detail.name === 'chat' ? '(empty = default behavior)' : ''}</label>
								<textarea id="edit-prompt" bind:value={editPrompt} rows="14" class="w-full bg-bg-primary border border-border/50 rounded-lg px-3 py-2 text-sm text-text-primary font-mono focus:outline-none focus:border-accent/40 resize-y" placeholder="Behavioral instructions..."></textarea>
							</div>
							<div class="flex items-center gap-2">
								<button onclick={saveMode} disabled={saving || !hasChanges} class="px-4 py-2 rounded-lg text-sm font-medium bg-accent text-white hover:bg-accent-dim transition-colors disabled:opacity-50">
									{saving ? 'Saving...' : 'Save'}
								</button>
								<button onclick={resetMode} disabled={!hasChanges} class="px-4 py-2 rounded-lg text-sm font-medium bg-bg-tertiary/50 text-text-secondary hover:bg-bg-tertiary transition-colors disabled:opacity-50">
									Reset
								</button>
								{#if !BUILTIN.has(detail.name)}
									<button onclick={deleteMode} class="ml-auto px-4 py-2 rounded-lg text-sm font-medium text-error hover:bg-error/10 transition-colors">
										Delete
									</button>
								{/if}
							</div>
						</div>
					{:else}
						<div class="flex items-center justify-center h-full text-text-dim text-sm">
							Select a mode to edit, or create a new one.
						</div>
					{/if}
				</div>
			</div>
		</div>
	</div>
{/if}
