package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 皮肤选择列表：每行「头部小头像 + 文件名」，可滚动、点击选中。
 * 只显示当前文件夹（slim / classic）里的皮肤。
 */
public class SkinPickList extends AbstractWidget {
    private static final int ROW = 14;
    private final List<CardAssets.Entry> entries = new ArrayList<>();
    private int selected = -1;
    private int scroll;

    public SkinPickList(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    /** folder = "slim" / "classic"；null 表示全部。 */
    public void refresh(String folder) {
        entries.clear();
        for (CardAssets.Entry entry : CardAssets.skins()) {
            if (folder == null || folder.equals(entry.folder())) {
                entries.add(entry);
            }
        }
        if (selected >= entries.size()) {
            selected = -1;
        }
        scroll = 0;
    }

    public CardAssets.Entry selected() {
        return selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
    }

    public int size() {
        return entries.size();
    }

    private int maxScroll() {
        return Math.max(0, entries.size() * ROW - (this.getHeight() - 2));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        graphics.fill(x, y, x + w, y + h, 0xC0000000);
        graphics.renderOutline(x, y, w, h, 0xFF6A6A6A);
        Minecraft minecraft = Minecraft.getInstance();
        if (entries.isEmpty()) {
            graphics.drawString(minecraft.font, "（这个文件夹里没有皮肤）", x + 4, y + 4, 0x808080, false);
            return;
        }
        graphics.enableScissor(x + 1, y + 1, x + w - 7, y + h - 1);
        try {
            int first = scroll / ROW;
            int offset = scroll % ROW;
            int rows = (h - 2) / ROW + 1;
            for (int i = first; i < Math.min(entries.size(), first + rows); i++) {
                int rowY = y + 1 + (i - first) * ROW - offset;
                boolean active = i == selected;
                if (active) {
                    graphics.fill(x + 1, rowY, x + w - 6, rowY + ROW - 1, 0x80407090);
                }
                CardAssets.Entry entry = entries.get(i);
                ResourceLocation head = CardTextures.skin(entry.reference());
                if (head != null) {
                    graphics.blit(head, x + 2, rowY + 2, 8, 8, 10, 10, 64, 64);
                }
                String label = entry.fileName().replace(".png", "") + (entry.usable() ? "" : "  ✗");
                graphics.drawString(minecraft.font, label, x + 16, rowY + 2,
                        entry.usable() ? (active ? 0xFFFFFF : 0xD0D0D0) : 0xE07070, false);
            }
        } finally {
            graphics.disableScissor();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible) {
            return false;
        }
        if (mouseX < this.getX() || mouseX > this.getX() + this.getWidth()
                || mouseY < this.getY() || mouseY > this.getY() + this.getHeight()) {
            return false;
        }
        int index = ((int) (mouseY - this.getY() - 1) + scroll) / ROW;
        if (index >= 0 && index < entries.size()) {
            selected = index;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.active || !this.visible) {
            return false;
        }
        if (mouseX < this.getX() || mouseX > this.getX() + this.getWidth()
                || mouseY < this.getY() || mouseY > this.getY() + this.getHeight()) {
            return false;
        }
        scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - scrollY * ROW));
        return true;
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }

    /** 选中名字（不含 .png）。 */
    public String selectedName() {
        CardAssets.Entry entry = selected();
        return entry == null ? null : entry.fileName().replace(".png", "");
    }
}
