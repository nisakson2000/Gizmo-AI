<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import Header from '$lib/components/Header.svelte';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import ChatArea from '$lib/components/ChatArea.svelte';
	import ChatInput from '$lib/components/ChatInput.svelte';
	import VoiceStudio from '$lib/components/VoiceStudio.svelte';
	import MemoryManager from '$lib/components/MemoryManager.svelte';
	import CodePlayground from '$lib/components/CodePlayground.svelte';
	import { connect, disconnect } from '$lib/ws/client';
	import { loadConversations, newConversation, generating } from '$lib/stores/chat';
	import { voiceStudioOpen, sidebarOpen, thinkingEnabled, memoryManagerOpen, codePlaygroundOpen, focusTrigger } from '$lib/stores/settings';
	import { theme } from '$lib/stores/theme';
	import ConsoleButtons from '$lib/components/ConsoleButtons.svelte';

	let showHttpBanner = $state(false);

	function handleChatKeydown(e: KeyboardEvent) {
		const mod = e.ctrlKey || e.metaKey;
		const tag = (e.target as HTMLElement)?.tagName;
		const inInput = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';

		// Ctrl+/ — focus chat input (works from anywhere)
		if (mod && e.key === '/') {
			e.preventDefault();
			focusTrigger.update((n) => n + 1);
			return;
		}

		// Don't fire other shortcuts while typing
		if (inInput) return;

		if (mod && e.shiftKey && e.key === 'N') {
			e.preventDefault();
			if (!$generating) newConversation();
		} else if (mod && e.shiftKey && e.key === 'T') {
			e.preventDefault();
			thinkingEnabled.update((v) => !v);
		} else if (mod && e.shiftKey && e.key === 'S') {
			e.preventDefault();
			sidebarOpen.update((v) => !v);
		}
	}

	onMount(() => {
		connect();
		loadConversations();
		document.addEventListener('keydown', handleChatKeydown);
		if (window.location.protocol === 'http:' && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
			showHttpBanner = true;
		}
	});

	onDestroy(() => {
		disconnect();
		document.removeEventListener('keydown', handleChatKeydown);
	});
</script>

<svelte:head>
	<title>Gizmo-AI</title>
</svelte:head>

<div class="console-frame flex flex-col flex-1 overflow-hidden">
	{#if $theme !== 'default'}<div class="cd" aria-hidden="true"><span></span><span></span><span></span><span></span></div>{/if}
	<ConsoleButtons />
	{#if showHttpBanner}
		<div class="bg-amber-500/10 border-b border-amber-500/30 px-4 py-2 text-xs text-amber-400 flex items-center justify-between">
			<span>Mic & voice features require HTTPS. Access Gizmo via Tailscale HTTPS or localhost for full access.</span>
			<button onclick={() => showHttpBanner = false} class="text-amber-500/60 hover:text-amber-400 ml-2 p-0.5" aria-label="Dismiss">
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
			</button>
		</div>
	{/if}
	<Header />
	<div class="console-screen flex flex-1 overflow-hidden">
		<Sidebar />
		<main class="flex flex-col flex-1 overflow-hidden">
			<ChatArea />
			<ChatInput />
		</main>
	</div>
</div>

<VoiceStudio bind:open={$voiceStudioOpen} />
<MemoryManager />
<CodePlayground />
