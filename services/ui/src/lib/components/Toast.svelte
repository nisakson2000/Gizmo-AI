<script lang="ts">
	import { toasts, dismissToast } from '$lib/stores/toast';
</script>

{#if $toasts.length > 0}
	<div class="fixed bottom-4 right-4 z-[60] flex flex-col gap-2">
		{#each $toasts as t (t.id)}
			<div
				class="flex items-center gap-2 px-4 py-2.5 rounded-lg border shadow-lg text-sm min-w-[240px] max-w-[380px] toast-appear
					{t.type === 'success' ? 'bg-success/10 border-success/30 text-success' :
					 t.type === 'error' ? 'bg-error/10 border-error/30 text-error' :
					 'bg-accent/10 border-accent/30 text-accent'}"
			>
				{#if t.type === 'success'}
					<svg class="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
					</svg>
				{:else if t.type === 'error'}
					<svg class="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
					</svg>
				{:else}
					<svg class="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
					</svg>
				{/if}
				<span class="flex-1">{t.message}</span>
				<button
					onclick={() => dismissToast(t.id)}
					class="flex-shrink-0 opacity-60 hover:opacity-100 transition-opacity"
					aria-label="Dismiss"
				>
					<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
					</svg>
				</button>
			</div>
		{/each}
	</div>
{/if}
