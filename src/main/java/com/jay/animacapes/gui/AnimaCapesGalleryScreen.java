package com.jay.animacapes.gui;

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
import java.util.ArrayList;
import java.util.List;

public class AnimaCapesGalleryScreen extends Screen {
    private final Screen parent;
    private final List<File> capeFiles = new ArrayList<>();

    private Button prevPageBtn;
    private Button nextPageBtn;
    private AnimationSpeedSlider speedSlider;

    private int currentPage = 0;
    private int totalPages = 1;
    private int cols = 1;
    private int rows = 1;
    private int pageSize = 1;

    private File pendingDeleteFile = null;
    private long pendingDeleteTime = 0;
    private long limitWarningTimer = 0;

    private static final int HEADER_H = 36;
    private static final int FOOTER_H = 34;

    private static final int CARD_W = 76;
    private static final int CARD_H = 104;
    private static final int GAP_X = 12;
    private static final int GAP_Y = 10;

    public AnimaCapesGalleryScreen(Screen parent) {
        super(Component.literal("Animated Capes Gallery"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refreshFileList();

        // Navigation & Speed Slider Bar
        int navY = 40;
        int btnNavW = 85;
        int sliderW = 150;
        int navGap = 8;
        int totalNavW = btnNavW * 2 + sliderW + navGap * 2;
        int navStartX = (this.width - totalNavW) / 2;

        prevPageBtn = this.addRenderableWidget(Button.builder(Component.literal("◀ Prev Page"), b -> {
            if (currentPage > 0) {
                currentPage--;
                recomputePagination();
            }
        }).bounds(navStartX, navY, btnNavW, 20).build());

        speedSlider = this.addRenderableWidget(new AnimationSpeedSlider(navStartX + btnNavW + navGap, navY, sliderW, 20));

        nextPageBtn = this.addRenderableWidget(Button.builder(Component.literal("Next Page ▶"), b -> {
            if (currentPage < totalPages - 1) {
                currentPage++;
                recomputePagination();
            }
        }).bounds(navStartX + btnNavW + sliderW + navGap * 2, navY, btnNavW, 20).build());

        // Footer Action Buttons
        int gap = 5;
        int btnW = Math.max(56, Math.min(84, (this.width - 20 - (gap * 4)) / 5));
        int btnH = 20;
        int btnY = this.height - 28;
        int totalW = (btnW * 5) + (gap * 4);
        int startX = (this.width - totalW) / 2;

        // Button 1: Upload File
        this.addRenderableWidget(Button.builder(Component.literal("Upload File..."), b -> {
            if (!GalleryFolder.isBypassLimit() && capeFiles.size() >= GalleryFolder.MAX_GALLERY_CAPES) {
                if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
                    this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, 1.0f));
                }
                limitWarningTimer = System.currentTimeMillis() + 4000;
                return;
            }
            FileDialogHelper.openImageFileChooserAsync(chosen -> {
                if (chosen == null) return;
                if (!com.jay.animacapes.codec.AnimationDecoder.isSupportedFormat(chosen.getName())) {
                    return;
                }
                long max = com.jay.animacapes.codec.AnimationDecoder.getMaxAllowedBytes(chosen.getName());
                if (chosen.length() > max) {
                    return;
                }
                File copied = GalleryFolder.copyToGallery(chosen);
                if (copied != null) {
                    AnimaCapesConfig.getInstance().capeFilePath = copied.getAbsolutePath();
                    AnimaCapesConfig.getInstance().save();
                    CapeAnimationEngine.getInstance().loadFromFile(copied);
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) {
                        mc.execute(() -> refreshFileList());
                    }
                }
            });
        }).bounds(startX, btnY, btnW, btnH).build());

        // Button 2: Wear Player...
        this.addRenderableWidget(Button.builder(Component.literal("Wear Player..."), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreenAndShow(new WearPlayerCapeScreen(this));
            }
        }).bounds(startX + btnW + gap, btnY, btnW, btnH).build());

        // Button 3: Open Folder
        this.addRenderableWidget(Button.builder(Component.literal("Open Folder"), b -> {
            GalleryFolder.openFolderInExplorer();
        }).bounds(startX + (btnW + gap) * 2, btnY, btnW, btnH).build());

        // Button 4: Refresh
        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            refreshFileList();
        }).bounds(startX + (btnW + gap) * 3, btnY, btnW, btnH).build());

        // Button 5: Back
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            this.onClose();
        }).bounds(startX + (btnW + gap) * 4, btnY, btnW, btnH).build());

        recomputePagination();
    }

    public void refreshFileList() {
        capeFiles.clear();
        capeFiles.addAll(GalleryFolder.listCapeFiles());
        recomputePagination();
    }

    private void recomputePagination() {
        int availW = this.width - 40;
        int gridTop = 66;
        int gridBottom = this.height - FOOTER_H - 4;
        int availH = Math.max(CARD_H, gridBottom - gridTop);

        cols = Math.max(1, (availW + GAP_X) / (CARD_W + GAP_X));
        rows = Math.max(1, (availH + GAP_Y) / (CARD_H + GAP_Y));
        pageSize = Math.max(1, cols * rows);
        totalPages = Math.max(1, (capeFiles.size() + pageSize - 1) / pageSize);

        if (currentPage >= totalPages) {
            currentPage = totalPages - 1;
        }
        if (currentPage < 0) {
            currentPage = 0;
        }

        if (prevPageBtn != null) {
            prevPageBtn.active = (currentPage > 0);
        }
        if (nextPageBtn != null) {
            nextPageBtn.active = (currentPage < totalPages - 1);
        }
    }

    public void deleteCape(File file) {
        if (file == null || !file.exists()) return;
        try {
            String equippedPath = AnimaCapesConfig.getInstance().capeFilePath;
            boolean isEquipped = (equippedPath != null && file.getAbsolutePath().equalsIgnoreCase(equippedPath));
            if (isEquipped) {
                AnimaCapesConfig.getInstance().capeFilePath = "";
                AnimaCapesConfig.getInstance().save();
                CapeAnimationEngine.getInstance().clearCape();
            }

            file.delete();
            GalleryThumbnailLoader.clearCache();
            refreshFileList();

            if (this.minecraft != null) {
                this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_BREAK, 1.0f));
            }
        } catch (Exception e) {
            System.err.println("[AnimaCapes] Failed to delete cape: " + e.getMessage());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount < 0 && currentPage < totalPages - 1) {
            currentPage++;
            recomputePagination();
            return true;
        } else if (verticalAmount > 0 && currentPage > 0) {
            currentPage--;
            recomputePagination();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) { // Left click
            double mx = event.x();
            double my = event.y();

            int footerY = this.height - FOOTER_H;
            int gridTop = 66;
            int gridBottom = footerY - 2;

            if (my >= gridTop && my <= gridBottom && !capeFiles.isEmpty()) {
                int startIndex = currentPage * pageSize;
                int endIndex = Math.min(startIndex + pageSize, capeFiles.size());

                int totalGridW = cols * CARD_W + (cols - 1) * GAP_X;
                int pageCardCount = endIndex - startIndex;
                int curRows = Math.max(1, (pageCardCount + cols - 1) / cols);
                int totalGridH = curRows * CARD_H + (curRows - 1) * GAP_Y;

                int startX = (this.width - totalGridW) / 2;
                int startY = gridTop + Math.max(0, (gridBottom - gridTop - totalGridH) / 2);

                for (int i = startIndex; i < endIndex; i++) {
                    int pageIdx = i - startIndex;
                    int col = pageIdx % cols;
                    int row = pageIdx / cols;
                    int x = startX + col * (CARD_W + GAP_X);
                    int y = startY + row * (CARD_H + GAP_Y);

                    File file = capeFiles.get(i);

                    // Top-right delete button hit test
                    int delW = 14;
                    int delH = 14;
                    int delX = x + CARD_W - delW - 2;
                    int delY = y + 2;

                    // If user is confirming delete, the hit area is wider
                    boolean isPendingDel = (pendingDeleteFile != null && pendingDeleteFile.getAbsolutePath().equalsIgnoreCase(file.getAbsolutePath()) && (System.currentTimeMillis() - pendingDeleteTime < 4000));
                    int clickX0 = isPendingDel ? (x + CARD_W - 40 - 2) : delX;
                    int clickX1 = x + CARD_W;

                    if (mx >= clickX0 && mx <= clickX1 && my >= delY && my <= delY + delH + 2) {
                        long now = System.currentTimeMillis();
                        if (isPendingDel) {
                            deleteCape(file);
                            pendingDeleteFile = null;
                        } else {
                            pendingDeleteFile = file;
                            pendingDeleteTime = now;
                            if (this.minecraft != null) {
                                this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8f));
                            }
                        }
                        return true;
                    } else if (mx >= x && mx <= x + CARD_W && my >= y && my <= y + CARD_H) {
                        pendingDeleteFile = null;
                        AnimaCapesConfig.getInstance().capeFilePath = file.getAbsolutePath();
                        AnimaCapesConfig.getInstance().save();
                        CapeAnimationEngine.getInstance().loadFromFile(file);
                        if (this.minecraft != null) {
                            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                        }
                        return true;
                    }
                }
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

        // 1. Header Background & Border
        extractor.fill(0, 0, this.width, HEADER_H, 0xE0141414);
        extractor.fill(0, HEADER_H - 1, this.width, HEADER_H, 0xFF3A3A3A);

        // Header Text
        extractor.centeredText(this.font, Component.literal("§lAnimated Capes Gallery"), centerX, 8, 0xFFFFFFFF);
        String countStr;
        if (GalleryFolder.isBypassLimit()) {
            countStr = capeFiles.size() + " capes §6[Whitelisted]";
        } else {
            countStr = capeFiles.size() + "/" + GalleryFolder.MAX_GALLERY_CAPES + " capes";
            if (capeFiles.size() >= GalleryFolder.MAX_GALLERY_CAPES) {
                countStr += " §c[Upload limit reached!]";
            }
        }
        String pageInfo = " • Page " + (currentPage + 1) + " of " + Math.max(1, totalPages);
        if (limitWarningTimer > System.currentTimeMillis()) {
            extractor.centeredText(this.font, Component.literal("§cUpload limit reached!"), centerX, 22, 0xFFFF5555);
        } else {
            extractor.centeredText(this.font, Component.literal("§7" + countStr + pageInfo + " • Click cape to equip • ✕ to remove"), centerX, 22, 0xFFAAAAAA);
        }

        // 2. Footer Background & Border
        int footerY = this.height - FOOTER_H;
        extractor.fill(0, footerY, this.width, this.height, 0xE0141414);
        extractor.fill(0, footerY, this.width, footerY + 1, 0xFF3A3A3A);

        // 3. Grid Area
        int gridTop = 66;
        int gridBottom = footerY - 2;

        if (capeFiles.isEmpty()) {
            extractor.centeredText(this.font, Component.literal("§7No capes found in \"animacapes\" folder"), centerX, (gridTop + gridBottom) / 2 - 12, 0xFFAAAAAA);
            extractor.centeredText(this.font, Component.literal("§eClick 'Upload File...' or drop stickers into 'Open Folder'!"), centerX, (gridTop + gridBottom) / 2 + 4, 0xFFFFFF55);
        } else {
            int startIndex = currentPage * pageSize;
            int endIndex = Math.min(startIndex + pageSize, capeFiles.size());

            int totalGridW = cols * CARD_W + (cols - 1) * GAP_X;
            int pageCardCount = endIndex - startIndex;
            int curRows = Math.max(1, (pageCardCount + cols - 1) / cols);
            int totalGridH = curRows * CARD_H + (curRows - 1) * GAP_Y;

            int startX = (this.width - totalGridW) / 2;
            int startY = gridTop + Math.max(0, (gridBottom - gridTop - totalGridH) / 2);

            String equippedPath = AnimaCapesConfig.getInstance().capeFilePath;
            float currentSpeed = AnimaCapesConfig.getInstance().getAnimationSpeed();

            for (int i = startIndex; i < endIndex; i++) {
                int pageIdx = i - startIndex;
                int col = pageIdx % cols;
                int row = pageIdx / cols;
                int x = startX + col * (CARD_W + GAP_X);
                int y = startY + row * (CARD_H + GAP_Y);

                File file = capeFiles.get(i);
                boolean isEquipped = (equippedPath != null && !equippedPath.isEmpty() && file.getAbsolutePath().equalsIgnoreCase(equippedPath));
                boolean isHovered = (mouseX >= x && mouseX <= x + CARD_W && mouseY >= y && mouseY <= y + CARD_H);

                // Card background
                int bgColor = isHovered ? 0xEE2A2A2A : 0xDD1A1A1A;
                extractor.fill(x, y, x + CARD_W, y + CARD_H, bgColor);

                // Card border
                int borderColor = isEquipped ? 0xFF55FF55 : (isHovered ? 0xFF00E5FF : 0xFF444444);
                extractor.outline(x, y, CARD_W, CARD_H, borderColor);
                if (isEquipped) {
                    extractor.outline(x + 1, y + 1, CARD_W - 2, CARD_H - 2, 0x8855FF55);
                }

                // Thumbnail area (10:16 aspect ratio: 35 x 56)
                int thumbW = 35;
                int thumbH = 56;
                int thumbX = x + (CARD_W - thumbW) / 2;
                int thumbY = y + 8;

                extractor.fill(thumbX - 1, thumbY - 1, thumbX + thumbW + 1, thumbY + thumbH + 1, 0xFF111111);
                extractor.outline(thumbX - 1, thumbY - 1, thumbW + 2, thumbH + 2, 0xFF333333);

                // Actively playing animated preview
                Identifier thumbId = GalleryThumbnailLoader.getThumbnail(file, currentSpeed);
                if (thumbId != null) {
                    extractor.blit(thumbId, thumbX, thumbY, thumbX + thumbW, thumbY + thumbH, 0f, 1f, 0f, 1f);
                } else {
                    extractor.centeredText(this.font, Component.literal("§8..."), thumbX + thumbW / 2, thumbY + thumbH / 2 - 4, 0xFF888888);
                }

                // Delete / Remove Button (top right of card)
                int delW = 14;
                int delH = 14;
                int delX = x + CARD_W - delW - 2;
                int delY = y + 2;

                boolean isDelHovered = (mouseX >= delX && mouseX <= delX + delW && mouseY >= delY && mouseY <= delY + delH);
                boolean isPendingDel = (pendingDeleteFile != null && pendingDeleteFile.getAbsolutePath().equalsIgnoreCase(file.getAbsolutePath()) && (System.currentTimeMillis() - pendingDeleteTime < 4000));

                if (isPendingDel) {
                    int pillW = 38;
                    int pillX = x + CARD_W - pillW - 2;
                    extractor.fill(pillX, delY, pillX + pillW, delY + delH, 0xEECC0000);
                    extractor.outline(pillX, delY, pillW, delH, 0xFFFF4444);
                    extractor.centeredText(this.font, Component.literal("§lSURE?"), pillX + pillW / 2, delY + 3, 0xFFFFFFFF);
                    if (mouseX >= pillX && mouseX <= pillX + pillW && mouseY >= delY && mouseY <= delY + delH) {
                        extractor.setTooltipForNextFrame(Component.literal("§cClick again to permanently delete cape"), mouseX, mouseY);
                    }
                } else {
                    int btnBg = isDelHovered ? 0xDDDD2222 : 0x77330000;
                    int btnBorder = isDelHovered ? 0xFFFF5555 : 0x88660000;
                    extractor.fill(delX, delY, delX + delW, delY + delH, btnBg);
                    extractor.outline(delX, delY, delW, delH, btnBorder);
                    extractor.centeredText(this.font, Component.literal("✕"), delX + delW / 2, delY + 3, isDelHovered ? 0xFFFFFFFF : 0xFFFFAAAA);
                    if (isDelHovered) {
                        extractor.setTooltipForNextFrame(Component.literal("§cRemove cape from gallery"), mouseX, mouseY);
                    }
                }

                // Status label & Truncated Filename
                if (isEquipped) {
                    extractor.centeredText(this.font, Component.literal("§a§lEQUIPPED"), x + CARD_W / 2, y + CARD_H - 24, 0xFF55FF55);
                }

                String name = file.getName();
                if (name.length() > 11) {
                    name = name.substring(0, 9) + "..";
                }
                int textColor = isEquipped ? 0xFFFFFFFF : (isHovered ? 0xFF00E5FF : 0xFFAAAAAA);
                extractor.centeredText(this.font, Component.literal(name), x + CARD_W / 2, y + CARD_H - 12, textColor);
            }
        }

        // 4. Render interactive widgets (Buttons & Sliders) on top
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
