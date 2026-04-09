<script lang="ts">
	import {
		conversations,
		activeConversationId,
		newConversation,
		loadConversation,
		deleteConversation,
		renameConversation,
		conversationsLoaded,
	} from '$lib/stores/chat';
	import { sidebarOpen, settingsOpen, voiceStudioOpen } from '$lib/stores/settings';
	import { toast } from '$lib/stores/toast';
	import { playNavigate } from '$lib/utils/sounds';

	let search = $state('');
	let isMobile = $state(false);
	let renamingId = $state<string | null>(null);
	let renameValue = $state('');
	let searchResults = $state<{ id: string; title: string; updated_at: string; snippet: string }[]>([]);
	let searching = $state(false);
	let searchTimer: ReturnType<typeof setTimeout> | null = null;

	function checkMobile() {
		isMobile = window.innerWidth < 768;
	}

	$effect(() => {
		checkMobile();
		window.addEventListener('resize', checkMobile);
		return () => window.removeEventListener('resize', checkMobile);
	});

	let filtered = $derived(
		$conversations.filter((c) => c.title.toLowerCase().includes(search.toLowerCase()))
	);

	interface DateGroup {
		label: string;
		convs: typeof $conversations;
	}

	let grouped = $derived.by((): DateGroup[] => {
		const now = new Date();
		const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
		const yesterday = new Date(today.getTime() - 86400000);
		const weekAgo = new Date(today.getTime() - 7 * 86400000);

		const groups: DateGroup[] = [
			{ label: 'Today', convs: [] },
			{ label: 'Yesterday', convs: [] },
			{ label: 'Previous 7 Days', convs: [] },
			{ label: 'Older', convs: [] },
		];

		for (const conv of filtered) {
			const d = new Date(conv.updated_at);
			if (d >= today) groups[0].convs.push(conv);
			else if (d >= yesterday) groups[1].convs.push(conv);
			else if (d >= weekAgo) groups[2].convs.push(conv);
			else groups[3].convs.push(conv);
		}

		return groups.filter((g) => g.convs.length > 0);
	});

	function handleConvClick(id: string) {
		playNavigate();
		loadConversation(id);
		if (isMobile) sidebarOpen.set(false);
	}

	function handleDeleteClick(e: MouseEvent, id: string) {
		e.stopPropagation();
		deleteConversation(id);
	}

	async function handleExportClick(e: MouseEvent, id: string) {
		e.stopPropagation();
		try {
			const resp = await fetch(`/api/conversations/${id}/export?format=markdown`);
			if (!resp.ok) return;
			const blob = await resp.blob();
			const disposition = resp.headers.get('Content-Disposition') || '';
			const match = disposition.match(/filename="(.+)"/);
			const filename = match ? match[1] : 'conversation.md';
			const url = URL.createObjectURL(blob);
			const a = document.createElement('a');
			a.href = url;
			a.download = filename;
			a.click();
			URL.revokeObjectURL(url);
			toast('Conversation exported', 'success');
		} catch {
			toast('Export failed', 'error');
		}
	}

	async function doFullTextSearch() {
		const q = search.trim();
		if (!q || q.length < 2) { searchResults = []; return; }
		searching = true;
		try {
			const resp = await fetch(`/api/conversations/search?q=${encodeURIComponent(q)}`);
			if (resp.ok) searchResults = await resp.json();
		} catch {
			searchResults = [];
		}
		searching = false;
	}

	async function handleSearchKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && search.trim()) {
			if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; }
			doFullTextSearch();
		}
	}

	// Auto-trigger full-text search after 500ms of no typing
	$effect(() => {
		const q = search.trim();
		if (searchTimer) clearTimeout(searchTimer);
		if (!q || q.length < 2) { searchResults = []; return; }
		searchTimer = setTimeout(() => {
			doFullTextSearch();
			searchTimer = null;
		}, 500);
		return () => { if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; } };
	});

	function startRename(id: string, currentTitle: string) {
		renamingId = id;
		renameValue = currentTitle;
	}

	async function finishRename() {
		if (!renamingId) return;
		const title = renameValue.trim();
		if (title && title.length <= 100) {
			await renameConversation(renamingId, title);
		}
		renamingId = null;
		renameValue = '';
	}

	function cancelRename() {
		renamingId = null;
		renameValue = '';
	}

	function handleRenameKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter') { e.preventDefault(); finishRename(); }
		else if (e.key === 'Escape') { cancelRename(); }
	}

	function handleNewChat() {
		newConversation();
		if (isMobile) sidebarOpen.set(false);
	}
</script>

