<script lang="ts">
	import hljs from 'highlight.js';

	let { code, language = '' }: { code: string; language?: string } = $props();
	let copied = $state(false);

	let highlighted = $derived(() => {
		try {
			if (language && hljs.getLanguage(language)) {
				return hljs.highlight(code, { language }).value;
			}
			return hljs.highlightAuto(code).value;
		} catch {
			return code.replace(/</g, '&lt;').replace(/>/g, '&gt;');
		}
	});

	async function copy() {
		await navigator.clipboard.writeText(code);
		copied = true;
		setTimeout(() => (copied = false), 2000);
	}
</script>

<div class="relative group rounded bg-code-bg border border-border my-2">
	<div class="flex items-center justify-between px-3 py-1 border-b border-border">
		<span class="text-xs text-text-dim font-mono">{language || 'code'}</span>
		<button
			onclick={copy}
			class="text-xs text-text-dim hover:text-text-primary transition-colors"
		>
			{copied ? 'Copied!' : 'Copy'}
		</button>
	</div>
	<pre class="overflow-x-auto p-3 text-sm leading-relaxed font-mono"><code class="hljs">{@html highlighted()}</code></pre>
</div>
