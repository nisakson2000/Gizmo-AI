import DOMPurify from 'dompurify';

export function sanitize(html: string): string {
	return DOMPurify.sanitize(html, {
		ADD_ATTR: ['class'],
		ADD_TAGS: ['svg', 'path', 'circle'],
	});
}
