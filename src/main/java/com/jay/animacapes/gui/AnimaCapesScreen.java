package com.jay.animacapes.gui;

import com.jay.animacapes.cloud.CloudCapeSync;
import com.jay.animacapes.cloud.RemoteCapeManager;
import com.jay.animacapes.config.AnimaCapesConfig;
import com.jay.animacapes.engine.CapeAnimationEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.io.File;
import java.util.List;

public class AnimaCapesScreen extends Screen {
    private final Screen parent;
    private Button toggleButton;
    private Button otherCapesButton;
    private Button cloudSyncButton;
    private Button fitModeButton;
    private Button prevCapeBtn;
    private Button nextCapeBtn;
    private long limitWarningTimer = 0;

    private int prevX = 0;
    private int prevY = 0;
    private int prevW = 35;
    private int prevH = 56;

    public AnimaCapesScreen(Screen parent) {
        super(Component.literal("Animated Capes Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = 44;
        int btnW = 200;
        int btnH = 20;
        int halfW = (btnW - 6) / 2;

        // 1. Choose File Button
        this.addRenderableWidget(Button.builder(Component.literal("Choose Animation (WebP / GIF)..."), b -> {
            if (!GalleryFolder.isBypassLimit() && GalleryFolder.listCapeFiles().size() >= GalleryFolder.MAX_GALLERY_CAPES) {
                limitWarningTimer = System.currentTimeMillis() + 4000;
                return;
            }
            FileDialogHelper.openImageFileChooserAsync(chosen -> {
                if (chosen == null) return;
                if (!com.jay.animacapes.codec.AnimationDecoder.isSupportedFormat(chosen.getName())) {
                    System.err.println("[AnimatedCapes] Only .webp and .gif animations are supported.");
                    return;
                }
                long max = com.jay.animacapes.codec.AnimationDecoder.getMaxAllowedBytes(chosen.getName());
                if (chosen.length() > max) {
                    System.err.println("[AnimatedCapes] File exceeds " + (max / (1024 * 1024)) + "MB limit");
                    return;
                }
                File saved = GalleryFolder.copyToGallery(chosen);
                if (saved != null) {
                    AnimaCapesConfig.getInstance().capeFilePath = saved.getAbsolutePath();
                    AnimaCapesConfig.getInstance().save();
                    CapeAnimationEngine.getInstance().loadFromFile(saved);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) {
                        mc.execute(() -> updateCycleButtons());
                    }
                }
            });
        }).bounds(centerX - btnW / 2, startY, btnW, btnH).build());

        // 2. Open Gallery Button
        this.addRenderableWidget(Button.builder(Component.literal("Cape Gallery (Select Uploaded)..."), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreenAndShow(new AnimaCapesGalleryScreen(this));
            }
        }).bounds(centerX - btnW / 2, startY + 24, btnW, btnH).build());

        // 3. Toggle Cape ON/OFF & Other Capes ON/OFF (Side by Side)
        toggleButton = this.addRenderableWidget(Button.builder(getToggleText(), b -> {
            AnimaCapesConfig.getInstance().enabled = !AnimaCapesConfig.getInstance().enabled;
            AnimaCapesConfig.getInstance().save();
            toggleButton.setMessage(getToggleText());
        }).bounds(centerX - btnW / 2, startY + 48, halfW, btnH).build());

        otherCapesButton = this.addRenderableWidget(Button.builder(getOtherCapesText(), b -> {
            AnimaCapesConfig.getInstance().showOtherPlayerCapes = !AnimaCapesConfig.getInstance().showOtherPlayerCapes;
            AnimaCapesConfig.getInstance().save();
            otherCapesButton.setMessage(getOtherCapesText());
            if (!AnimaCapesConfig.getInstance().showOtherPlayerCapes) {
                RemoteCapeManager.getInstance().clearAll();
            }
        }).bounds(centerX - btnW / 2 + halfW + 6, startY + 48, halfW, btnH).build());

