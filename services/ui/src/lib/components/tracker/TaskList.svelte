<script lang="ts">
	import { tasks, taskFilter, selectedTaskId, completeTask, updateTask } from '$lib/stores/tracker';
	import type { Task } from '$lib/stores/tracker';
	import TaskItem from './TaskItem.svelte';

	const statusOptions = [
		{ value: '', label: 'All' },
		{ value: 'todo', label: 'Todo' },
		{ value: 'in_progress', label: 'Active' },
		{ value: 'done', label: 'Done' },
		{ value: 'blocked', label: 'Blocked' },
	];

	const priorityOrder: Record<string, number> = { urgent: 0, high: 1, medium: 2, low: 3 };

	let filtered = $derived.by(() => {
		let list = $tasks.filter(t => !t.parent_id);
		const f = $taskFilter;
		if (f.status) list = list.filter(t => t.status === f.status);
		if (f.priority) list = list.filter(t => t.priority === f.priority);
		if (f.tag) list = list.filter(t => t.tags?.includes(f.tag));

		list.sort((a, b) => {
			if (f.sort === 'due') {
				if (!a.due_date && !b.due_date) return 0;
				if (!a.due_date) return 1;
				if (!b.due_date) return -1;
				return a.due_date.localeCompare(b.due_date);
			}
			return (priorityOrder[a.priority] ?? 2) - (priorityOrder[b.priority] ?? 2);
		});
		return list;
	});

	function getSubtasks(parentId: string): Task[] {
		return $tasks.filter(t => t.parent_id === parentId);
	}
</script>

<!-- Filter bar -->
<div class="flex items-center gap-2 px-4 py-2 border-b border-border/20">
	<div class="flex gap-0.5 bg-bg-tertiary/50 rounded-lg p-0.5">
		{#each statusOptions as opt}
			<button
				onclick={() => taskFilter.update(f => ({ ...f, status: opt.value }))}
				class="px-2.5 py-1 text-[11px] font-medium rounded-md transition-all
					{$taskFilter.status === opt.value
						? 'bg-bg-primary text-text-primary shadow-sm'
						: 'text-text-dim hover:text-text-secondary'}"
			>
				{opt.label}
			</button>
		{/each}
	</div>
	<div class="flex-1"></div>
	<select
		value={$taskFilter.sort}
		onchange={(e) => taskFilter.update(f => ({ ...f, sort: (e.target as HTMLSelectElement).value }))}
		class="text-[11px] bg-bg-tertiary/50 text-text-dim rounded px-2 py-1 border-none outline-none cursor-pointer"
	>
		<option value="priority">Sort: Priority</option>
		<option value="due">Sort: Due Date</option>
	</select>
</div>

<!-- Task list -->
<div class="divide-y divide-border/10">
	{#each filtered as task (task.id)}
		<TaskItem {task} subtasks={getSubtasks(task.id)} depth={0} />
	{:else}
		<div class="flex flex-col items-center justify-center py-16 text-text-dim">
			<svg class="w-10 h-10 mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
			</svg>
			<p class="text-sm">No tasks yet</p>
			<p class="text-xs mt-1 text-text-dim/60">Use the bar above to add one</p>
		</div>
	{/each}
</div>
