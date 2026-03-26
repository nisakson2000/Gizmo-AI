<script lang="ts">
	import '../app.css';
	import 'highlight.js/styles/github-dark-dimmed.css';
	import Toast from '$lib/components/Toast.svelte';
	import Settings from '$lib/components/Settings.svelte';
	import IconRail from '$lib/components/IconRail.svelte';
	import BootSequence from '$lib/components/BootSequence.svelte';
	import { theme } from '$lib/stores/theme';
	import { settingsOpen, voiceStudioOpen, memoryManagerOpen, codePlaygroundOpen } from '$lib/stores/settings';

	let { children } = $props();

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
			if ($memoryManagerOpen) { memoryManagerOpen.set(false); e.preventDefault(); return; }
			if ($codePlaygroundOpen) { codePlaygroundOpen.set(false); e.preventDefault(); return; }
		}
	}
</script>

<svelte:window onkeydown={handleGlobalKeydown} />

<div class="flex h-screen bg-bg-primary">
	<IconRail />
	<div class="flex-1 flex flex-col overflow-hidden">
		{@render children()}
	</div>
</div>

<Settings />
<Toast />
<BootSequence />
