<script lang="ts">
	import { tasks, selectedTaskId, updateTask, deleteTask, createTask } from '$lib/stores/tracker';
	import type { Task } from '$lib/stores/tracker';

	let task = $derived($tasks.find((t) => t.id === $selectedTaskId) ?? null);

	// Local edit state — sync from task when selection changes
	let title = $state('');
	let description = $state('');
	let status = $state<Task['status']>('todo');
	let priority = $state<Task['priority']>('medium');
	let due_date = $state('');
	let tagsText = $state('');
	let recurrence = $state('');
	let newSubtaskTitle = $state('');
	let confirmDelete = $state(false);

	let subtasks = $derived($tasks.filter((t) => t.parent_id === $selectedTaskId));

	// Sync local state only when selected task ID changes (not on every store update)
	let lastSyncedId = $state('');
	$effect(() => {
		const id = $selectedTaskId;
		if (id && id !== lastSyncedId && task) {
			lastSyncedId = id;
			title = task.title;
			description = task.description;
			status = task.status;
			priority = task.priority;
			due_date = task.due_date ?? '';
			tagsText = task.tags.join(', ');
			recurrence = task.recurrence ?? '';
			confirmDelete = false;
		}
	});

	function close() {
		selectedTaskId.set(null);
	}

	async function save() {
		if (!task) return;
		await updateTask(task.id, {
			title,
			description,
			status,
			priority,
			due_date: due_date || null,
			tags: tagsText.split(',').map((t) => t.trim()).filter(Boolean),
			recurrence: recurrence || null,
		});
	}

	async function handleDelete() {
		if (!confirmDelete) {
			confirmDelete = true;
			return;
		}
		if (!task) return;
		await deleteTask(task.id);
		close();
	}

	async function addSubtask() {
		if (!newSubtaskTitle.trim() || !task) return;
		await createTask({
			title: newSubtaskTitle.trim(),
			parent_id: task.id,
			status: 'todo',
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
</script>

{#if task}
	<div class="w-96 bg-bg-secondary border-l border-border/40 flex flex-col h-full overflow-y-auto">
		<!-- Header -->
		<div class="flex items-center justify-between px-4 py-3 border-b border-border/40">
			<h3 class="text-sm font-medium text-text-primary">Task Details</h3>
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

			<!-- Description -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Description</label>
				<textarea
					bind:value={description}
					rows={4}
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none focus:border-accent/60 resize-none"
				></textarea>
			</div>

			<!-- Status + Priority row -->
			<div class="flex gap-3">
				<div class="flex-1">
					<label class="block text-xs text-text-dim mb-1">Status</label>
					<select
						bind:value={status}
						class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none"
					>
						<option value="todo">Todo</option>
						<option value="in_progress">In Progress</option>
						<option value="done">Done</option>
						<option value="blocked">Blocked</option>
					</select>
				</div>
				<div class="flex-1">
					<label class="block text-xs text-text-dim mb-1">Priority</label>
					<select
						bind:value={priority}
						class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none"
					>
						<option value="urgent">Urgent</option>
						<option value="high">High</option>
						<option value="medium">Medium</option>
						<option value="low">Low</option>
					</select>
				</div>
			</div>

			<!-- Due date -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Due Date</label>
				<input
					type="date"
					bind:value={due_date}
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none focus:border-accent/60"
				/>
			</div>

			<!-- Tags -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Tags (comma-separated)</label>
				<input
					bind:value={tagsText}
					placeholder="work, urgent, feature"
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none focus:border-accent/60"
				/>
			</div>

			<!-- Recurrence -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Recurrence</label>
				<select
					bind:value={recurrence}
					class="w-full bg-bg-tertiary text-text-primary text-sm rounded px-3 py-2 border border-border/40 outline-none"
				>
					<option value="">None</option>
					<option value="daily">Daily</option>
					<option value="weekly">Weekly</option>
					<option value="monthly">Monthly</option>
					<option value="yearly">Yearly</option>
				</select>
			</div>

			<!-- Subtasks -->
			<div>
				<label class="block text-xs text-text-dim mb-1">Subtasks</label>
				{#each subtasks as sub (sub.id)}
					<div class="flex items-center gap-2 py-1 text-sm text-text-secondary">
						<span class="w-2 h-2 rounded-full {sub.status === 'done' ? 'bg-success' : 'border border-text-dim'}"></span>
						<span class={sub.status === 'done' ? 'line-through text-text-dim' : ''}>{sub.title}</span>
					</div>
				{/each}
				<div class="flex items-center gap-2 mt-1">
					<input
						bind:value={newSubtaskTitle}
						onkeydown={handleSubtaskKeydown}
						placeholder="Add subtask..."
						class="flex-1 bg-bg-tertiary text-text-primary text-sm rounded px-2 py-1 border border-border/40 outline-none focus:border-accent/60"
					/>
					<button
						onclick={addSubtask}
						class="text-xs text-accent hover:text-accent-dim transition-colors"
					>
						Add
					</button>
				</div>
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
