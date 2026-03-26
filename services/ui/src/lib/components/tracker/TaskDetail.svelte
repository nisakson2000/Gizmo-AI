<script lang="ts">
	import { onDestroy } from 'svelte';
	import { tasks, selectedTaskId, updateTask, deleteTask, createTask } from '$lib/stores/tracker';
	import type { Task } from '$lib/stores/tracker';

	let task = $derived($tasks.find((t) => t.id === $selectedTaskId) ?? null);

	// Local edit state
	let title = $state('');
	let description = $state('');
	let status = $state<Task['status']>('todo');
	let priority = $state<Task['priority']>('medium');
	let due_date = $state('');
	let tagsList = $state<string[]>([]);
	let tagInput = $state('');
	let recurrence = $state('');
	let newSubtaskTitle = $state('');
	let confirmDelete = $state(false);

	// Auto-save state
	let dirty = $state(false);
	let saveTimer: ReturnType<typeof setTimeout> | null = null;
	let saveStatus = $state<'idle' | 'saving' | 'saved'>('idle');
	let capturedTaskId = $state<string | null>(null);

	let subtasks = $derived($tasks.filter((t) => t.parent_id === $selectedTaskId));

	const statusOptions: { value: Task['status']; label: string; color: string }[] = [
		{ value: 'todo', label: 'Todo', color: 'border-text-dim/50 text-text-secondary bg-bg-tertiary/30' },
		{ value: 'in_progress', label: 'Active', color: 'border-accent text-accent bg-accent/10' },
		{ value: 'done', label: 'Done', color: 'border-success text-success bg-success/10' },
		{ value: 'blocked', label: 'Blocked', color: 'border-error text-error bg-error/10' },
	];

	const priorityOptions: { value: Task['priority']; label: string; color: string }[] = [
		{ value: 'urgent', label: 'Urgent', color: 'border-error text-error bg-error/10' },
		{ value: 'high', label: 'High', color: 'border-accent text-accent bg-accent/10' },
		{ value: 'medium', label: 'Medium', color: 'border-yellow-400 text-yellow-400 bg-yellow-400/10' },
		{ value: 'low', label: 'Low', color: 'border-text-dim/50 text-text-dim bg-bg-tertiary/30' },
	];

	// Sync local state only when selected task ID changes
	let lastSyncedId = $state('');
	$effect(() => {
		const id = $selectedTaskId;
		if (id && id !== lastSyncedId && task) {
			lastSyncedId = id;
			capturedTaskId = id;
			title = task.title;
			description = task.description;
			status = task.status;
			priority = task.priority;
			due_date = task.due_date ?? '';
			tagsList = [...task.tags];
			tagInput = '';
			recurrence = task.recurrence ?? '';
			confirmDelete = false;
			dirty = false;
			saveStatus = 'idle';
			if (saveTimer) clearTimeout(saveTimer);
		}
	});

	$effect(() => {
		if (task) capturedTaskId = task.id;
	});

	function markDirty() {
		dirty = true;
		saveStatus = 'idle';
		if (saveTimer) clearTimeout(saveTimer);
		saveTimer = setTimeout(doSave, 800);
	}

	async function doSave() {
		if (!dirty || !task) return;
		saveStatus = 'saving';
		await updateTask(task.id, {
			title, description, status, priority,
			due_date: due_date || null,
			tags: tagsList,
			recurrence: recurrence || null,
		});
		dirty = false;
		saveStatus = 'saved';
		setTimeout(() => { if (saveStatus === 'saved') saveStatus = 'idle'; }, 2000);
	}

	function close() {
		if (saveTimer) clearTimeout(saveTimer);
		if (dirty && capturedTaskId) {
			updateTask(capturedTaskId, {
				title, description, status, priority,
				due_date: due_date || null,
				tags: tagsList,
				recurrence: recurrence || null,
			});
		}
		selectedTaskId.set(null);
	}

	onDestroy(() => {
		if (saveTimer) clearTimeout(saveTimer);
		if (dirty && capturedTaskId) {
			updateTask(capturedTaskId, {
				title, description, status, priority,
				due_date: due_date || null,
				tags: tagsList,
				recurrence: recurrence || null,
			});
		}
	});

	async function handleDelete() {
		if (!confirmDelete) {
			confirmDelete = true;
			return;
		}
		if (!task) return;
		await deleteTask(task.id);
		selectedTaskId.set(null);
	}

	async function addSubtask() {
		if (!newSubtaskTitle.trim() || !task) return;
		await createTask({
			title: newSubtaskTitle.trim(),
			parent_id: task.id,
			priority: 'medium',
		});
		newSubtaskTitle = '';
	}

	function handleSubtaskKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter') {
			e.preventDefault();
			addSubtask();
		}
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

