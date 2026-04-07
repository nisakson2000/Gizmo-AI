<script lang="ts">
	import { onMount, onDestroy, tick } from 'svelte';
	import { marked } from 'marked';
	import { sanitize } from '$lib/utils/sanitize';
	import { toast } from '$lib/stores/toast';
	import { highlightCode } from '$lib/actions/highlight';
	import hljs from 'highlight.js';
	import ThinkingBlock from '$lib/components/ThinkingBlock.svelte';
	import ToolCallBlock from '$lib/components/ToolCallBlock.svelte';
	import {
		type Language,
		EXECUTABLE_LANGUAGES, MARKUP_LANGUAGES, ALL_LANGUAGES,
		LANGUAGE_LABELS, PLACEHOLDERS, LANGUAGE_EXTENSIONS, HLJS_NAMES,
		codeLanguage, codeChatOpen, splitPercent, wordWrap,
		saveCode, loadCode,
	} from '$lib/stores/code';
	import {
		connectCodeChat, disconnectCodeChat, sendCodeMessage,
		codeMessages, codeGenerating, codeStreamingContent, codeStreamingThinking,
	} from '$lib/ws/code-client';

	let code = $state('');
	let stdinData = $state('');
	let stdinOpen = $state(false);
	let running = $state(false);
	let output = $state<{ stdout: string; stderr: string; exit_code: number; timed_out: boolean } | null>(null);
	let error = $state('');
	let timeout = $state(10);
	let copied = $state(false);
	let previewHtml = $state('');
	let chatInput = $state('');
	let messagesEl: HTMLDivElement | undefined = $state();

	let language = $derived($codeLanguage);
	let isMarkup = $derived(MARKUP_LANGUAGES.includes(language));

	// Syntax highlighting
	let highlightedHtml = $state('');
	let highlightTimer: ReturnType<typeof setTimeout> | null = null;
	let highlightEl: HTMLPreElement | undefined = $state();
	let codeCopied = $state(false);
	let savedFeedback = $state(false);
	let saveTimer: ReturnType<typeof setTimeout> | null = null;
	let dragging = $state(false);

	$effect(() => {
		const c = code;
		const lang = language;
		if (highlightTimer) clearTimeout(highlightTimer);
		if (!c) { highlightedHtml = ''; return; }
		highlightTimer = setTimeout(() => {
			try {
				const hljsLang = HLJS_NAMES[lang];
				if (hljs.getLanguage(hljsLang)) {
					highlightedHtml = hljs.highlight(c, { language: hljsLang }).value;
				} else {
					highlightedHtml = hljs.highlightAuto(c).value;
				}
			} catch {
				highlightedHtml = c.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
			}
			highlightTimer = null;
		}, 50);
	});

	// Auto-save (2s debounce)
	$effect(() => {
		const c = code;
		const lang = language;
		if (!c.trim()) return;
		if (saveTimer) clearTimeout(saveTimer);
		saveTimer = setTimeout(() => {
			saveCode(lang, c);
			savedFeedback = true;
			setTimeout(() => { savedFeedback = false; }, 1500);
			saveTimer = null;
		}, 2000);
		return () => { if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; } };
	});

	// Auto-detect if code reads from stdin
	let codeNeedsInput = $derived(() => {
		if (!code.trim() || isMarkup) return false;
		const t = code;
		return /\binput\s*\(/.test(t)           // Python
			|| /\bscanf\s*\(/.test(t)           // C
			|| /\bcin\s*>>/.test(t)              // C++
			|| /\bfmt\.Scan/.test(t)             // Go
			|| /\breadline\s*\(/.test(t)         // Node.js
			|| /\bio\.read/.test(t)              // Lua
			|| /\bread\s/.test(t);               // Bash
	});

	let lineNumbers = $derived(() => {
		const lines = code.split('\n').length;
		return Array.from({ length: Math.max(lines, 1) }, (_, i) => i + 1);
	});

	// Reset output when language changes — keep code intact (user may be correcting detection)
	let lastLang = $state<Language>('python');
	let pasteDetectedLangChange = false;
	$effect(() => {
		const lang = $codeLanguage;
		if (lang !== lastLang) {
			lastLang = lang;
			pasteDetectedLangChange = false;
			output = null;
			previewHtml = '';
			error = '';
		}
	});

	// Auto-expand stdin when code needs input
	$effect(() => {
		if (codeNeedsInput()) stdinOpen = true;
	});

	// Clear everything when code is emptied
	$effect(() => {
		if (!code.trim()) {
			stdinData = '';
			stdinOpen = false;
			output = null;
			error = '';
			previewHtml = '';
		}
	});

	// Live auto-preview for markup languages (debounced)
	let previewTimer: ReturnType<typeof setTimeout> | null = null;
	$effect(() => {
		if (isMarkup && code.trim()) {
			if (previewTimer) clearTimeout(previewTimer);
			previewTimer = setTimeout(renderPreview, 300);
		} else if (isMarkup) {
			previewHtml = '';
		}
	});

	// Auto-scroll chat
	$effect(() => {
		$codeMessages;
		$codeStreamingContent;
		tick().then(() => {
			if (messagesEl) messagesEl.scrollTop = messagesEl.scrollHeight;
		});
	});

	marked.setOptions({ breaks: true, gfm: true });

	function renderMarkdown(text: string): string {
		try { return sanitize(marked.parse(text) as string); } catch { return text; }
	}

	function renderPreview() {
		if (language === 'html') {
			previewHtml = code;
		} else if (language === 'css') {
			previewHtml = `<!DOCTYPE html><html><head><style>${code}</style></head><body><h1>Heading</h1><p>Paragraph text for preview.</p><button>Button</button><div class="box" style="width:100px;height:100px;margin:16px 0;"></div><ul><li>Item 1</li><li>Item 2</li></ul></body></html>`;
		} else if (language === 'svg') {
			previewHtml = `<!DOCTYPE html><html><body style="margin:0;display:flex;align-items:center;justify-content:center;height:100vh;background:#111">${code}</body></html>`;
		} else if (language === 'markdown') {
			try {
				const parsed = marked.parse(code) as string;
				previewHtml = `<!DOCTYPE html><html><head><style>body{font-family:system-ui,sans-serif;padding:24px;max-width:720px;margin:0 auto;color:#e0e0e0;background:#0d0d0d;line-height:1.7}code{background:#1a1a1a;padding:2px 6px;border-radius:4px;font-size:0.9em}pre{background:#1a1a1a;padding:16px;border-radius:8px;overflow-x:auto}pre code{background:none;padding:0}h1,h2,h3{color:#fff}a{color:#d4a574}blockquote{border-left:3px solid #d4a574;margin-left:0;padding-left:16px;color:#aaa}</style></head><body>${parsed}</body></html>`;
			} catch { previewHtml = code; }
		}
	}

	async function runCode() {
		if (!code.trim() || running) return;
		saveCode(language, code);

		if (isMarkup) {
			renderPreview();
			return;
		}

		running = true;
		error = '';
		output = null;
		try {
			const resp = await fetch('/api/run-code', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ code: code.trim(), language, timeout, stdin: stdinData }),
			});
			if (!resp.ok) {
				const err = await resp.json().catch(() => null);
				error = err?.error || `Execution failed (${resp.status})`;
				return;
			}
			output = await resp.json();
		} catch {
			error = 'Sandbox unavailable. Is the orchestrator running?';
		} finally {
			running = false;
		}
	}

	async function copyOutput() {
		if (!output) return;
		const text = [output.stdout, output.stderr].filter(Boolean).join('\n');
		await navigator.clipboard.writeText(text);
		copied = true;
		toast('Copied to clipboard', 'success');
		setTimeout(() => { copied = false; }, 1500);
	}

	async function copyCode() {
		await navigator.clipboard.writeText(code);
		codeCopied = true;
		toast('Copied to clipboard', 'success');
		setTimeout(() => { codeCopied = false; }, 1500);
	}

	function downloadCode() {
		const ext = LANGUAGE_EXTENSIONS[language] || '.txt';
		const blob = new Blob([code], { type: 'text/plain' });
		const url = URL.createObjectURL(blob);
		const a = document.createElement('a');
		a.href = url;
		a.download = 'code' + ext;
		a.click();
		URL.revokeObjectURL(url);
	}

	function handleEditorKeydown(e: KeyboardEvent) {
		if (e.key === 'Tab') {
			e.preventDefault();
			const target = e.target as HTMLTextAreaElement;
			const start = target.selectionStart;
			const end = target.selectionEnd;
			code = code.substring(0, start) + '    ' + code.substring(end);
			requestAnimationFrame(() => {
				target.selectionStart = target.selectionEnd = start + 4;
			});
		}
		if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
			e.preventDefault();
			runCode();
		}
	}

	function detectLanguage(text: string): Language | null {
		const t = text.trim();
		// SVG (before HTML — SVG is valid HTML but more specific)
		if (t.includes('<svg') && t.includes('xmlns')) return 'svg';
		// HTML
		if (/^<!DOCTYPE/i.test(t) || /^<html/i.test(t) || (/^<\w/.test(t) && /<\/\w+>\s*$/.test(t))) return 'html';
		// Markdown (headings, bold, lists, code fences) — but not if it looks like code with comments
		const hasCodePatterns = /\b(import |from |def |class |print\(|printf\(|cout|func |console\.log|local\b)/.test(t);
		if (!hasCodePatterns && (/^#{1,3}\s/.test(t) || /\*\*\w/.test(t) || /^```\w/m.test(t) || /^- \w/m.test(t))) return 'markdown';
		// CSS (selector { property: value; })
		if (/[\w.#\-]+\s*\{[\s\S]*?:\s*[\s\S]*?;[\s\S]*?\}/.test(t) && !t.includes('int main') && !t.includes('#include')) return 'css';
		// C++ (before C — more specific)
		if (t.includes('#include <iostream>') || t.includes('std::') || t.includes('cout') || t.includes('cin')) return 'cpp';
		// C
		if (t.includes('#include <stdio.h>') || (t.includes('#include') && t.includes('printf('))) return 'c';
		// Go
		if (t.includes('package main') && t.includes('func ')) return 'go';
		// Bash
		if (t.startsWith('#!/bin/bash') || t.startsWith('#!/bin/sh') || (/^(echo|export|alias|source|chmod|mkdir|cd|ls|grep|awk|sed|cat|curl|wget)\b/m.test(t) && !t.includes('import'))) return 'bash';
		// JavaScript
		if (/\b(console\.log|document\.|window\.|const |let |var |=>|require\(|module\.exports)/.test(t)) return 'javascript';
		// Lua
		if (/\blocal\b/.test(t) && /\bfunction\b/.test(t) || /\bend\b/.test(t) && t.includes('print(') && !t.includes('import')) return 'lua';
		// Python (broad — acts as fallback for code-like text)
		if (/\b(import |from |def |class |print\(|if __name__)/.test(t)) return 'python';
		return null;
	}

	function handlePaste(e: ClipboardEvent) {
		const target = e.target as HTMLTextAreaElement;
		const allSelected = target.selectionStart === 0 && target.selectionEnd === code.length;
		// Detect when editor is empty or entire content is selected (about to be replaced)
		if (code.trim() && !allSelected) return;
		const pasted = e.clipboardData?.getData('text') || '';
		if (!pasted.trim()) return;
		const detected = detectLanguage(pasted);
		if (detected && detected !== language) {
			pasteDetectedLangChange = true;
			codeLanguage.set(detected);
		}
	}

	function handleChatKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			sendChat();
		}
	}

	function sendChat() {
		if (!chatInput.trim() || $codeGenerating) return;
		sendCodeMessage(chatInput.trim(), code, language);
		chatInput = '';
	}

	// Sync scroll between line numbers and textarea
	let editorEl: HTMLTextAreaElement | undefined = $state();
	let lineNumEl: HTMLDivElement | undefined = $state();
	function syncScroll() {
		if (editorEl && lineNumEl) {
			lineNumEl.scrollTop = editorEl.scrollTop;
		}
		if (editorEl && highlightEl) {
			highlightEl.scrollTop = editorEl.scrollTop;
			highlightEl.scrollLeft = editorEl.scrollLeft;
		}
	}

	// Resizable split pane
	function handleDragStart(e: PointerEvent) {
		const target = e.currentTarget as HTMLElement;
		target.setPointerCapture(e.pointerId);
		dragging = true;
		document.body.style.userSelect = 'none';
	}

	function handleDragMove(e: PointerEvent) {
		if (!dragging) return;
		const container = (e.currentTarget as HTMLElement).parentElement;
		if (!container) return;
		const rect = container.getBoundingClientRect();
		const pct = ((e.clientX - rect.left) / rect.width) * 100;
		splitPercent.set(Math.max(25, Math.min(85, pct)));
	}

	function handleDragEnd() {
		dragging = false;
		document.body.style.userSelect = '';
	}

	function resetSplit() {
		splitPercent.set(55);
	}

	onMount(() => {
		connectCodeChat();
		code = '';
		output = null;
		previewHtml = '';
		error = '';
	});

	onDestroy(() => {
		disconnectCodeChat();
	});
</script>

<svelte:head>
	<title>Gizmo-AI — Code</title>
</svelte:head>

<div class="console-frame flex flex-col h-full bg-bg-primary">
	<!-- Header -->
	<header class="flex items-center justify-between px-5 py-2.5 border-b border-border/60 bg-bg-secondary/50 shrink-0">
		<div class="flex items-center gap-3">
			<div class="w-8 h-8 rounded-lg bg-accent/15 flex items-center justify-center">
				<svg class="w-4 h-4 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17.25 6.75L22.5 12l-5.25 5.25m-10.5 0L1.5 12l5.25-5.25m7.5-3l-4.5 16.5" />
				</svg>
			</div>
			<span class="text-sm font-semibold text-text-primary">Code Playground</span>

			<select
				value={language}
				onchange={(e) => codeLanguage.set((e.target as HTMLSelectElement).value as Language)}
				class="text-xs bg-bg-tertiary/50 text-text-secondary rounded-lg px-2.5 py-1.5 border border-border/30 outline-none cursor-pointer hover:border-border/50 transition-colors"
			>
				<optgroup label="Executable">
					{#each EXECUTABLE_LANGUAGES as lang}
						<option value={lang}>{LANGUAGE_LABELS[lang]}</option>
					{/each}
				</optgroup>
				<optgroup label="Preview">
					{#each MARKUP_LANGUAGES as lang}
						<option value={lang}>{LANGUAGE_LABELS[lang]}</option>
					{/each}
				</optgroup>
			</select>

			<button
				onclick={copyCode}
				class="p-1.5 text-text-dim hover:text-text-secondary transition-colors rounded"
				aria-label="Copy code"
				title="Copy code"
			>
				{#if codeCopied}
					<svg class="w-3.5 h-3.5 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
					</svg>
				{:else}
					<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9.75a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184" />
					</svg>
				{/if}
			</button>
			<button
				onclick={downloadCode}
				class="p-1.5 text-text-dim hover:text-text-secondary transition-colors rounded"
				aria-label="Download code"
				title="Download code"
				disabled={!code.trim()}
			>
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
				</svg>
			</button>
			<button
				onclick={() => wordWrap.update(v => !v)}
				class="p-1.5 rounded transition-colors {$wordWrap ? 'text-accent' : 'text-text-dim hover:text-text-secondary'}"
				aria-label="Toggle word wrap"
				title="Toggle word wrap"
			>
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 10h10a4 4 0 010 8H9m4 0l-3-3m3 3l-3 3M3 6h18M3 14h4" />
				</svg>
			</button>
		</div>

		<div class="flex items-center gap-2">
			{#if !isMarkup}
				<div class="flex gap-1 mr-2">
					{#each [5, 10, 20, 30] as t}
						<button
							onclick={() => timeout = t}
							class="px-2 py-0.5 text-[10px] rounded-md font-medium transition-all {timeout === t
								? 'bg-accent text-white'
								: 'bg-bg-tertiary/50 text-text-dim border border-border/30 hover:border-border/50'}"
						>{t}s</button>
					{/each}
				</div>
			{/if}

			{#if !isMarkup}
				<button
					onclick={runCode}
					disabled={!code.trim() || running}
					class="px-4 py-1.5 text-xs font-semibold rounded-lg transition-all
						{code.trim() && !running
							? 'bg-accent text-white hover:bg-accent-dim shadow-sm'
							: 'bg-bg-tertiary text-text-dim cursor-not-allowed'}"
				>
					{#if running}
						<span class="flex items-center gap-1.5">
							<svg class="w-3 h-3 animate-spin" fill="none" viewBox="0 0 24 24">
								<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
								<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
							</svg>
							Running
						</span>
					{:else}
						Run
					{/if}
				</button>
			{/if}

			<div class="w-px h-5 bg-border/30"></div>

			<button
				onclick={() => codeChatOpen.update(v => !v)}
				class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg border transition-all
					{$codeChatOpen
						? 'bg-accent/15 text-accent border-accent/30'
						: 'text-text-dim border-border/20 hover:text-text-secondary hover:bg-bg-hover hover:border-border/40'}"
			>
				<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
				</svg>
				Ask Gizmo
			</button>

			<span class="text-[10px] text-text-dim/40">Ctrl+Enter to run</span>
		</div>
	</header>

	<!-- Main area: split pane -->
	<div class="console-screen flex-1 overflow-hidden relative flex">
		<!-- Left: editor -->
		<div class="flex flex-col overflow-hidden border-r border-border/30 min-w-0" style="width: {$splitPercent}%">
			<div class="flex-1 flex overflow-hidden">
				<!-- Line numbers -->
				<div
					bind:this={lineNumEl}
					class="w-12 bg-bg-secondary/50 border-r border-border/20 overflow-hidden select-none shrink-0 pt-3 pr-2 text-right"
				>
					{#each lineNumbers() as num}
						<div class="text-[11px] leading-relaxed text-text-dim/30 font-mono h-[1.625rem]">{num}</div>
					{/each}
				</div>
				<!-- Layered editor -->
				<div class="editor-wrapper relative flex-1 overflow-hidden">
					<!-- Highlighted code layer (behind) -->
					<pre
						bind:this={highlightEl}
						class="code-layer absolute inset-0 overflow-auto pointer-events-none p-3 m-0 bg-transparent"
						style="white-space: {$wordWrap ? 'pre-wrap' : 'pre'}; word-break: {$wordWrap ? 'break-all' : 'normal'};"
					><code class="hljs">{@html highlightedHtml || '&nbsp;'}</code></pre>
					<!-- Textarea (front, transparent text, visible caret) -->
					<textarea
						bind:this={editorEl}
						bind:value={code}
						onscroll={syncScroll}
						onkeydown={handleEditorKeydown}
						onpaste={handlePaste}
						placeholder={PLACEHOLDERS[language]}
						spellcheck="false"
						class="code-layer absolute inset-0 resize-none bg-transparent caret-text-primary p-3 outline-none placeholder:text-text-dim/30 overflow-auto"
						style="tab-size: 4; -webkit-text-fill-color: transparent; color: transparent; white-space: {$wordWrap ? 'pre-wrap' : 'pre'}; word-break: {$wordWrap ? 'break-all' : 'normal'};"
					></textarea>
				</div>
			</div>

			<!-- Footer info -->
			<div class="flex items-center justify-between px-3 py-1.5 border-t border-border/20 bg-bg-secondary/30 text-[10px] text-text-dim/40">
				<span>{LANGUAGE_LABELS[language]}</span>
				<span>{savedFeedback ? 'Saved' : (isMarkup ? 'Client-side rendering' : 'Sandboxed execution — no network, 256MB RAM')}</span>
				<span>{code.split('\n').length} lines</span>
			</div>
		</div>

		<!-- Drag handle -->
		<div
			class="w-1 shrink-0 cursor-col-resize bg-border/30 hover:bg-accent/40 transition-colors {dragging ? 'bg-accent/60' : ''}"
			onpointerdown={handleDragStart}
			onpointermove={handleDragMove}
			onpointerup={handleDragEnd}
			ondblclick={resetSplit}
			role="separator"
			aria-label="Resize panels"
		></div>

		<!-- Right: I/O panel -->
		<div class="flex flex-col overflow-hidden min-w-[200px]" style="width: {100 - $splitPercent}%">
			<!-- Stdin section (executable languages only) -->
			{#if !isMarkup && (codeNeedsInput() || stdinOpen || stdinData.trim())}
				<div class="shrink-0 border-b border-border/30">
					<div class="flex items-center justify-between px-3 py-2 bg-bg-secondary/30">
						<div class="flex items-center gap-2">
							<span class="text-xs text-text-dim font-medium">Input</span>
							{#if stdinData.trim()}
								<span class="text-[9px] px-1.5 py-0.5 rounded bg-accent/15 text-accent font-medium">{stdinData.split('\n').length} line{stdinData.split('\n').length === 1 ? '' : 's'}</span>
							{/if}
						</div>
						{#if stdinData.trim()}
							<button
								onclick={() => stdinData = ''}
								class="text-[10px] text-text-dim hover:text-text-secondary transition-colors"
							>Clear</button>
						{/if}
					</div>
					<textarea
						bind:value={stdinData}
						placeholder="One value per line — each line feeds one input() call"
						rows={3}
						spellcheck="false"
						class="w-full resize-none bg-bg-primary/50 text-text-primary text-xs font-mono leading-relaxed px-3 py-2 outline-none placeholder:text-text-dim/30"
					></textarea>
				</div>
			{/if}

			{#if error}
				<div class="m-3 px-3 py-2 bg-error/10 border border-error/20 rounded-lg text-xs text-error">{error}</div>
			{/if}

			{#if output && !isMarkup}
				<!-- Executable output -->
				<div class="flex items-center justify-between px-3 py-2 border-b border-border/20 bg-bg-secondary/30">
					<span class="text-xs text-text-dim font-medium">Output</span>
					<div class="flex items-center gap-2">
						{#if output.timed_out}
							<span class="text-[10px] text-amber-400">timed out</span>
						{/if}
						<span class="text-[10px] font-mono {output.exit_code === 0 ? 'text-success' : 'text-error'}">
							exit {output.exit_code}
						</span>
						<button
							onclick={copyOutput}
							class="flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-medium transition-colors
								{copied ? 'text-success' : 'text-text-dim hover:text-text-primary hover:bg-bg-hover/50'}"
							aria-label="Copy output"
						>
							{#if copied}
								<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
								</svg>
								Copied
							{:else}
								<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9.75a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184" />
								</svg>
								Copy
							{/if}
						</button>
					</div>
				</div>
				<div class="flex-1 overflow-y-auto px-4 py-3">
					{#if output.stdout}
						<pre class="text-sm text-text-primary font-mono whitespace-pre-wrap leading-relaxed">{output.stdout}</pre>
					{/if}
					{#if output.stderr}
						<pre class="text-sm text-error/80 font-mono whitespace-pre-wrap leading-relaxed {output.stdout ? 'mt-2 pt-2 border-t border-border/20' : ''}">{output.stderr}</pre>
					{/if}
					{#if !output.stdout && !output.stderr}
						<p class="text-xs text-text-dim italic">No output</p>
					{/if}
					{#if output.output_files?.length}
						<div class="mt-3 pt-3 border-t border-border/20 space-y-2">
							<p class="text-xs text-text-dim font-medium">Generated Files</p>
							{#each output.output_files as file}
								{#if /\.(png|jpg|jpeg|svg|webp)$/i.test(file.filename)}
									<div>
										<img src={file.url} alt={file.filename} class="max-h-48 rounded-lg mb-1" />
										<a href={file.url} download={file.filename} class="text-xs text-accent hover:text-accent-dim">{file.filename}</a>
									</div>
								{:else}
									<a href={file.url} download={file.filename} class="flex items-center gap-1.5 text-xs text-accent hover:text-accent-dim">
										<svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
										</svg>
										{file.filename}
									</a>
								{/if}
							{/each}
						</div>
					{/if}
				</div>
			{:else if previewHtml && isMarkup}
				<!-- Markup preview -->
				<div class="flex items-center px-3 py-2 border-b border-border/20 bg-bg-secondary/30">
					<span class="text-xs text-text-dim font-medium">Preview</span>
				</div>
				<iframe
					srcdoc={previewHtml}
					sandbox="allow-scripts"
					class="flex-1 border-0 bg-white"
					title="Markup preview"
				></iframe>
			{:else}
				<!-- Empty state -->
				<div class="flex-1 flex flex-col items-center justify-center text-text-dim/40">
					<svg class="w-12 h-12 mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1" d="M5 3l14 9-14 9V3z" />
					</svg>
					<p class="text-sm">{isMarkup ? 'Start typing to see a live preview' : 'Click Run or press Ctrl+Enter'}</p>
				</div>
			{/if}
		</div>

		<!-- Overlay: AI Chat -->
		{#if $codeChatOpen}
			<div class="overlay-container overlay-fade overlay-chat">
				<div class="overlay-dim hidden sm:block" onclick={() => codeChatOpen.set(false)} role="presentation"></div>
				<div class="w-full sm:w-[400px] bg-bg-secondary border-l border-border/40 flex flex-col overflow-hidden overlay-slide">
					<!-- Chat header -->
					<div class="flex items-center justify-between px-4 py-3 border-b border-border/40 shrink-0">
						<div class="flex items-center gap-2">
							<svg class="w-4 h-4 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
							</svg>
							<span class="text-sm font-semibold text-text-primary">Code Assistant</span>
						</div>
						<button onclick={() => codeChatOpen.set(false)} class="text-text-dim hover:text-text-secondary transition-colors" aria-label="Close chat">
							<svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
							</svg>
						</button>
					</div>

					<!-- Chat messages -->
					<div bind:this={messagesEl} class="flex-1 overflow-y-auto p-4 space-y-3">
						{#each $codeMessages as msg (msg.id)}
							<div class="flex flex-col gap-1 {msg.role === 'user' ? 'items-end' : 'items-start'}">
								{#if msg.role === 'user'}
									<div class="bg-user-msg text-text-primary text-sm rounded-lg px-3 py-2 max-w-[90%]">{msg.content}</div>
								{:else}
									{#if msg.thinking}
										<div class="max-w-[90%]">
											<ThinkingBlock content={msg.thinking} />
										</div>
									{/if}
									{#if msg.toolCalls}
										{#each msg.toolCalls as tc}
											<div class="max-w-[90%]">
												<ToolCallBlock tool={tc.tool} status={tc.status} result={tc.result} />
											</div>
										{/each}
									{/if}
									<div class="prose-sm text-text-primary text-sm max-w-[90%] [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1 [&_code]:bg-code-bg [&_code]:px-1 [&_code]:rounded [&_code]:text-xs" use:highlightCode={msg.content}>
										{@html renderMarkdown(msg.content)}
									</div>
								{/if}
							</div>
						{/each}

						{#if $codeGenerating}
							<div class="flex flex-col gap-1 items-start">
								{#if $codeStreamingThinking}
									<div class="max-w-[90%]">
										<ThinkingBlock content={$codeStreamingThinking} streaming={true} />
									</div>
								{/if}
								{#if $codeStreamingContent}
									<div class="prose-sm text-text-primary text-sm max-w-[90%] [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1 [&_code]:bg-code-bg [&_code]:px-1 [&_code]:rounded [&_code]:text-xs" use:highlightCode={$codeStreamingContent}>
										{@html renderMarkdown($codeStreamingContent)}
									</div>
								{:else}
									<div class="flex gap-1 px-2 py-2">
										<span class="w-1.5 h-1.5 rounded-full bg-text-dim animate-pulse"></span>
										<span class="w-1.5 h-1.5 rounded-full bg-text-dim animate-pulse" style="animation-delay: 0.15s"></span>
										<span class="w-1.5 h-1.5 rounded-full bg-text-dim animate-pulse" style="animation-delay: 0.3s"></span>
									</div>
								{/if}
							</div>
						{/if}
					</div>

					<!-- Chat input -->
					<div class="border-t border-border/40 p-3 shrink-0">
						<div class="flex items-end gap-2">
							<textarea
								bind:value={chatInput}
								onkeydown={handleChatKeydown}
								placeholder="Ask about your code..."
								rows={1}
								class="flex-1 bg-bg-tertiary text-text-primary text-sm rounded-lg px-3 py-2 border border-border/40 outline-none focus:border-accent/60 resize-none max-h-24"
							></textarea>
							<button
								onclick={sendChat}
								disabled={!chatInput.trim() || $codeGenerating}
								class="p-2 rounded-lg transition-colors flex-shrink-0
									{chatInput.trim() && !$codeGenerating ? 'bg-accent text-bg-primary hover:bg-accent-dim' : 'bg-bg-tertiary text-text-dim cursor-not-allowed'}"
								aria-label="Send"
							>
								<svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24">
									<path d="M3.478 2.404a.75.75 0 0 0-.926.941l2.432 7.905H13.5a.75.75 0 0 1 0 1.5H4.984l-2.432 7.905a.75.75 0 0 0 .926.94 60.519 60.519 0 0 0 18.445-8.986.75.75 0 0 0 0-1.218A60.517 60.517 0 0 0 3.478 2.404Z" />
								</svg>
							</button>
						</div>
					</div>
				</div>
			</div>
		{/if}
	</div>
</div>

<style>
	.code-layer {
		font-family: var(--font-mono);
		font-size: 0.875rem;
		line-height: 1.625;
		tab-size: 4;
		word-wrap: break-word;
		overflow-wrap: break-word;
		border: none;
		margin: 0;
	}

	.code-layer code {
		font-family: inherit;
		font-size: inherit;
		line-height: inherit;
	}

	@keyframes overlayFade {
		from { opacity: 0; }
		to { opacity: 1; }
	}
	@keyframes overlaySlide {
		from { transform: translateX(100%); }
		to { transform: translateX(0); }
	}
	.overlay-container {
		position: absolute;
		inset: 0;
		z-index: 30;
		display: flex;
	}
	.overlay-fade {
		animation: overlayFade 0.15s ease-out;
	}
	.overlay-dim {
		flex: 1;
		background: rgba(0, 0, 0, 0.3);
	}
	.overlay-slide {
		animation: overlaySlide 0.2s ease-out;
	}
</style>
