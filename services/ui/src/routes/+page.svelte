<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import Header from '$lib/components/Header.svelte';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import ChatArea from '$lib/components/ChatArea.svelte';
	import ChatInput from '$lib/components/ChatInput.svelte';
	import Settings from '$lib/components/Settings.svelte';
	import { connect, disconnect } from '$lib/ws/client';
	import { loadConversations } from '$lib/stores/chat';

	onMount(() => {
		connect();
		loadConversations();
	});

	onDestroy(() => {
		disconnect();
	});
</script>

<svelte:head>
	<title>Gizmo-AI</title>
</svelte:head>

<div class="flex flex-col h-screen bg-bg-primary">
	<Header />
	<div class="flex flex-1 overflow-hidden">
		<Sidebar />
		<main class="flex flex-col flex-1 overflow-hidden">
			<ChatArea />
			<ChatInput />
		</main>
	</div>
</div>

<Settings />