{#if task}
	<div class="flex flex-col h-full">
		<!-- Header -->
		<div class="flex items-center justify-between px-5 py-3 border-b border-border/40 shrink-0">
			<h3 class="text-xs font-medium text-text-dim uppercase tracking-wider">Task Details</h3>
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

		<div class="flex-1 overflow-y-auto p-5 space-y-5">
			<!-- Title (heading-style input) -->
			<input
				bind:value={title}
				oninput={markDirty}
				placeholder="Task title"
				class="w-full bg-transparent text-text-primary text-lg font-semibold outline-none border-b border-transparent focus:border-border/40 pb-1 transition-colors"
			/>

			<!-- Description -->
			<textarea
				bind:value={description}
				oninput={markDirty}
				rows={3}
				placeholder="Add a description..."
				class="w-full bg-bg-tertiary/30 text-text-primary text-sm rounded-lg px-3 py-2.5 border border-border/20 outline-none focus:border-accent/40 resize-none transition-colors"
			></textarea>

			<!-- Status (segmented control) -->
			<div>
				<label class="block text-[11px] text-text-dim uppercase tracking-wider mb-2">Status</label>
				<div class="grid grid-cols-4 gap-1.5">
					{#each statusOptions as opt}
						<button
							onclick={() => { status = opt.value; markDirty(); }}
							class="px-2 py-1.5 text-[11px] font-medium rounded-md border transition-all
								{status === opt.value ? opt.color : 'border-transparent text-text-dim hover:bg-bg-hover/50'}"
						>
							{opt.label}
						</button>
					{/each}
				</div>
			</div>

			<!-- Priority (colored buttons) -->
			<div>
				<label class="block text-[11px] text-text-dim uppercase tracking-wider mb-2">Priority</label>
				<div class="grid grid-cols-4 gap-1.5">
					{#each priorityOptions as opt}
						<button
							onclick={() => { priority = opt.value; markDirty(); }}
							class="px-2 py-1.5 text-[11px] font-medium rounded-md border transition-all
								{priority === opt.value ? opt.color : 'border-transparent text-text-dim hover:bg-bg-hover/50'}"
						>
							{opt.label}
						</button>
					{/each}
				</div>
			</div>

			<!-- Due date -->
			<div>
				<label class="block text-[11px] text-text-dim uppercase tracking-wider mb-2">Due Date</label>
				<input
					type="date"
					bind:value={due_date}
					onchange={markDirty}
					class="bg-bg-tertiary/30 text-text-primary text-sm rounded-lg px-3 py-2 border border-border/20 outline-none focus:border-accent/40 transition-colors"
				/>
			</div>

			<!-- Tags (pills) -->
			<div>
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

			<!-- Recurrence -->
			<div>
				<label class="block text-[11px] text-text-dim uppercase tracking-wider mb-2">Recurrence</label>
				<select
					bind:value={recurrence}
					onchange={markDirty}
					class="bg-bg-tertiary/30 text-text-primary text-sm rounded-lg px-3 py-2 border border-border/20 outline-none cursor-pointer"
				>
					<option value="">None</option>
					<option value="daily">Daily</option>
					<option value="weekly">Weekly</option>
					<option value="biweekly">Biweekly</option>
					<option value="monthly">Monthly</option>
					<option value="yearly">Yearly</option>
				</select>
			</div>

			<!-- Subtasks -->
			<div>
				<label class="block text-[11px] text-text-dim uppercase tracking-wider mb-2">Subtasks</label>
				{#each subtasks as sub (sub.id)}
					<div class="flex items-center gap-2.5 py-1.5 text-sm text-text-secondary">
						<span class="w-2.5 h-2.5 rounded-full shrink-0
							{sub.status === 'done' ? 'bg-success' : 'border border-text-dim'}"></span>
						<span class={sub.status === 'done' ? 'line-through text-text-dim' : ''}>{sub.title}</span>
					</div>
				{/each}
				<div class="flex items-center gap-2 mt-1.5">
					<input
						bind:value={newSubtaskTitle}
						onkeydown={handleSubtaskKeydown}
						placeholder="Add subtask..."
						class="flex-1 bg-bg-tertiary/30 text-text-primary text-sm rounded-lg px-3 py-1.5 border border-border/20 outline-none focus:border-accent/40 transition-colors"
					/>
					<button
						onclick={addSubtask}
						class="text-xs text-accent hover:text-accent-dim transition-colors font-medium"
					>Add</button>
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