        // 4. Cloud Sync ON/OFF & Fit Mode Button (Side by Side)
        cloudSyncButton = this.addRenderableWidget(Button.builder(getCloudSyncText(), b -> {
            AnimaCapesConfig.getInstance().cloudSyncEnabled = !AnimaCapesConfig.getInstance().cloudSyncEnabled;
            AnimaCapesConfig.getInstance().save();
            cloudSyncButton.setMessage(getCloudSyncText());
            if (AnimaCapesConfig.getInstance().cloudSyncEnabled) {
                CapeAnimationEngine.getInstance().reload();
            } else {
                CloudCapeSync.getInstance().deleteCurrentCapeAsync();
            }
        }).bounds(centerX - btnW / 2, startY + 72, halfW, btnH).build());

        fitModeButton = this.addRenderableWidget(Button.builder(getFitModeText(), b -> {
            String current = AnimaCapesConfig.getInstance().fitMode;
            if ("COVER".equalsIgnoreCase(current)) {
                AnimaCapesConfig.getInstance().fitMode = "CONTAIN";
            } else if ("CONTAIN".equalsIgnoreCase(current)) {
                AnimaCapesConfig.getInstance().fitMode = "STRETCH";
            } else {
                AnimaCapesConfig.getInstance().fitMode = "COVER";
            }
            AnimaCapesConfig.getInstance().save();
            fitModeButton.setMessage(getFitModeText());
            CapeAnimationEngine.getInstance().reload();
        }).bounds(centerX - btnW / 2 + halfW + 6, startY + 72, halfW, btnH).build());

        // 5. Clear Cape & Open Folder (Side by Side)
        this.addRenderableWidget(Button.builder(Component.literal("Clear Cape"), b -> {
            AnimaCapesConfig.getInstance().capeFilePath = "";
            AnimaCapesConfig.getInstance().save();
            CapeAnimationEngine.getInstance().clearCape();
            updateCycleButtons();
        }).bounds(centerX - btnW / 2, startY + 96, halfW, btnH).build());

        this.addRenderableWidget(Button.builder(Component.literal("Open Folder"), b -> {
            GalleryFolder.openFolderInExplorer();
        }).bounds(centerX - btnW / 2 + halfW + 6, startY + 96, halfW, btnH).build());

        // 6. Preview Quick-Cycle Buttons on the Status Card
        int cardY = 162;
        int cardW = 320;
        int cardX = centerX - cardW / 2;
        prevX = cardX + cardW - prevW - 24;
        prevY = cardY + 12;

