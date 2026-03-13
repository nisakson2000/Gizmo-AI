<script lang="ts">
	import { thinkingEnabled, ttsEnabled, ttsVoice, contextLength, settingsOpen } from '$lib/stores/settings';

	interface ServiceHealth {
		[key: string]: { status: string; error?: string };
	}
	let health = $state<ServiceHealth>({});

	async function checkHealth() {
		try {
			const resp = await fetch('/api/services/health');
			if (resp.ok) health = await resp.json();
		} catch {
			health = {};
		}
	}

	$effect(() => {
		if ($settingsOpen) checkHealth();
	});
</script>

{#if $settingsOpen}
	<div class="fixed inset-0 bg-black/50 z-50 flex items-center justify-center" role="dialog">
		<div class="bg-bg-secondary border border-border rounded-lg w-full max-w-lg mx-4 max-h-[80vh] overflow-y-auto">
			<div class="flex items-center justify-between p-4 border-b border-border">
				<h2 class="text-lg font-semibold">Settings</h2>
				<button onclick={() => settingsOpen.set(false)} class="text-text-secondary hover:text-text-primary">
					<svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
					</svg>
				</button>
			</div>

			<div class="p-4 space-y-6">
				<!-- Thinking Mode -->
				<div>
					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium">Thinking Mode</span>
							<p class="text-xs text-text-secondary mt-0.5">Model reasons step-by-step before responding. Better for complex problems, slower for simple ones.</p>
						</div>
						<button
							onclick={() => thinkingEnabled.update((v) => !v)}
							class="w-10 h-5 rounded-full transition-colors {$thinkingEnabled ? 'bg-accent' : 'bg-bg-tertiary border border-border'}"
						>
							<div class="w-4 h-4 rounded-full bg-white transition-transform {$thinkingEnabled ? 'translate-x-5' : 'translate-x-0.5'}"></div>
						</button>
					</label>
				</div>

				<!-- TTS -->
				<div>
					<label class="flex items-center justify-between">
						<div>
							<span class="text-sm font-medium">Text-to-Speech</span>
							<p class="text-xs text-text-secondary mt-0.5">Gizmo will speak responses aloud via Kokoro TTS.</p>
						</div>
						<button
							onclick={() => ttsEnabled.update((v) => !v)}
							class="w-10 h-5 rounded-full transition-colors {$ttsEnabled ? 'bg-accent' : 'bg-bg-tertiary border border-border'}"
						>
							<div class="w-4 h-4 rounded-full bg-white transition-transform {$ttsEnabled ? 'translate-x-5' : 'translate-x-0.5'}"></div>
						</button>
					</label>
					{#if $ttsEnabled}
						<div class="mt-2">
							<label class="text-xs text-text-secondary">Voice</label>
							<select
								bind:value={$ttsVoice}
								class="mt-1 w-full bg-bg-tertiary border border-border rounded px-2 py-1 text-sm text-text-primary"
							>
								<option value="af_heart">Heart (warm, natural)</option>
								<option value="af_bella">Bella</option>
								<option value="af_nicole">Nicole</option>
								<option value="am_adam">Adam</option>
								<option value="am_michael">Michael</option>
							</select>
						</div>
					{/if}
				</div>

				<!-- Context Length -->
				<div>
					<label class="text-sm font-medium">Context Length: {$contextLength.toLocaleString()} tokens</label>
					<p class="text-xs text-text-secondary mt-0.5">How much conversation history the model can see. Higher = more context but slower.</p>
					<input
						type="range"
						min="2048"
						max="16384"
						step="1024"
						bind:value={$contextLength}
						class="w-full mt-2 accent-accent"
					/>
					<div class="flex justify-between text-xs text-text-dim">
						<span>2K</span>
						<span>16K</span>
					</div>
				</div>

				<!-- Service Health -->
				<div>
					<div class="flex items-center justify-between mb-2">
						<span class="text-sm font-medium">Service Health</span>
						<button onclick={checkHealth} class="text-xs text-accent hover:text-accent-dim">Refresh</button>
					</div>
					<div class="space-y-1">
						{#each Object.entries(health) as [name, info]}
							<div class="flex items-center justify-between bg-bg-tertiary rounded px-3 py-1.5">
								<span class="text-sm text-text-secondary">{name}</span>
								<span class="text-xs font-mono {info.status === 'ok' ? 'text-success' : 'text-error'}">
									{info.status}
								</span>
							</div>
						{/each}
					</div>
				</div>
			</div>
		</div>
	</div>
{/if}
