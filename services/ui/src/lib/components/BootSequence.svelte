<script lang="ts">
	import { theme } from '$lib/stores/theme';

	let visible = $state(false);
	let animating = $state(false);
	let currentTheme = $state('');
	let skipTimeout: ReturnType<typeof setTimeout> | null = null;

	function getSessionKey(t: string): string {
		return `gizmo:booted:${t}`;
	}

	function hasBooted(t: string): boolean {
		return typeof sessionStorage !== 'undefined' && sessionStorage.getItem(getSessionKey(t)) === '1';
	}

	function markBooted(t: string) {
		if (typeof sessionStorage !== 'undefined') {
			sessionStorage.setItem(getSessionKey(t), '1');
		}
	}

	function dismiss() {
		if (skipTimeout) { clearTimeout(skipTimeout); skipTimeout = null; }
		animating = false;
		setTimeout(() => { visible = false; }, 300); // fade-out duration
	}

	function triggerBoot(t: string) {
		currentTheme = t;
		visible = true;
		// Small delay to ensure DOM is rendered before animation starts
		requestAnimationFrame(() => { animating = true; });
		markBooted(t);
		// Auto-dismiss after animation duration
		skipTimeout = setTimeout(dismiss, 2800);
	}

	// Re-trigger boot sequence (called from ConsoleButtons)
	export function replayBoot() {
		const t = $theme;
		if (t === 'default') return;
		if (typeof sessionStorage !== 'undefined') {
			sessionStorage.removeItem(getSessionKey(t));
		}
		triggerBoot(t);
	}

	// Watch for theme changes
	$effect(() => {
		const t = $theme;
		if (t !== 'default' && !hasBooted(t)) {
			triggerBoot(t);
		}
	});
</script>

