import hljs from 'highlight.js';

export function highlightCode(node: HTMLElement, _html: string) {
	function process() {
		const blocks = node.querySelectorAll('pre code:not([data-highlighted])');
		blocks.forEach((block) => {
			const el = block as HTMLElement;
			el.setAttribute('data-highlighted', 'true');

			const langClass = Array.from(el.classList).find((c) => c.startsWith('language-'));
			const language = langClass?.replace('language-', '') || '';

			try {
				if (language && hljs.getLanguage(language)) {
					el.innerHTML = hljs.highlight(el.textContent || '', { language }).value;
				} else {
					el.innerHTML = hljs.highlightAuto(el.textContent || '').value;
				}
			} catch {
				// Keep original content
			}

			el.classList.add('hljs');

			const pre = el.parentElement;
			if (pre && !pre.querySelector('.code-header')) {
				const header = document.createElement('div');
				header.className = 'code-header';
				header.innerHTML = `
					<span class="code-lang">${language || 'code'}</span>
					<button class="code-copy">Copy</button>
				`;
				pre.insertBefore(header, pre.firstChild);

				const copyBtn = header.querySelector('.code-copy') as HTMLButtonElement;
				copyBtn.addEventListener('click', () => {
					navigator.clipboard.writeText(el.textContent || '');
					copyBtn.textContent = 'Copied!';
					setTimeout(() => (copyBtn.textContent = 'Copy'), 2000);
				});
			}
		});
	}

	process();

	return {
		update(_newHtml: string) {
			process();
		},
		destroy() {
			// Copy button listeners are GC'd when {@html} re-renders and removes the DOM nodes.
		},
	};
}
