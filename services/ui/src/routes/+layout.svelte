<script lang="ts">
	import '../app.css';
	import 'highlight.js/styles/github-dark-dimmed.css';
	import Toast from '$lib/components/Toast.svelte';
	import Settings from '$lib/components/Settings.svelte';
	import IconRail from '$lib/components/IconRail.svelte';
	import MobileNav from '$lib/components/MobileNav.svelte';
	import BootSequence from '$lib/components/BootSequence.svelte';
	import { theme } from '$lib/stores/theme';
	import { settingsOpen, voiceStudioOpen, memoryManagerOpen, modeEditorOpen, codePlaygroundOpen, sidebarOpen } from '$lib/stores/settings';
	import ModeEditor from '$lib/components/ModeEditor.svelte';
	import { trackerChatOpen, selectedTaskId, selectedNoteId } from '$lib/stores/tracker';
	import { onMount } from 'svelte';
	import { get } from 'svelte/store';

	let { children } = $props();

	// On mobile first visit, close sidebar
	onMount(() => {
		if (window.innerWidth < 768 && get(sidebarOpen)) {
			sidebarOpen.set(false);
		}
	});

	// Apply theme to <html> element reactively
	$effect(() => {
		const t = $theme;
		if (t === 'default') {
			delete document.documentElement.dataset.theme;
		} else {
			document.documentElement.dataset.theme = t;
		}
	});

	function handleGlobalKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			if ($settingsOpen) { settingsOpen.set(false); e.preventDefault(); return; }
			if ($voiceStudioOpen) { voiceStudioOpen.set(false); e.preventDefault(); return; }
			if ($modeEditorOpen) { modeEditorOpen.set(false); e.preventDefault(); return; }
			if ($memoryManagerOpen) { memoryManagerOpen.set(false); e.preventDefault(); return; }
			if ($codePlaygroundOpen) { codePlaygroundOpen.set(false); e.preventDefault(); return; }
			if ($trackerChatOpen) { trackerChatOpen.set(false); e.preventDefault(); return; }
			if ($selectedTaskId) { selectedTaskId.set(null); e.preventDefault(); return; }
			if ($selectedNoteId) { selectedNoteId.set(null); e.preventDefault(); return; }
			if ($sidebarOpen) { sidebarOpen.set(false); e.preventDefault(); return; }
		}
	}
</script>

<svelte:window onkeydown={handleGlobalKeydown} />

<div class="flex flex-col md:flex-row h-dvh bg-bg-primary">
	<div class="hidden md:flex">
		<IconRail />
	</div>
	<div class="flex-1 flex flex-col overflow-hidden min-h-0">
		{@render children()}
	</div>
	<MobileNav />
</div>

<Settings />
<ModeEditor />
<Toast />
<BootSequence />
