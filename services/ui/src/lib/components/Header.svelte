<script lang="ts">
	import { connectionStatus } from '$lib/stores/connection';
	import { sidebarOpen, settingsOpen } from '$lib/stores/settings';
	import { conversations, activeConversationId } from '$lib/stores/chat';

	let statusColor = $derived(
		$connectionStatus === 'connected'
			? 'bg-success'
			: $connectionStatus === 'generating'
				? 'bg-accent animate-pulse'
				: $connectionStatus === 'connecting'
					? 'bg-accent animate-pulse'
					: 'bg-error'
	);

	let statusText = $derived(
		$connectionStatus === 'generating'
			? 'Generating'
			: $connectionStatus === 'connecting'
				? 'Connecting'
				: $connectionStatus === 'connected'
					? 'Online'
					: 'Offline'
	);

	let conversationTitle = $derived.by(() => {
		if (!$activeConversationId) return null;
		const conv = $conversations.find(c => c.id === $activeConversationId);
		return conv?.title || null;
	});
</script>

<header class="flex items-center justify-between px-4 h-12 border-b border-border/60 bg-bg-primary">
	<div class="flex items-center gap-3 min-w-0">
		<button
			onclick={() => sidebarOpen.update((v) => !v)}
			class="text-text-dim hover:text-text-secondary p-1.5 -ml-1 transition-colors shrink-0"
			aria-label="Toggle sidebar"
		>
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5" />
			</svg>
		</button>
		<span class="text-sm font-semibold text-text-primary tracking-tight shrink-0">Gizmo</span>
		{#if conversationTitle}
			<span class="text-text-dim/40 shrink-0">/</span>
			<span class="text-xs text-text-dim truncate">{conversationTitle}</span>
		{/if}
	</div>

	<div class="flex items-center gap-2 shrink-0">
		<div class="flex items-center gap-1.5">
			<div class="w-1.5 h-1.5 rounded-full {statusColor}"></div>
			<span class="text-[11px] text-text-dim">{statusText}</span>
		</div>
		<button
			onclick={() => settingsOpen.set(true)}
			class="md:hidden p-1.5 text-text-dim hover:text-text-secondary transition-colors"
			aria-label="Settings"
		>
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.325.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.241-.438.613-.43.992a7.723 7.723 0 010 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.955.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.47 6.47 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.281c-.09.543-.56.94-1.11.94h-2.594c-.55 0-1.019-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.991a6.932 6.932 0 010-.255c.007-.38-.138-.751-.43-.992l-1.004-.827a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.086.22-.128.332-.183.582-.495.644-.869l.214-1.28z" />
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
			</svg>
		</button>
	</div>
</header>
