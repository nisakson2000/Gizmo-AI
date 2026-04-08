<script lang="ts">
	import { activeMode } from '$lib/stores/settings';
	import { playSelect } from '$lib/utils/sounds';

	interface ModeInfo {
		name: string;
		label: string;
		description: string;
		icon: string;
		order: number;
	}

	let modes = $state<ModeInfo[]>([]);
	let open = $state(false);
	let dropdownEl: HTMLDivElement;

	$effect(() => {
		fetchModes();
	});

	// Close dropdown on outside click
	$effect(() => {
		if (!open) return;
		const handler = (e: MouseEvent) => {
			if (dropdownEl && !dropdownEl.contains(e.target as Node)) {
				open = false;
			}
		};
		document.addEventListener('click', handler, true);
		return () => document.removeEventListener('click', handler, true);
	});

	async function fetchModes() {
		try {
			const resp = await fetch('/api/modes');
			if (resp.ok) modes = await resp.json();
		} catch {
			// Modes will show just the current selection
		}
	}

	function selectMode(name: string) {
		playSelect();
		activeMode.set(name);
		open = false;
	}

	let currentLabel = $derived(modes.find((m) => m.name === $activeMode)?.label ?? 'Chat');
	let isDefault = $derived($activeMode === 'chat');

	const icons: Record<string, string> = {
		chat: 'M8.625 9.75a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375m-13.5 3.01c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.184-4.183a1.14 1.14 0 01.778-.332 48.294 48.294 0 005.83-.498c1.585-.233 2.708-1.626 2.708-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z',
		lightbulb: 'M12 18v-5.25m0 0a6.01 6.01 0 001.5-.189m-1.5.189a6.01 6.01 0 01-1.5-.189m3.75 7.478a12.06 12.06 0 01-4.5 0m3.75 2.383a14.406 14.406 0 01-3 0M14.25 18v-.192c0-.983.658-1.823 1.508-2.316a7.5 7.5 0 10-7.517 0c.85.493 1.509 1.333 1.509 2.316V18',
		code: 'M17.25 6.75L22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3l-4.5 16.5',
		search: 'M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z',
		clipboard: 'M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 002.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 00-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 00.75-.75 2.25 2.25 0 00-.1-.664m-5.8 0A2.251 2.251 0 0113.5 2.25H15a2.25 2.25 0 012.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25',
		theater: 'M15.182 15.182a4.5 4.5 0 01-6.364 0M21 12a9 9 0 11-18 0 9 9 0 0118 0zM9.75 9.75c0 .414-.168.75-.375.75S9 10.164 9 9.75 9.168 9 9.375 9s.375.336.375.75zm-.375 0h.008v.015h-.008V9.75zm5.625 0c0 .414-.168.75-.375.75s-.375-.336-.375-.75.168-.75.375-.75.375.336.375.75zm-.375 0h.008v.015h-.008V9.75z',
		custom: 'M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 010 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.281z',
	};

	function iconPath(icon: string): string {
		return icons[icon] || icons.custom;
	}
</script>

<div class="relative" bind:this={dropdownEl}>
	<button
		onclick={() => (open = !open)}
		class="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium transition-all {isDefault
			? 'bg-bg-secondary text-text-dim border border-border/50 hover:border-border hover:text-text-secondary'
			: 'bg-accent text-white'}"
		aria-label="Switch mode"
	>
		<svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
			<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d={iconPath(modes.find((m) => m.name === $activeMode)?.icon ?? 'chat')} />
		</svg>
		{currentLabel}
		<svg class="w-2.5 h-2.5 transition-transform {open ? 'rotate-180' : ''}" fill="none" stroke="currentColor" viewBox="0 0 24 24">
			<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
		</svg>
	</button>

	{#if open}
		<div class="absolute bottom-full left-0 mb-2 w-64 bg-bg-secondary border border-border/60 rounded-xl shadow-2xl overflow-hidden z-50">
			{#each modes as m (m.name)}
				<button
					onclick={() => selectMode(m.name)}
					class="w-full flex items-start gap-3 px-3.5 py-2.5 hover:bg-bg-tertiary/50 transition-colors text-left"
				>
					<svg class="w-4 h-4 mt-0.5 flex-shrink-0 {$activeMode === m.name ? 'text-accent' : 'text-text-dim'}" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d={iconPath(m.icon)} />
					</svg>
					<div class="flex-1 min-w-0">
						<div class="flex items-center gap-2">
							<span class="text-sm font-medium {$activeMode === m.name ? 'text-accent' : 'text-text-primary'}">{m.label}</span>
							{#if $activeMode === m.name}
								<svg class="w-3.5 h-3.5 text-accent flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
									<path fill-rule="evenodd" d="M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" clip-rule="evenodd" />
								</svg>
							{/if}
						</div>
						<p class="text-[11px] text-text-dim leading-tight mt-0.5">{m.description}</p>
					</div>
				</button>
			{/each}
		</div>
	{/if}
</div>
