<script lang="ts">
	import { theme } from '$lib/stores/theme';
	import { newConversation } from '$lib/stores/chat';
	import { settingsOpen, sidebarOpen } from '$lib/stores/settings';

	// Button definitions per theme: [label, position CSS, action]
	interface ConsoleBtn {
		label: string;
		style: string;
		action: () => void;
	}

	function replayBoot() {
		// Signal BootSequence to replay via custom event (avoids fragile theme toggle)
		document.dispatchEvent(new CustomEvent('gizmo:replay-boot'));
	}

	function openFilePicker() {
		// Dispatch event that ChatInput listens for to trigger its file upload
		document.dispatchEvent(new CustomEvent('gizmo:upload'));
	}

	let buttons = $derived.by((): ConsoleBtn[] => {
		switch ($theme) {
			case 'nes':
				return [
					{ label: 'Power — Replay boot', style: 'bottom: 12px; left: 54px; width: 24px; height: 10px;', action: replayBoot },
					{ label: 'Reset — New conversation', style: 'bottom: 12px; left: 86px; width: 24px; height: 10px;', action: () => newConversation() },
				];
			case 'snes':
				return [
					{ label: 'Power — Replay boot', style: 'bottom: 14px; left: 36px; width: 34px; height: 10px;', action: replayBoot },
					{ label: 'Eject — Upload file', style: 'top: 10px; left: 34px; width: 22px; height: 8px;', action: openFilePicker },
				];
			case 'gba':
				return [
					{ label: 'Power — Replay boot', style: 'top: 17px; right: 48px; width: 10px; height: 10px; border-radius: 50%;', action: replayBoot },
				];
			case 'n64':
				return [
					{ label: 'Power — Replay boot', style: 'bottom: 12px; left: 34px; width: 16px; height: 16px; border-radius: 50%;', action: replayBoot },
					{ label: 'Reset — New conversation', style: 'bottom: 14px; left: 58px; width: 14px; height: 10px;', action: () => newConversation() },
				];
			case 'gamecube':
				return [
					{ label: 'Power — Replay boot', style: 'bottom: 10px; left: 30px; width: 14px; height: 14px; border-radius: 50%;', action: replayBoot },
					{ label: 'Disc cover — Upload file', style: 'top: 8px; right: 22px; width: 32px; height: 32px; border-radius: 50%;', action: openFilePicker },
				];
			case 'wii':
				return [
					{ label: 'Power — Replay boot', style: 'bottom: 10px; left: 50%; transform: translateX(-50%); width: 8px; height: 8px; border-radius: 50%;', action: replayBoot },
					{ label: 'Eject — Upload file', style: 'top: 10px; left: 25%; right: 25%; height: 4px;', action: openFilePicker },
					{ label: 'Sync — New conversation', style: 'bottom: 8px; right: 32px; width: 10px; height: 10px; border-radius: 50%;', action: () => newConversation() },
				];
			case 'switch':
				return [
					{ label: 'Plus — Settings', style: 'top: 50%; right: 12px; width: 16px; height: 16px;', action: () => settingsOpen.set(true) },
					{ label: 'Minus — Toggle sidebar', style: 'top: 52%; left: 12px; width: 16px; height: 16px;', action: () => sidebarOpen.update(v => !v) },
				];
			case 'ds':
				return [
					{ label: 'Power — Replay boot', style: 'top: 6px; left: 50%; transform: translateX(-50%); width: 12px; height: 12px; border-radius: 50%;', action: replayBoot },
				];
			case '3ds':
				return [
					{ label: 'Home — New conversation', style: 'bottom: 8px; left: 50%; transform: translateX(-50%); width: 14px; height: 14px; border-radius: 50%;', action: () => newConversation() },
					{ label: 'Power — Replay boot', style: 'top: 6px; left: 50%; transform: translateX(-50%); width: 10px; height: 10px; border-radius: 50%;', action: replayBoot },
				];
			default:
				return [];
		}
	});
</script>

{#if $theme !== 'default' && buttons.length > 0}
	{#each buttons as btn}
		<button
			onclick={btn.action}
			style="position: absolute; {btn.style} z-index: 10; background: transparent; border: none; cursor: pointer; padding: 0;"
			class="console-btn"
			aria-label={btn.label}
			title={btn.label}
		></button>
	{/each}
{/if}

<style>
	.console-btn {
		transition: box-shadow 0.2s ease;
	}
	.console-btn:hover {
		box-shadow: 0 0 8px 2px rgba(255, 255, 255, 0.15);
	}
	.console-btn:active {
		box-shadow: 0 0 4px 1px rgba(255, 255, 255, 0.1);
	}

	@media (max-width: 640px) {
		.console-btn { display: none; }
	}
</style>
