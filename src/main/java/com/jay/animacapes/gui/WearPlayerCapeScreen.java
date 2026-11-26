package com.jay.animacapes.gui;

import com.jay.animacapes.cloud.CapeShareService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class WearPlayerCapeScreen extends Screen {
    private final Screen parent;
    private EditBox nameInput;
    private EditBox reasonInput;
    private String statusMessage = "Enter a username to wear, report, or block their animated cape.";
    private int statusColor = 0xFFAAAAAA;

    public WearPlayerCapeScreen(Screen parent) {
        super(Component.literal("Wear & Moderate Player Capes"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = 46;

        nameInput = new EditBox(this.font, centerX - 120, startY, 240, 20, Component.literal("Player Name or UUID"));
        nameInput.setMaxLength(36);
        this.addRenderableWidget(nameInput);
        this.setInitialFocus(nameInput);

        reasonInput = new EditBox(this.font, centerX - 120, startY + 40, 240, 20, Component.literal("Reason for Report"));
        reasonInput.setMaxLength(80);
        this.addRenderableWidget(reasonInput);

        int btnW = 118;
        int btnH = 20;
        int row1Y = startY + 72;

        // Button 1: Wear Cape
        this.addRenderableWidget(Button.builder(Component.literal("Wear Cape"), b -> {
            String name = nameInput.getValue().trim();
            if (name.isEmpty()) {
                setStatus("Please enter a player name first!", 0xFFFF5555);
                return;
            }
            setStatus("Searching server for " + name + "'s cape...", 0xFFFFFF55);
            CapeShareService.wearPlayerCape(name, msg -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        int col = msg.contains("§a") ? 0xFF55FF55 : 0xFFFF5555;
                        setStatus(msg.replaceAll("§[0-9a-fk-or]", ""), col);
                    });
                }
            });
        }).bounds(centerX - 120, row1Y, btnW, btnH).build());

        // Button 2: Block Player
        this.addRenderableWidget(Button.builder(Component.literal("Block Player"), b -> {
            String name = nameInput.getValue().trim();
            if (name.isEmpty()) {
                setStatus("Please enter a player name to block!", 0xFFFF5555);
                return;
            }
            CapeShareService.blockPlayer(name, msg -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        int col = msg.contains("§e") ? 0xFFFFFF55 : 0xFF55FF55;
                        setStatus(msg.replaceAll("§[0-9a-fk-or]", ""), col);
                    });
                }
            });
        }).bounds(centerX + 2, row1Y, btnW, btnH).build());

        int row2Y = row1Y + 24;

        // Button 3: Report Player
        this.addRenderableWidget(Button.builder(Component.literal("Report Inappropriate Cape"), b -> {
            String name = nameInput.getValue().trim();
            String reason = reasonInput.getValue().trim();
            if (name.isEmpty()) {
                setStatus("Please enter the reported player's name!", 0xFFFF5555);
                return;
            }
            setStatus("Submitting report with active cape to Discord...", 0xFFFFFF55);
            CapeShareService.reportPlayer(name, reason, msg -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        int col = msg.contains("§a") ? 0xFF55FF55 : 0xFFFF5555;
                        setStatus(msg.replaceAll("§[0-9a-fk-or]", ""), col);
                    });
                }
            });
        }).bounds(centerX - 120, row2Y, btnW, btnH).build());

        // Button 4: Unblock Player
        this.addRenderableWidget(Button.builder(Component.literal("Unblock Player"), b -> {
            String name = nameInput.getValue().trim();
            if (name.isEmpty()) {
                setStatus("Please enter a player name to unblock!", 0xFFFF5555);
                return;
            }
            CapeShareService.unblockPlayer(name, msg -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        int col = msg.contains("§a") ? 0xFF55FF55 : 0xFFFF5555;
                        setStatus(msg.replaceAll("§[0-9a-fk-or]", ""), col);
                    });
                }
            });
        }).bounds(centerX + 2, row2Y, btnW, btnH).build());

        // Button 5: Back to Gallery
        this.addRenderableWidget(Button.builder(Component.literal("Back to Gallery"), b -> {
            this.onClose();
        }).bounds(centerX - 70, Math.min(this.height - 30, row2Y + 45), 140, btnH).build());
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;

        // Background
        extractor.fill(0, 0, this.width, this.height, 0xD0101010);

        // Header Title
        extractor.centeredText(this.font, Component.literal("§lSearch, Wear, Report & Block Capes"), centerX, 12, 0xFFFFFFFF);

        // Field labels
        extractor.text(this.font, Component.literal("§7Target Username or UUID:"), centerX - 120, 36, 0xFFAAAAAA);
        extractor.text(this.font, Component.literal("§7Reason for Report (Optional):"), centerX - 120, 76, 0xFFAAAAAA);

        // Live Status Message
        if (statusMessage != null && !statusMessage.isEmpty()) {
            extractor.centeredText(this.font, Component.literal(statusMessage), centerX, 172, statusColor);
        }

        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            if (this.parent instanceof AnimaCapesGalleryScreen) {
                ((AnimaCapesGalleryScreen) this.parent).refreshFileList();
            }
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
