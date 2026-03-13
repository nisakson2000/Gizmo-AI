<script lang="ts">
	import { onMount } from 'svelte';
	import { connectionStatus } from '$lib/stores/connection';
	import { thinkingEnabled, ttsEnabled, sidebarOpen } from '$lib/stores/settings';

	let sidebarBtn: HTMLButtonElement;
	let thinkBtn: HTMLButtonElement;
	let ttsBtn: HTMLButtonElement;

	let statusColor = $derived(
		$connectionStatus === 'connected'
			? 'bg-success'
			: $connectionStatus === 'generating'
				? 'bg-accent animate-pulse'
				: 'bg-error'
	);

	onMount(() => {
		sidebarBtn.addEventListener('click', () => sidebarOpen.update((v) => !v));
		thinkBtn.addEventListener('click', () => thinkingEnabled.update((v) => !v));
		ttsBtn.addEventListener('click', () => ttsEnabled.update((v) => !v));
	});
</script>

<header class="flex items-center justify-between px-4 py-2 border-b border-border bg-bg-secondary">
	<div class="flex items-center gap-3">
		<button
			bind:this={sidebarBtn}
			class="text-text-secondary hover:text-text-primary p-1"
			aria-label="Toggle sidebar"
		>
			<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
			</svg>
		</button>
		<span class="text-lg font-semibold text-text-primary tracking-tight">Gizmo</span>
		<span class="text-xs text-text-dim font-mono hidden sm:inline">Qwen3.5-27B Abliterated</span>
	</div>

	<div class="flex items-center gap-3">
		<button
			bind:this={thinkBtn}
			class="flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition-colors {$thinkingEnabled ? 'bg-accent text-white' : 'bg-bg-tertiary text-text-secondary hover:text-text-primary'}"
		>
			<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
			</svg>
			Think {$thinkingEnabled ? 'ON' : 'OFF'}
		</button>

		<button
			bind:this={ttsBtn}
			class="flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition-colors {$ttsEnabled ? 'bg-accent text-white' : 'bg-bg-tertiary text-text-secondary hover:text-text-primary'}"
		>
			<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.536 8.464a5 5 0 010 7.072m2.828-9.9a9 9 0 010 12.728M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z" />
			</svg>
			TTS
		</button>

		<div class="flex items-center gap-1.5">
			<div class="w-2 h-2 rounded-full {statusColor}"></div>
			<span class="text-xs text-text-dim hidden sm:inline">
				{$connectionStatus === 'generating' ? 'Generating...' : $connectionStatus}
			</span>
		</div>
	</div>
</header>
