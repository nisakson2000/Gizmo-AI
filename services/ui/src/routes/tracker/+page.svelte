<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import {
		activeTab,
		tasks,
		notes,
		selectedTaskId,
		selectedNoteId,
		trackerChatOpen,
		loadTasks,
		loadNotes,
		loadTags,
	} from '$lib/stores/tracker';
	import { connectTracker, disconnectTracker } from '$lib/ws/tracker-client';

	import QuickAdd from '$lib/components/tracker/QuickAdd.svelte';
	import TaskList from '$lib/components/tracker/TaskList.svelte';
	import NoteList from '$lib/components/tracker/NoteList.svelte';
	import TaskDetail from '$lib/components/tracker/TaskDetail.svelte';
	import NoteEditor from '$lib/components/tracker/NoteEditor.svelte';
	import TrackerChat from '$lib/components/tracker/TrackerChat.svelte';

	let taskCount = $derived($tasks.filter(t => t.status !== 'done').length);
	let noteCount = $derived($notes.length);
	let totalCount = $derived($tasks.filter(t => !t.parent_id).length);
	let doneCount = $derived($tasks.filter(t => !t.parent_id && t.status === 'done').length);
	let completionPct = $derived(totalCount ? Math.round((doneCount / totalCount) * 100) : 0);

	const circumference = 2 * Math.PI * 10;

	let showTaskDetail = $derived($activeTab === 'tasks' && $selectedTaskId !== null);
	let showNoteEditor = $derived($activeTab === 'notes' && $selectedNoteId !== null);

	onMount(() => {
		loadTasks();
		loadNotes();
		loadTags();
		connectTracker();
	});

	onDestroy(() => {
		disconnectTracker();
	});
</script>

<svelte:head>
	<title>Gizmo-AI — Tracker</title>
</svelte:head>

<div class="console-frame flex flex-col h-full bg-bg-primary">
	<!-- Header -->
	<header class="flex items-center justify-between px-5 py-3 border-b border-border/60 bg-bg-secondary/50 shrink-0">
		<div class="flex items-center gap-3">
			<div class="w-9 h-9 rounded-xl bg-accent/15 flex items-center justify-center shadow-sm">
				<svg class="w-4.5 h-4.5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
				</svg>
			</div>
			<div class="flex flex-col">
				<span class="text-sm font-semibold text-text-primary tracking-tight">Tracker</span>
				{#if $activeTab === 'tasks' && totalCount > 0}
					<span class="text-[10px] text-text-dim/60">{doneCount}/{totalCount} completed</span>
				{:else if $activeTab === 'notes'}
					<span class="text-[10px] text-text-dim/60">{noteCount} note{noteCount !== 1 ? 's' : ''}</span>
				{/if}
			</div>

			{#if $activeTab === 'tasks' && totalCount > 0}
				<div class="flex items-center gap-1.5 ml-2 px-2 py-1 rounded-lg bg-bg-tertiary/50">
					<svg class="w-5 h-5 -rotate-90" viewBox="0 0 24 24">
						<circle cx="12" cy="12" r="10" fill="none" stroke="var(--color-border)" stroke-width="2.5" opacity="0.15" />
						<circle cx="12" cy="12" r="10" fill="none" stroke="var(--color-accent)" stroke-width="2.5"
							stroke-dasharray={circumference}
							stroke-dashoffset={circumference * (1 - completionPct / 100)}
							stroke-linecap="round"
							class="transition-all duration-500" />
					</svg>
					<span class="text-[11px] font-medium text-accent">{completionPct}%</span>
				</div>
			{/if}
		</div>

		<div class="flex items-center gap-2">
			<div class="flex bg-bg-tertiary/70 rounded-lg p-0.5 border border-border/20">
				<button
					onclick={() => activeTab.set('tasks')}
					class="px-4 py-1.5 text-xs font-semibold rounded-md transition-all
						{$activeTab === 'tasks' ? 'bg-bg-primary text-accent shadow-sm' : 'text-text-secondary hover:text-text-primary'}"
				>Tasks <span class="text-text-dim ml-0.5">{taskCount}</span></button>
				<button
					onclick={() => activeTab.set('notes')}
					class="px-4 py-1.5 text-xs font-semibold rounded-md transition-all
						{$activeTab === 'notes' ? 'bg-bg-primary text-accent shadow-sm' : 'text-text-secondary hover:text-text-primary'}"
				>Notes <span class="text-text-dim ml-0.5">{noteCount}</span></button>
			</div>

			<div class="w-px h-5 bg-border/30"></div>

			<button
				onclick={() => trackerChatOpen.update(v => !v)}
				class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg border transition-all
					{$trackerChatOpen
						? 'bg-accent/15 text-accent border-accent/30'
						: 'text-text-dim border-border/20 hover:text-text-secondary hover:bg-bg-hover hover:border-border/40'}"
			>
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
				</svg>
				Ask Gizmo
			</button>
		</div>
	</header>

	<QuickAdd />

	<!-- Main content area -->
	<div class="console-screen flex-1 overflow-hidden relative">
		<!-- Full-width scrollable list -->
		<div class="h-full overflow-y-auto">
			{#if $activeTab === 'tasks'}
				<TaskList />
			{:else}
				<NoteList />
			{/if}
		</div>

		<!-- Overlay: Task Detail -->
		{#if showTaskDetail}
			<div class="overlay-container overlay-fade">
				<div class="overlay-dim hidden sm:block" onclick={() => selectedTaskId.set(null)} role="presentation"></div>
				<div class="w-full sm:w-[480px] bg-bg-secondary border-l border-border/40 flex flex-col overflow-hidden overlay-slide">
					<TaskDetail />
				</div>
			</div>
		{/if}

		<!-- Overlay: Note Editor -->
		{#if showNoteEditor}
			<div class="overlay-container overlay-fade">
				<div class="overlay-dim hidden sm:block" onclick={() => selectedNoteId.set(null)} role="presentation"></div>
				<div class="w-full sm:w-[480px] bg-bg-secondary border-l border-border/40 flex flex-col overflow-hidden overlay-slide">
					<NoteEditor />
				</div>
			</div>
		{/if}

		<!-- Overlay: Tracker Chat (always on top) -->
		{#if $trackerChatOpen}
			<div class="overlay-container overlay-fade overlay-chat">
				<div class="overlay-dim hidden sm:block" onclick={() => trackerChatOpen.set(false)} role="presentation"></div>
				<div class="w-full sm:w-[400px] bg-bg-secondary border-l border-border/40 flex flex-col overflow-hidden overlay-slide">
					<TrackerChat />
				</div>
			</div>
		{/if}
	</div>
</div>

<style>
	@keyframes overlayFade {
		from { opacity: 0; }
		to { opacity: 1; }
	}
	@keyframes overlaySlide {
		from { transform: translateX(100%); }
		to { transform: translateX(0); }
	}

	.overlay-container {
		position: absolute;
		inset: 0;
		z-index: 20;
		display: flex;
	}
	.overlay-chat {
		z-index: 30;
	}
	.overlay-fade {
		animation: overlayFade 0.15s ease-out;
	}
	.overlay-dim {
		flex: 1;
		background: rgba(0, 0, 0, 0.3);
	}
	.overlay-slide {
		animation: overlaySlide 0.2s ease-out;
	}
</style>