{#if $sidebarOpen}
	{#if isMobile}
		<button class="sidebar-overlay" onclick={() => sidebarOpen.set(false)} aria-label="Close sidebar"></button>
	{/if}

	<aside class="{isMobile ? 'sidebar-panel' : 'w-64 flex-shrink-0'} bg-bg-secondary border-r border-border/40 flex flex-col h-full">
		{#if isMobile}
			<div class="px-3 pt-3 pb-2 border-b border-border/30 space-y-0.5">
				<a href="/tracker" onclick={() => sidebarOpen.set(false)} class="flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm text-text-secondary hover:bg-bg-hover/50 transition-colors">
					<svg class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 002.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 00-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 00.75-.75 2.25 2.25 0 00-.1-.664m-5.8 0A2.251 2.251 0 0113.5 2.25H15a2.25 2.25 0 012.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25zM6.75 12h.008v.008H6.75V12zm0 3h.008v.008H6.75V15zm0 3h.008v.008H6.75V18z" /></svg>
					Tasks
				</a>
				<a href="/code" onclick={() => sidebarOpen.set(false)} class="flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm text-text-secondary hover:bg-bg-hover/50 transition-colors">
					<svg class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17.25 6.75L22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3l-4.5 16.5" /></svg>
					Code
				</a>
				<a href="/analytics" onclick={() => sidebarOpen.set(false)} class="flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm text-text-secondary hover:bg-bg-hover/50 transition-colors">
					<svg class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z" /></svg>
					Stats
				</a>
				<button onclick={() => { settingsOpen.set(true); sidebarOpen.set(false); }} class="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm text-text-secondary hover:bg-bg-hover/50 transition-colors text-left">
					<svg class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 010 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 010-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
					Settings
				</button>
				<button onclick={() => { voiceStudioOpen.set(true); sidebarOpen.set(false); }} class="w-full flex items-center gap-2.5 px-2.5 py-2 rounded-lg text-sm text-text-secondary hover:bg-bg-hover/50 transition-colors text-left">
					<svg class="w-4 h-4 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M19.114 5.636a9 9 0 010 12.728M16.463 8.288a5.25 5.25 0 010 7.424M6.75 8.25l4.72-4.72a.75.75 0 011.28.53v15.88a.75.75 0 01-1.28.53l-4.72-4.72H4.51c-.88 0-1.704-.507-1.938-1.354A9.009 9.009 0 012.25 12c0-.83.112-1.633.322-2.396C2.806 8.756 3.63 8.25 4.51 8.25H6.75z" /></svg>
					Voice Studio
				</button>
			</div>
		{/if}
		<div class="p-3">
			<button
				onclick={handleNewChat}
				class="w-full px-3 py-2 rounded-lg bg-bg-hover/60 border border-border/40 text-text-secondary hover:text-text-primary hover:border-border text-sm transition-all text-left flex items-center gap-2"
			>
				<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 4.5v15m7.5-7.5h-15" />
				</svg>
				New chat
			</button>
		</div>

		<div class="px-3 pb-2">
			<input
				type="text"
				placeholder="Search conversations..."
				bind:value={search}
				onkeydown={handleSearchKeydown}
				class="w-full px-2.5 py-1.5 bg-bg-primary/50 border border-border/30 rounded-lg text-sm text-text-primary placeholder:text-text-dim focus:outline-none focus:border-border transition-colors"
			/>
		</div>

		<div class="flex-1 overflow-y-auto px-2" role="listbox" aria-label="Conversations">
			{#if !$conversationsLoaded && $conversations.length === 0}
				<div class="px-3 py-2 space-y-1">
					{#each [60, 80, 45, 70, 55] as w}
						<div class="h-8 bg-bg-hover/30 rounded-lg animate-pulse" style="width: {w}%"></div>
					{/each}
				</div>
			{/if}
			{#if searching}
				<div class="px-3 py-2 text-xs text-text-dim">Searching messages...</div>
			{/if}
			{#if searchResults.length > 0}
				<div class="px-2 pt-2 pb-1 text-[11px] uppercase tracking-wider text-accent/70 font-medium">
					Message matches
				</div>
				{#each searchResults as result (result.id)}
					<button
						onclick={() => handleConvClick(result.id)}
						class="w-full text-left px-3 py-2 rounded-lg mb-0.5 cursor-pointer hover:bg-bg-hover/50 transition-colors"
					>
						<div class="text-sm text-text-primary truncate">{result.title}</div>
						<div class="text-xs text-text-dim italic mt-0.5 line-clamp-2">{result.snippet}</div>
					</button>
				{/each}
				<div class="border-b border-border/20 my-2"></div>
			{/if}
			{#each grouped as group}
				<div class="px-2 pt-4 pb-1 text-[11px] uppercase tracking-wider text-text-dim/70 font-medium">
					{group.label}
				</div>
				{#each group.convs as conv (conv.id)}
					<div
						onclick={() => handleConvClick(conv.id)}
						onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleConvClick(conv.id); } }}
						role="option"
						tabindex="0"
						aria-selected={$activeConversationId === conv.id}
						class="w-full text-left px-3 py-2 rounded-lg mb-0.5 flex items-center justify-between group transition-colors cursor-pointer
							{$activeConversationId === conv.id
								? 'bg-bg-hover text-text-primary'
								: 'text-text-secondary hover:bg-bg-hover/50 hover:text-text-primary'}"
					>
						{#if renamingId === conv.id}
						<!-- svelte-ignore a11y_autofocus -->
						<input
							type="text"
							bind:value={renameValue}
							onkeydown={handleRenameKeydown}
							onblur={finishRename}
							autofocus
							maxlength="100"
							class="flex-1 bg-bg-primary border border-accent/40 rounded px-1.5 py-0.5 text-sm text-text-primary focus:outline-none min-w-0"
							onclick={(e) => e.stopPropagation()}
						/>
					{:else}
						<span
							class="truncate text-sm flex-1"
							ondblclick={(e) => { e.stopPropagation(); startRename(conv.id, conv.title); }}
						>{conv.title}</span>
					{/if}
						<div class="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0">
							<button
								onclick={(e) => handleExportClick(e, conv.id)}
								class="text-text-dim hover:text-accent transition-colors p-0.5"
								aria-label="Export conversation"
							>
								<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
								</svg>
							</button>
							<button
								onclick={(e) => handleDeleteClick(e, conv.id)}
								class="text-text-dim hover:text-error transition-colors p-0.5"
								aria-label="Delete conversation"
							>
								<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
								</svg>
							</button>
						</div>
					</div>
				{/each}
			{/each}
		</div>

	</aside>
{/if}
