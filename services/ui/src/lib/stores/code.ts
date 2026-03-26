import { writable } from 'svelte/store';

function persistedWritable<T>(key: string, defaultValue: T) {
	const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null;
	let initial = defaultValue;
	if (stored !== null) {
		try { initial = JSON.parse(stored); } catch { /* corrupt localStorage */ }
	}
	const store = writable<T>(initial);
	if (typeof localStorage !== 'undefined') {
		store.subscribe((value) => localStorage.setItem(key, JSON.stringify(value)));
	}
	return store;
}

export type Language = 'python' | 'javascript' | 'bash' | 'c' | 'cpp' | 'go' | 'lua' | 'html' | 'css' | 'svg' | 'markdown';

export const EXECUTABLE_LANGUAGES: Language[] = ['python', 'javascript', 'bash', 'c', 'cpp', 'go', 'lua'];
export const MARKUP_LANGUAGES: Language[] = ['html', 'css', 'svg', 'markdown'];
export const ALL_LANGUAGES: Language[] = [...EXECUTABLE_LANGUAGES, ...MARKUP_LANGUAGES];

export const LANGUAGE_LABELS: Record<Language, string> = {
	python: 'Python',
	javascript: 'JavaScript',
	bash: 'Bash',
	c: 'C',
	cpp: 'C++',
	go: 'Go',
	lua: 'Lua',
	html: 'HTML',
	css: 'CSS',
	svg: 'SVG',
	markdown: 'Markdown',
};

export const PLACEHOLDERS: Record<Language, string> = {
	python: "# Python — numpy, pandas, matplotlib, sympy, scipy available\nprint('Hello, world!')",
	javascript: "// JavaScript (Node.js)\nconsole.log('Hello, world!');",
	bash: "#!/bin/bash\necho 'Hello, world!'",
	c: '#include <stdio.h>\n\nint main() {\n    printf("Hello, world!\\n");\n    return 0;\n}',
	cpp: '#include <iostream>\n\nint main() {\n    std::cout << "Hello, world!" << std::endl;\n    return 0;\n}',
	go: 'package main\n\nimport "fmt"\n\nfunc main() {\n    fmt.Println("Hello, world!")\n}',
	lua: "-- Lua\nprint('Hello, world!')",
	html: '<!DOCTYPE html>\n<html>\n<body>\n    <h1>Hello, world!</h1>\n</body>\n</html>',
	css: 'body {\n    background: #1a1a2e;\n    display: flex;\n    align-items: center;\n    justify-content: center;\n    height: 100vh;\n    margin: 0;\n}\n\nh1 {\n    color: #c8a0ff;\n    font-family: sans-serif;\n}',
	svg: '<svg width="200" height="200" xmlns="http://www.w3.org/2000/svg">\n    <circle cx="100" cy="100" r="80" fill="#d4a574" />\n    <text x="100" y="110" text-anchor="middle" fill="white" font-size="24">Gizmo</text>\n</svg>',
	markdown: '# Hello World\n\nThis is **bold** and this is *italic*.\n\n- Item 1\n- Item 2\n\n```python\nprint("code block")\n```',
};

// Per-language code persistence
export const codeLanguage = persistedWritable<Language>('gizmo:code:lang', 'python');
export const codeChatOpen = writable(false);

// Store code per language in localStorage
export function saveCode(lang: Language, code: string) {
	if (typeof localStorage !== 'undefined') {
		localStorage.setItem(`gizmo:code:${lang}`, code);
	}
}

export function loadCode(lang: Language): string {
	if (typeof localStorage !== 'undefined') {
		return localStorage.getItem(`gizmo:code:${lang}`) || '';
	}
	return '';
}
