package dev.cloudgram.client.restore;

import dev.cloudgram.core.config.ConfigStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One-time offer to import the snapshots that already exist in the bound chat.
 *
 * <p>Shown on the title screen when Cloudgram has just been installed on a machine whose chat
 * already holds backups. Declining only closes the prompt; the restore list stays reachable from
 * the Cloudgram button.
 */
public final class CloudgramImportPromptScreen extends Screen {
	private static final int COLOR_TITLE = 0xFFFFFFFF;
	private static final int COLOR_BODY = 0xFFA0A0A0;
	private static final int COLOR_DIM = 0xFF707070;
	private static final int BUTTON_HEIGHT = 20;

	private final Screen parent;
	private final ConfigStore configStore;
	private final int snapshots;

	public CloudgramImportPromptScreen(Screen parent, ConfigStore configStore, int snapshots) {
		super(Component.literal("Cloudgram found cloud saves"));
		if (configStore == null) {
			throw new IllegalArgumentException("configStore is required");
		}
		if (snapshots < 1) {
			throw new IllegalArgumentException("snapshots must be positive");
		}
		this.parent = parent;
		this.configStore = configStore;
		this.snapshots = snapshots;
	}

	@Override
	protected void init() {
		super.init();

		int width = Math.min(300, Math.max(200, this.width - 40));
		int left = this.width / 2 - width / 2;
		int y = this.height / 2 + 4;

		Button restore = Button.builder(Component.literal("Restore now"), button -> openRestore())
				.bounds(left, y, width, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(restore);

		Button later = Button.builder(Component.literal("Not now"), button -> onClose())
				.bounds(left, y + 24, width, BUTTON_HEIGHT)
				.build();
		addRenderableWidget(later);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int centerY = height / 2 - 40;
		graphics.centeredText(font, Component.literal("Cloudgram found cloud saves"),
				width / 2, centerY, COLOR_TITLE);
		graphics.centeredText(font,
				Component.literal(snapshots == 1
						? "1 snapshot is waiting in your bot chat."
						: snapshots + " snapshots are waiting in your bot chat."),
				width / 2, centerY + 16, COLOR_BODY);
		graphics.centeredText(font, Component.literal("Restoring creates a new save; nothing is overwritten."),
				width / 2, centerY + 30, COLOR_DIM);
	}

	@Override
	public void onClose() {
		if (minecraft != null && minecraft.gui != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	private void openRestore() {
		if (minecraft == null || minecraft.gui == null) {
			return;
		}
		minecraft.gui.setScreen(new CloudgramRestoreScreen(parent, configStore));
	}
}