        prevCapeBtn = this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            cycleCape(-1);
        }).bounds(prevX - 16, prevY + 18, 14, 20).build());

        nextCapeBtn = this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            cycleCape(1);
        }).bounds(prevX + prevW + 2, prevY + 18, 14, 20).build());

        updateCycleButtons();

        // 7. Done / Back Button
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            this.onClose();
        }).bounds(centerX - btnW / 2, this.height - 30, btnW, btnH).build());
    }

    private void updateCycleButtons() {
        List<File> files = GalleryFolder.listCapeFiles();
        boolean hasMultiple = files.size() > 1;
        if (prevCapeBtn != null) prevCapeBtn.visible = hasMultiple;
        if (nextCapeBtn != null) nextCapeBtn.visible = hasMultiple;
    }

    private void cycleCape(int delta) {
        List<File> files = GalleryFolder.listCapeFiles();
        if (files.isEmpty()) return;

        String current = AnimaCapesConfig.getInstance().capeFilePath;
        int currentIndex = -1;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getAbsolutePath().equalsIgnoreCase(current)) {
                currentIndex = i;
                break;
            }
        }

        int nextIndex = (currentIndex + delta + files.size()) % files.size();
        File target = files.get(nextIndex);
        AnimaCapesConfig.getInstance().capeFilePath = target.getAbsolutePath();
        AnimaCapesConfig.getInstance().save();
        CapeAnimationEngine.getInstance().loadFromFile(target);
    }

    private Component getToggleText() {
        boolean en = AnimaCapesConfig.getInstance().enabled;
        return Component.literal("Cape: " + (en ? "§aON" : "§cOFF"));
    }

    private Component getOtherCapesText() {
        boolean showOthers = AnimaCapesConfig.getInstance().showOtherPlayerCapes;
        return Component.literal("Others: " + (showOthers ? "§aON" : "§cOFF"));
    }

    private Component getCloudSyncText() {
        boolean sync = AnimaCapesConfig.getInstance().cloudSyncEnabled;
        return Component.literal("Cloud: " + (sync ? "§aSYNC" : "§7OFF"));
    }

    private Component getFitModeText() {
        return Component.literal("Fit: §e" + AnimaCapesConfig.getInstance().fitMode.toUpperCase());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) { // Left click
            double mx = event.x();
            double my = event.y();
            if (mx >= prevX && mx <= prevX + prevW && my >= prevY && my <= prevY + prevH) {
                if (this.minecraft != null) {
                    this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    this.minecraft.setScreenAndShow(new AnimaCapesGalleryScreen(this));
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(extractor);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;

        // Title
        extractor.centeredText(this.font, Component.literal("§lAnimated Capes Manager"), centerX, 14, 0xFFFFFFFF);
        if (limitWarningTimer > System.currentTimeMillis()) {
            extractor.centeredText(this.font, Component.literal("§cUpload limit reached!"), centerX, 26, 0xFFFF5555);
        } else {
            extractor.centeredText(this.font, Component.literal("§7Upload animated stickers or pick from your cape gallery"), centerX, 26, 0xFFAAAAAA);
        }

        // Status Card
        int cardY = 162;
        int cardW = 320;
        int cardH = 82;
        int cardX = centerX - cardW / 2;

        extractor.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x88000000);
        extractor.outline(cardX, cardY, cardW, cardH, 0xFF444444);

        CapeAnimationEngine engine = CapeAnimationEngine.getInstance();
        if (engine.isLoading()) {
            extractor.centeredText(this.font, Component.literal("§eDecoding animation frames in background..."), centerX - 20, cardY + 32, 0xFFFFFF55);
        } else if (engine.hasCape()) {
            String path = engine.getCurrentFilePath();
            String fileName = (path != null && !path.isEmpty()) ? new File(path).getName() : "Unknown";
            int frames = engine.getFrameCount();
            long dur = engine.getTotalDurationMs();
            float fps = (dur > 0 && frames > 1) ? (frames * 1000f / dur) : 0;

            String displayFile = (fileName != null) ? fileName : "Unknown";
            if (displayFile.length() > 24) {
                displayFile = displayFile.substring(0, 22) + "..";
            }

            extractor.text(this.font, Component.literal("§bActive File: §f" + displayFile), cardX + 10, cardY + 10, 0xFFFFFFFF);
            extractor.text(this.font, Component.literal("§bFrame Count: §f" + frames + " frames §7(" + String.format("%.1f", fps) + " FPS)"), cardX + 10, cardY + 24, 0xFFFFFFFF);
            extractor.text(this.font, Component.literal("§aEngine: §7Zero-CPU VRAM Swapping Active"), cardX + 10, cardY + 38, 0xFF55FF55);

            // Cloud status
            if (!AnimaCapesConfig.getInstance().cloudSyncEnabled) {
                extractor.text(this.font, Component.literal("§dCloud Sync: §7Disabled (Local Only)"), cardX + 10, cardY + 52, 0xFF888888);
            } else {
                CloudCapeSync.Status st = CloudCapeSync.getInstance().getStatus();
                if (st == CloudCapeSync.Status.SYNCED) {
                    extractor.text(this.font, Component.literal("§dCloud Sync: §aVisible to others"), cardX + 10, cardY + 52, 0xFF55FF55);
                } else if (st == CloudCapeSync.Status.UPLOADING) {
                    extractor.text(this.font, Component.literal("§dCloud Sync: §eSyncing with cloud..."), cardX + 10, cardY + 52, 0xFFFFFF55);
                } else if (st == CloudCapeSync.Status.LOCAL_ONLY) {
                    extractor.text(this.font, Component.literal("§dCloud Sync: §eLocal Only §7(Cloud Whitelist)"), cardX + 10, cardY + 52, 0xFFFFFF55);
                } else if (st == CloudCapeSync.Status.ERROR) {
                    String err = CloudCapeSync.getInstance().getErrorMessage();
                    if (err != null && (err.contains("403") || err.toLowerCase().contains("whitelist") || err.toLowerCase().contains("forbidden"))) {
                        extractor.text(this.font, Component.literal("§dCloud Sync: §eLocal Only §7(Cloud Whitelist)"), cardX + 10, cardY + 52, 0xFFFFFF55);
                    } else {
                        if (err == null || err.trim().isEmpty()) {
                            err = "Connection failed";
                        } else if (err.length() > 24) {
                            err = err.substring(0, 22) + "..";
                        }
                        extractor.text(this.font, Component.literal("§dCloud Sync: §c" + err), cardX + 10, cardY + 52, 0xFFFF5555);
                    }
                } else {
                    extractor.text(this.font, Component.literal("§dCloud Sync: §7Ready"), cardX + 10, cardY + 52, 0xFFAAAAAA);
                }

                if (mouseX >= cardX + 10 && mouseX <= cardX + 220 && mouseY >= cardY + 50 && mouseY <= cardY + 62) {
                    if (st == CloudCapeSync.Status.LOCAL_ONLY || (st == CloudCapeSync.Status.ERROR && CloudCapeSync.getInstance().getErrorMessage().contains("403"))) {
                        extractor.setTooltipForNextFrame(Component.literal("§eDirect WebP cloud upload requires server whitelist.\n§aYour cape is 100% active locally!\n§7Tip: Use GIF files for open cloud sharing."), mouseX, mouseY);
                    }
                }
            }

            if (!AnimaCapesConfig.getInstance().showOtherPlayerCapes) {
                extractor.text(this.font, Component.literal("§cOther Capes: §7Hidden by option"), cardX + 10, cardY + 66, 0xFFAAAAAA);
            } else {
                extractor.text(this.font, Component.literal("§6Minecraft Cape: §7Disabled (Mod Active)"), cardX + 10, cardY + 66, 0xFFAAAAAA);
            }

            // Draw Cape Texture Preview Thumbnail (Strictly bounded!)
            Identifier activeCape = engine.getActiveCapeIdentifier();
            if (activeCape != null) {
                boolean isThumbHovered = (mouseX >= prevX && mouseX <= prevX + prevW && mouseY >= prevY && mouseY <= prevY + prevH);
                extractor.fill(prevX - 2, prevY - 2, prevX + prevW + 2, prevY + prevH + 2, 0xFF222222);
                int borderCol = isThumbHovered ? 0xFF00E5FF : 0xFF666666;
                extractor.outline(prevX - 2, prevY - 2, prevW + 4, prevH + 4, borderCol);

                int x0 = prevX;
                int y0 = prevY;
                int x1 = prevX + prevW;
                int y1 = prevY + prevH;
                float u0 = 1.0f / 64.0f;
                float u1 = 11.0f / 64.0f;
                float v0 = 1.0f / 32.0f;
                float v1 = 17.0f / 32.0f;
                extractor.blit(activeCape, x0, y0, x1, y1, u0, u1, v0, v1);

                if (isThumbHovered) {
                    extractor.setTooltipForNextFrame(Component.literal("§eClick to open Cape Gallery"), mouseX, mouseY);
                }
            }
        } else {
            extractor.centeredText(this.font, Component.literal("§7No animated cape loaded. Minecraft cape disabled while active."), centerX, cardY + 28, 0xFF888888);
            extractor.centeredText(this.font, Component.literal("§eClick 'Choose Animated File' or 'Cape Gallery'!"), centerX, cardY + 44, 0xFFFFFF55);
        }

        // Render widgets (Buttons)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
