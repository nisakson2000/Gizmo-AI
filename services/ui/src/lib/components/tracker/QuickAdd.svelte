<script lang="ts">
	import { createTask, createNote, activeTab } from '$lib/stores/tracker';
	import type { Task } from '$lib/stores/tracker';

	let title = $state('');
	let priority = $state<Task['priority']>('medium');

	const priorityOptions: { value: Task['priority']; label: string; activeClass: string; inactiveClass: string }[] = [
		{ value: 'urgent', label: 'Urgent', activeClass: 'bg-error border-error scale-110', inactiveClass: 'border-error/30 hover:border-error/60' },
		{ value: 'high', label: 'High', activeClass: 'bg-accent border-accent scale-110', inactiveClass: 'border-accent/30 hover:border-accent/60' },
		{ value: 'medium', label: 'Medium', activeClass: 'bg-yellow-400 border-yellow-400 scale-110', inactiveClass: 'border-yellow-400/30 hover:border-yellow-400/60' },
		{ value: 'low', label: 'Low', activeClass: 'bg-text-dim/40 border-text-dim/40 scale-110', inactiveClass: 'border-text-dim/20 hover:border-text-dim/40' },
	];

	async function submit() {
		const text = title.trim();
		if (!text) return;

		if ($activeTab === 'tasks') {
			await createTask({ title: text, priority });
		} else {
			await createNote({ title: text, content: '' });
		}
		title = '';
		priority = 'medium';
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter') {
			e.preventDefault();
			submit();
		}
	}
</script>

<div class="mx-4 mt-3 mb-2 rounded-xl bg-bg-tertiary/60 border border-border/50 shadow-sm transition-all
	{title.trim() ? 'ring-1 ring-accent/30 border-accent/30 shadow-md' : 'hover:border-border/70'}">
	<div class="px-4 py-3.5">
		<div class="flex items-center gap-3">
			<div class="w-7 h-7 rounded-lg bg-accent/10 flex items-center justify-center shrink-0">
				<svg class="w-3.5 h-3.5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M12 4v16m8-8H4" />
				</svg>
			</div>
			<input
				bind:value={title}
				onkeydown={handleKeydown}
				placeholder={$activeTab === 'tasks' ? 'What needs to be done?' : 'Add a new note...'}
				class="flex-1 bg-transparent text-text-primary text-sm outline-none placeholder:text-text-dim/40"
			/>
			{#if !title.trim()}
				<span class="text-[10px] text-text-dim/30 shrink-0">Press Enter</span>
			{/if}
		</div>

		{#if title.trim()}
			<div class="flex items-center gap-3 mt-3 pt-3 border-t border-border/30">
				{#if $activeTab === 'tasks'}
					<div class="flex items-center gap-2">
						<span class="text-[10px] text-text-dim/60 mr-0.5">Priority:</span>
						{#each priorityOptions as opt}
							<button
								onclick={() => priority = opt.value}
								class="w-5 h-5 rounded-full border-2 transition-all
									{priority === opt.value ? opt.activeClass : opt.inactiveClass}"
								title={opt.label}
							></button>
						{/each}
					</div>
				{/if}
				<div class="flex-1"></div>
				<button
					onclick={submit}
					class="px-4 py-1.5 text-xs font-semibold rounded-lg bg-accent text-bg-primary hover:brightness-110 transition-all shadow-sm"
				>
					{$activeTab === 'tasks' ? 'Add Task' : 'Add Note'}
				</button>
			</div>
		{/if}
	</div>
</div>
