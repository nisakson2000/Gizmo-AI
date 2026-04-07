interface SwipeOptions {
	onSwipeRight?: () => void;
	onSwipeLeft?: () => void;
}

export function swipe(node: HTMLElement, options: SwipeOptions) {
	let startX = 0;
	let startY = 0;
	let tracking = false;

	function handleTouchStart(e: TouchEvent) {
		const touch = e.touches[0];
		startX = touch.clientX;
		startY = touch.clientY;
		tracking = true;
	}

	function handleTouchEnd(e: TouchEvent) {
		if (!tracking) return;
		tracking = false;

		const touch = e.changedTouches[0];
		const deltaX = touch.clientX - startX;
		const deltaY = touch.clientY - startY;

		if (Math.abs(deltaX) < 50) return;
		if (Math.abs(deltaX) < 2 * Math.abs(deltaY)) return;

		if (deltaX > 0 && startX < 30 && options.onSwipeRight) {
			options.onSwipeRight();
		} else if (deltaX < 0 && options.onSwipeLeft) {
			options.onSwipeLeft();
		}
	}

	node.addEventListener('touchstart', handleTouchStart, { passive: true });
	node.addEventListener('touchend', handleTouchEnd, { passive: true });

	return {
		update(newOptions: SwipeOptions) {
			options = newOptions;
		},
		destroy() {
			node.removeEventListener('touchstart', handleTouchStart);
			node.removeEventListener('touchend', handleTouchEnd);
		},
	};
}
