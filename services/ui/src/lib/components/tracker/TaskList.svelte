<script lang="ts">
	import { tasks, taskFilter, allTags } from '$lib/stores/tracker';
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

	let tagDropdownOpen = $state(false);

	function getStatusCount(statusValue: string): number {
		const all = $tasks.filter(t => !t.parent_id);
		if (!statusValue) return all.length;
		return all.filter(t => t.status === statusValue).length;
	}

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

	function handleTagSelect(tag: string) {
		taskFilter.update(f => ({ ...f, tag: f.tag === tag ? '' : tag }));
		tagDropdownOpen = false;
	}

	function clearTag() {
		taskFilter.update(f => ({ ...f, tag: '' }));
		tagDropdownOpen = false;
	}

	// Close dropdown on click outside
	$effect(() => {
		if (!tagDropdownOpen) return;
		function handleClick(e: MouseEvent) {
			if (!(e.target as Element)?.closest('.tag-dropdown-container')) {
				tagDropdownOpen = false;
			}
		}
		document.addEventListener('click', handleClick, true);
		return () => document.removeEventListener('click', handleClick, true);
	});
</script>

<!-- Filter bar -->
<div class="flex items-center gap-2 mx-4 mt-2 mb-1 px-3 py-2 bg-bg-tertiary/40 rounded-lg border border-border/20">
	<div class="flex gap-0.5 bg-bg-primary/50 rounded-lg p-0.5">
		{#each statusOptions as opt}
			<button
				onclick={() => taskFilter.update(f => ({ ...f, status: opt.value }))}
				class="px-2.5 py-1 text-[11px] font-medium rounded-md transition-all flex items-center gap-1
					{$taskFilter.status === opt.value
						? 'bg-bg-primary text-text-primary shadow-sm'
						: 'text-text-dim hover:text-text-secondary'}"
			>
				{opt.label}
				<span class="text-[10px] opacity-50">{getStatusCount(opt.value)}</span>
			</button>
		{/each}
	</div>

	<!-- Tag dropdown -->
	{#if $allTags.length > 0}
		<div class="relative tag-dropdown-container">
			<button
				onclick={() => tagDropdownOpen = !tagDropdownOpen}
				class="flex items-center gap-1 px-2.5 py-1 text-[11px] font-medium rounded-md transition-all
					{$taskFilter.tag ? 'bg-accent/10 text-accent' : 'text-text-dim hover:text-text-secondary hover:bg-bg-hover/50'}"
			>
				<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A2 2 0 013 12V7a4 4 0 014-4z" />
				</svg>
				{$taskFilter.tag || 'Tags'}
				<svg class="w-2.5 h-2.5 transition-transform {tagDropdownOpen ? 'rotate-180' : ''}" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
				</svg>
			</button>

			{#if tagDropdownOpen}
				<div class="absolute top-full left-0 mt-1 bg-bg-secondary border border-border/40 rounded-lg shadow-lg z-10 p-1 min-w-[140px] max-h-[200px] overflow-y-auto">
					<button
						onclick={clearTag}
						class="w-full text-left px-2.5 py-1.5 text-[11px] rounded-md transition-colors
							{!$taskFilter.tag ? 'bg-bg-hover text-text-primary font-medium' : 'text-text-secondary hover:bg-bg-hover/50'}"
					>All tasks</button>
					{#each $allTags as tag}
						<button
							onclick={() => handleTagSelect(tag)}
							class="w-full text-left px-2.5 py-1.5 text-[11px] rounded-md transition-colors flex items-center gap-2
								{$taskFilter.tag === tag ? 'bg-accent/10 text-accent font-medium' : 'text-text-secondary hover:bg-bg-hover/50'}"
						>
							<span class="w-1.5 h-1.5 rounded-full shrink-0 {$taskFilter.tag === tag ? 'bg-accent' : 'bg-text-dim/30'}"></span>
							{tag}
						</button>
					{/each}
				</div>
			{/if}
		</div>
	{/if}

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
<div class="py-1">
	{#each filtered as task (task.id)}
		<TaskItem {task} subtasks={getSubtasks(task.id)} depth={0} />
	{:else}
		<div class="flex flex-col items-center justify-center py-20 text-text-dim">
			<div class="w-16 h-16 rounded-2xl bg-bg-tertiary/50 border border-border/20 flex items-center justify-center mb-4">
				<svg class="w-7 h-7 opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
				</svg>
			</div>
			<p class="text-sm font-medium text-text-secondary">No tasks yet</p>
			<p class="text-xs mt-1.5 text-text-dim/50 max-w-[200px] text-center">Type a title above and press Enter to create your first task</p>
		</div>
	{/each}
</div>