{#if visible}
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="boot-overlay fixed inset-0 z-[100] flex items-center justify-center cursor-pointer"
		class:boot-active={animating}
		class:boot-fade-out={!animating && visible}
		data-boot={currentTheme}
		onclick={dismiss}
	>
		<div class="boot-content">
			{#if currentTheme === 'nes'}
				<div class="boot-nes-bg"></div>
				<div class="boot-nes-text">Nintendo</div>
			{:else if currentTheme === 'snes'}
				<div class="boot-snes-text">Super Nintendo</div>
			{:else if currentTheme === 'gba'}
				<div class="boot-gba-line1">Game Boy</div>
				<div class="boot-gba-line2">ADVANCE</div>
			{:else if currentTheme === 'n64'}
				<div class="boot-n64-letter">N</div>
			{:else if currentTheme === 'gamecube'}
				<div class="boot-gc-cube"></div>
				<div class="boot-gc-letter">G</div>
			{:else if currentTheme === 'wii'}
				<div class="boot-wii-glow"></div>
				<div class="boot-wii-text">Wii</div>
				<div class="boot-wii-sub">Press anywhere to continue</div>
			{:else if currentTheme === 'switch'}
				<div class="boot-switch-left"></div>
				<div class="boot-switch-right"></div>
			{:else if currentTheme === 'ds'}
				<div class="boot-ds-top"></div>
				<div class="boot-ds-bottom"></div>
				<div class="boot-ds-text">Nintendo DS</div>
			{:else if currentTheme === '3ds'}
				<div class="boot-3ds-top"></div>
				<div class="boot-3ds-bottom"></div>
				<div class="boot-3ds-text">Nintendo 3DS</div>
			{/if}
		</div>
	</div>
{/if}

<style>
	.boot-overlay {
		opacity: 0;
		transition: opacity 300ms ease;
		background: #000;
	}
	.boot-active { opacity: 1; }
	.boot-fade-out { opacity: 0; }

	.boot-content {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		width: 100%;
		height: 100%;
	}

	/* ═══ NES ═══ */
	[data-boot="nes"] { background: #c0c0c0; }
	[data-boot="nes"] .boot-content { animation: nesFlicker 1.2s steps(1) forwards; }
	.boot-nes-bg { position: absolute; inset: 0; background: #c0c0c0; }
	.boot-nes-text {
		font-family: 'Press Start 2P', monospace;
		font-size: 28px;
		color: #e40000;
		opacity: 0;
		animation: nesFadeIn 0.8s 1.2s ease forwards;
		z-index: 1;
	}
	@keyframes nesFlicker {
		0% { opacity: 0; }
		10% { opacity: 1; }
		20% { opacity: 0; }
		30% { opacity: 1; }
		50% { opacity: 0.3; }
		60% { opacity: 1; }
		100% { opacity: 1; }
	}
	@keyframes nesFadeIn {
		from { opacity: 0; }
		to { opacity: 1; }
	}

	/* ═══ SNES ═══ */
	[data-boot="snes"] { background: #000; }
	.boot-snes-text {
		font-family: 'Press Start 2P', monospace;
		font-size: 22px;
		color: #c8a0ff;
		transform: scale(0.3) perspective(500px) rotateX(15deg);
		opacity: 0;
		animation: snesScale 1.5s 0.5s cubic-bezier(0.22, 1, 0.36, 1) forwards;
	}
	@keyframes snesScale {
		0% { opacity: 0; transform: scale(0.3) perspective(500px) rotateX(15deg); }
		40% { opacity: 1; }
		100% { opacity: 1; transform: scale(1) perspective(500px) rotateX(0deg); }
	}

	/* ═══ GBA ═══ */
	[data-boot="gba"] { background: #fff; animation: gbaFlash 0.3s ease; }
	.boot-gba-line1 {
		font-family: 'Press Start 2P', monospace;
		font-size: 20px;
		color: #1a0e40;
		transform: translateY(-40px);
		opacity: 0;
		animation: gbaDropIn 0.6s 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
	}
	.boot-gba-line2 {
		font-family: 'Press Start 2P', monospace;
		font-size: 14px;
		color: #4a3090;
		letter-spacing: 6px;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.2s ease forwards;
		margin-top: 8px;
	}
	@keyframes gbaFlash {
		0% { background: #fff; }
		50% { background: #fff; }
		100% { background: #fff; }
	}
	@keyframes gbaDropIn {
		0% { opacity: 0; transform: translateY(-40px); }
		100% { opacity: 1; transform: translateY(0); }
	}

	/* ═══ N64 ═══ */
	[data-boot="n64"] { background: #000; }
	.boot-n64-letter {
		font-family: 'Fredoka', sans-serif;
		font-size: 100px;
		font-weight: 700;
		background: linear-gradient(135deg, #e04040 0%, #40b040 33%, #4080e0 66%, #e0c040 100%);
		-webkit-background-clip: text;
		-webkit-text-fill-color: transparent;
		background-clip: text;
		animation: n64Spin 1.5s 0.3s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
		transform: rotateY(180deg) scale(0.5);
		opacity: 0;
	}
	@keyframes n64Spin {
		0% { opacity: 0; transform: rotateY(180deg) scale(0.5); }
		50% { opacity: 1; }
		100% { opacity: 1; transform: rotateY(0deg) scale(1); }
	}

	/* ═══ GameCube ═══ */
	[data-boot="gamecube"] { background: #000; }
	.boot-gc-cube {
		width: 40px;
		height: 40px;
		border: 3px solid #7b6fcf;
		border-radius: 4px;
		animation: gcSpin 1.2s 0.3s ease forwards;
		transform: rotate(0deg) scale(0.3);
		opacity: 0;
		position: absolute;
	}
	.boot-gc-letter {
		font-family: 'Nunito', sans-serif;
		font-size: 60px;
		font-weight: 700;
		color: #7b6fcf;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.6s ease forwards;
	}
	@keyframes gcSpin {
		0% { opacity: 0; transform: rotate(0deg) scale(0.3); }
		50% { opacity: 1; transform: rotate(540deg) scale(1.5); }
		80% { opacity: 1; transform: rotate(720deg) scale(1.2); }
		100% { opacity: 0; transform: rotate(720deg) scale(0); }
	}

	/* ═══ Wii ═══ */
	[data-boot="wii"] { background: #fff; }
	.boot-wii-glow {
		position: absolute;
		width: 120px;
		height: 120px;
		border-radius: 50%;
		background: radial-gradient(circle, rgba(0,136,204,0.3) 0%, transparent 70%);
		animation: wiiPulse 1.5s 0.3s ease infinite;
		opacity: 0;
	}
	.boot-wii-text {
		font-family: system-ui, sans-serif;
		font-size: 48px;
		font-weight: 300;
		color: #555;
		letter-spacing: 4px;
		opacity: 0;
		animation: nesFadeIn 0.8s 0.8s ease forwards;
		z-index: 1;
	}
	.boot-wii-sub {
		font-family: system-ui, sans-serif;
		font-size: 11px;
		color: #aaa;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.6s ease forwards;
		margin-top: 16px;
		z-index: 1;
	}
	@keyframes wiiPulse {
		0% { opacity: 0; transform: scale(0.5); }
		50% { opacity: 1; transform: scale(1.5); }
		100% { opacity: 0; transform: scale(2.5); }
	}

	/* ═══ Switch ═══ */
	[data-boot="switch"] { background: #1a1a1a; }
	.boot-switch-left, .boot-switch-right {
		position: absolute;
		top: 0;
		bottom: 0;
		width: 50%;
	}
	.boot-switch-left {
		left: -50%;
		background: linear-gradient(90deg, #e60012, #ff2a3e);
		animation: switchSlideLeft 0.8s 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
	}
	.boot-switch-right {
		right: -50%;
		background: linear-gradient(90deg, #00c3e3, #009ab8);
		animation: switchSlideRight 0.8s 0.5s cubic-bezier(0.34, 1.56, 0.64, 1) forwards;
	}
	@keyframes switchSlideLeft {
		0% { left: -50%; }
		70% { left: 1%; }
		100% { left: 0%; }
	}
	@keyframes switchSlideRight {
		0% { right: -50%; }
		70% { right: 1%; }
		100% { right: 0%; }
	}

	/* ═══ DS ═══ */
	[data-boot="ds"] { background: #b8b8c0; }
	.boot-ds-top, .boot-ds-bottom {
		width: 200px;
		height: 80px;
		border: 3px solid #888;
		border-radius: 4px;
		background: #111;
	}
	.boot-ds-top {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.5s ease forwards;
		box-shadow: 0 0 20px rgba(48,112,208,0.3);
	}
	.boot-ds-bottom {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.9s ease forwards;
		margin-top: 12px;
		box-shadow: 0 0 20px rgba(48,112,208,0.3);
	}
	.boot-ds-text {
		font-family: system-ui, sans-serif;
		font-size: 14px;
		font-weight: 600;
		color: #555;
		letter-spacing: 2px;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.4s ease forwards;
		margin-top: 16px;
	}
	@keyframes dsScreenOn {
		0% { opacity: 0; box-shadow: none; }
		50% { opacity: 1; box-shadow: 0 0 30px rgba(48,112,208,0.5); }
		100% { opacity: 1; box-shadow: 0 0 15px rgba(48,112,208,0.2); }
	}

	/* ═══ 3DS ═══ */
	[data-boot="3ds"] { background: #151e28; }
	.boot-3ds-top, .boot-3ds-bottom {
		width: 200px;
		height: 80px;
		border: 2px solid #2a3a4a;
		border-radius: 4px;
		background: #0a1018;
	}
	.boot-3ds-top {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.5s ease forwards;
		box-shadow: 0 0 20px rgba(0,188,212,0.2);
		width: 220px;
	}
	.boot-3ds-bottom {
		opacity: 0;
		animation: dsScreenOn 0.4s 0.9s ease forwards;
		margin-top: 8px;
		box-shadow: 0 0 20px rgba(0,188,212,0.2);
	}
	.boot-3ds-text {
		font-family: 'Nunito', sans-serif;
		font-size: 14px;
		font-weight: 700;
		color: #00bcd4;
		letter-spacing: 2px;
		opacity: 0;
		animation: nesFadeIn 0.5s 1.4s ease forwards;
		margin-top: 12px;
		text-shadow: 1px 1px 0 rgba(0,188,212,0.15);
	}

	/* Mobile: skip boot */
	@media (max-width: 640px) {
		.boot-overlay { display: none !important; }
	}
</style>
