<script lang="ts">
	import {
		conversations,
		activeConversationId,
		newConversation,
		loadConversation,
		deleteConversation,
	} from '$lib/stores/chat';
	import { sidebarOpen, settingsOpen } from '$lib/stores/settings';

	let search = $state('');

	let filtered = $derived(
		$conversations.filter((c) => c.title.toLowerCase().includes(search.toLowerCase()))
	);

	function formatTime(ts: string): string {
		try {
			const d = new Date(ts);
			const now = new Date();
			const diff = now.getTime() - d.getTime();
			if (diff < 86400000) return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
			if (diff < 604800000) return d.toLocaleDateString([], { weekday: 'short' });
			return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
		} catch {
			return '';
		}
	}
</script>

{#if $sidebarOpen}
	<aside class="w-64 flex-shrink-0 bg-bg-secondary border-r border-border flex flex-col h-full">
		<div class="p-3">
			<button
				onclick={() => newConversation()}
				class="w-full px-3 py-2 rounded bg-accent hover:bg-accent-dim text-white text-sm font-medium transition-colors"
			>
				+ New Chat
			</button>
		</div>

		<div class="px-3 pb-2">
			<input
				type="text"
				placeholder="Search..."
				bind:value={search}
				class="w-full px-2.5 py-1.5 bg-bg-tertiary border border-border rounded text-sm text-text-primary placeholder:text-text-dim focus:outline-none focus:border-accent-dim"
			/>
		</div>

		<div class="flex-1 overflow-y-auto px-2">
			{#each filtered as conv (conv.id)}
				<div
					role="button"
					tabindex="0"
					onclick={() => loadConversation(conv.id)}
					onkeydown={(e) => { if (e.key === 'Enter') loadConversation(conv.id); }}
					class="w-full text-left px-3 py-2 rounded mb-0.5 flex items-center justify-between group transition-colors cursor-pointer {$activeConversationId === conv.id ? 'bg-bg-hover text-text-primary' : 'text-text-secondary hover:bg-bg-hover hover:text-text-primary'}"
				>
					<span class="truncate text-sm flex-1">{conv.title}</span>
					<span class="text-xs text-text-dim ml-2 flex-shrink-0">{formatTime(conv.updated_at)}</span>
					<button
						onclick={(e) => { e.stopPropagation(); deleteConversation(conv.id); }}
						class="ml-1 text-text-dim hover:text-error opacity-0 group-hover:opacity-100 transition-opacity"
						aria-label="Delete conversation"
					>
						<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
						</svg>
					</button>
				</div>
			{/each}
		</div>

		<div class="p-3 border-t border-border">
			<button
				onclick={() => settingsOpen.update((v) => !v)}
				class="w-full px-3 py-2 rounded text-sm text-text-secondary hover:bg-bg-hover hover:text-text-primary transition-colors text-left"
			>
				Settings
			</button>
		</div>
	</aside>
{/if}
