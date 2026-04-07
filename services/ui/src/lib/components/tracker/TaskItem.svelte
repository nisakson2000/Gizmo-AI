<script lang="ts">
	import { selectedTaskId, completeTask, updateTask, deleteTask, tasks } from '$lib/stores/tracker';
	import type { Task } from '$lib/stores/tracker';

	let { task, subtasks = [], depth = 0, focused = false }: { task: Task; subtasks?: Task[]; depth?: number; focused?: boolean } = $props();

	const priorityColors: Record<string, string> = {
		urgent: 'var(--color-error)',
		high: 'var(--color-accent)',
		medium: '#eab308',
		low: 'var(--color-border)',
	};

	let isOverdue = $derived(
		task.due_date && task.status !== 'done' && new Date(task.due_date) < new Date()
	);

	let isSelected = $derived($selectedTaskId === task.id);
	let doneSubtasks = $derived(subtasks.filter(s => s.status === 'done').length);
	let subtasksExpanded = $state(false);
	let editingTitle = $state(false);
	let editTitle = $state('');
	let itemEl: HTMLDivElement;

	$effect(() => {
		if (focused && itemEl) {
			itemEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
		}
	});

	async function cycleStatus() {
		if (task.status === 'todo') {
			await updateTask(task.id, { status: 'in_progress' });
		} else if (task.status === 'in_progress') {
			await completeTask(task.id);
		} else if (task.status === 'done') {
			await updateTask(task.id, { status: 'todo' });
		} else if (task.status === 'blocked') {
			await updateTask(task.id, { status: 'todo' });
		}
	}

	function getChildSubtasks(parentId: string): Task[] {
		return $tasks.filter(t => t.parent_id === parentId);
	}

	function formatDate(d: string): string {
		const date = new Date(d);
		const now = new Date();
		const diff = Math.floor((date.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
		if (diff === 0) return 'Today';
		if (diff === 1) return 'Tomorrow';
		if (diff === -1) return 'Yesterday';
		if (diff > 0 && diff < 7) return date.toLocaleDateString(undefined, { weekday: 'short' });
		return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
	}
</script>

<div
	bind:this={itemEl}
	class="group mx-4 my-1.5 rounded-lg border transition-all cursor-pointer
		{isOverdue ? 'ring-1 ring-error/15' : ''}
		{focused ? 'ring-2 ring-accent/30' : ''}
		{isSelected ? 'bg-bg-hover/80 border-accent/40 shadow-sm' : 'border-border/20 hover:border-border/40 hover:shadow-sm'}"
	style="border-left: 3px solid {priorityColors[task.priority]};{depth > 0 ? ` margin-left: ${depth * 20}px` : ''}"
	role="button"
	tabindex="0"
	onclick={() => selectedTaskId.set(isSelected ? null : task.id)}
	onkeydown={(e) => e.key === 'Enter' && selectedTaskId.set(isSelected ? null : task.id)}
>
	<div class="flex items-center gap-2.5 px-3 py-2.5">
		<!-- Status circle (SVG) -->
		<button
			onclick={(e) => { e.stopPropagation(); cycleStatus(); }}
			class="shrink-0 flex items-center justify-center transition-all hover:scale-110
				{task.status === 'done' ? 'text-success' : task.status === 'in_progress' ? 'text-accent' : task.status === 'blocked' ? 'text-error' : 'text-text-dim'}"
			title="Click to change status"
		>
			<svg class="w-[18px] h-[18px]" viewBox="0 0 18 18" fill="none">
				{#if task.status === 'todo'}
					<circle cx="9" cy="9" r="7.5" stroke="currentColor" stroke-width="1.5" />
				{:else if task.status === 'in_progress'}
					<circle cx="9" cy="9" r="7.5" stroke="currentColor" stroke-width="1.5" />
					<circle cx="9" cy="9" r="3.5" fill="currentColor" />
				{:else if task.status === 'done'}
					<circle cx="9" cy="9" r="8" fill="currentColor" />
					<path d="M5.5 9.5L7.5 11.5L12.5 6.5" stroke="white" stroke-width="1.5" fill="none" stroke-linecap="round" stroke-linejoin="round" />
				{:else}
					<circle cx="9" cy="9" r="7.5" stroke="currentColor" stroke-width="1.5" />
					<path d="M6.5 6.5L11.5 11.5M11.5 6.5L6.5 11.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
				{/if}
			</svg>
		</button>

		<!-- Title -->
		{#if editingTitle}
			<!-- svelte-ignore a11y_autofocus -->
			<input
				type="text"
				bind:value={editTitle}
				autofocus
				onclick={(e) => e.stopPropagation()}
				onkeydown={(e) => {
					if (e.key === 'Enter') {
						e.preventDefault();
						const trimmed = editTitle.trim();
						if (trimmed && trimmed !== task.title) updateTask(task.id, { title: trimmed });
						editingTitle = false;
					} else if (e.key === 'Escape') {
						editingTitle = false;
					}
				}}
				onblur={() => {
					const trimmed = editTitle.trim();
					if (trimmed && trimmed !== task.title) updateTask(task.id, { title: trimmed });
					editingTitle = false;
				}}
				class="flex-1 text-sm bg-bg-primary border border-accent/40 rounded px-1.5 py-0.5 text-text-primary focus:outline-none min-w-0"
			/>
		{:else}
			<span
				class="flex-1 text-sm truncate {task.status === 'done' ? 'line-through text-text-dim' : 'text-text-primary'}"
				ondblclick={(e) => { e.stopPropagation(); editTitle = task.title; editingTitle = true; }}
			>
				{task.title}
			</span>
		{/if}

		<!-- Subtask progress -->
		{#if subtasks.length > 0}
			<button
				onclick={(e) => { e.stopPropagation(); subtasksExpanded = !subtasksExpanded; }}
				class="shrink-0 text-[11px] text-text-dim flex items-center gap-0.5 hover:text-text-secondary transition-colors"
			>
				{doneSubtasks}/{subtasks.length}
				<svg class="w-3 h-3 transition-transform duration-200 {subtasksExpanded ? 'rotate-90' : ''}" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
				</svg>
			</button>
		{/if}

		<!-- Recurrence badge -->
		{#if task.recurrence && task.recurrence !== 'none'}
			<span class="shrink-0 px-1.5 py-0.5 text-[10px] rounded-full bg-accent/[0.08] text-accent/60">
				{task.recurrence}
			</span>
		{/if}

		<!-- Tags -->
		{#each (task.tags || []).slice(0, 2) as tag}
			<span class="shrink-0 px-1.5 py-0.5 text-[10px] rounded-full bg-bg-tertiary text-text-dim">{tag}</span>
		{/each}

		<!-- Due date pill -->
		{#if task.due_date}
			<span class="shrink-0 px-2 py-0.5 text-[11px] rounded-full
				{isOverdue ? 'bg-error/10 text-error font-medium' : 'bg-bg-tertiary/50 text-text-dim'}">
				{formatDate(task.due_date)}
			</span>
		{/if}

		<!-- Delete (on hover) -->
		<button
			onclick={(e) => { e.stopPropagation(); deleteTask(task.id); }}
			class="shrink-0 opacity-0 group-hover:opacity-60 max-sm:opacity-40 hover:!opacity-100 text-text-dim hover:text-error transition-all p-0.5"
			title="Delete"
		>
			<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
			</svg>
		</button>
	</div>
</div>

<!-- Subtasks -->
{#if subtasksExpanded}
	{#each subtasks as sub (sub.id)}
		<svelte:self task={sub} subtasks={getChildSubtasks(sub.id)} depth={depth + 1} />
	{/each}
{/if}
