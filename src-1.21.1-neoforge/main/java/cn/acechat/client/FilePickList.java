package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「文件选择框」：固定高度、可滚动的小列表，用来从目录里挑一个 json 文件。
 *
 * <p>自己画（不依赖原版列表控件），所以两个版本行为完全一致：鼠标滚轮滚动、点击选中并高亮，
 * 选中的文件名用 {@link #selectedName()} 取。只列 {@code .json}。
 */
public class FilePickList extends AbstractWidget {
    private static final int ROW = 12;
    private final List<String> names = new ArrayList<>();
    private String selected;
    private int scroll;

    public FilePickList(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    /** 重新扫描目录（只列 .json，按名字排序）。 */
    public void refresh(Path dir) {
        names.clear();
        try {
            if (dir != null && Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.map(path -> path.getFileName().toString())
                            .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".json"))
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .forEach(names::add);
                }
            }
        } catch (Exception ignored) {
            // 目录读不了就当空的
        }

        if (selected != null && !names.contains(selected)) {
            selected = null;
        }

        scroll = 0;
    }

    public String selectedName() {
        return selected;
    }

    public int size() {
        return names.size();
    }

    private int maxScroll() {
        return Math.max(0, names.size() * ROW - (this.getHeight() - 2));
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
        if (names.isEmpty()) {
            if (minecraft != null) {
                graphics.drawString(minecraft.font, Component.translatable("screen.acechat.filepick.empty"),
                        x + 4, y + 4, 0x808080, false);
            }

            return;
        }

        graphics.enableScissor(x + 1, y + 1, x + w - 7, y + h - 1);
        try {
            int rows = (h - 2) / ROW + 1;
            for (int i = 0; i < rows; i++) {
                int index = scroll / ROW + i;
                if (index >= names.size()) {
                    break;
                }

                int rowY = y + 1 + index * ROW - scroll;
                boolean isSelected = names.get(index).equals(selected);
                boolean hovered = mouseX >= x + 1 && mouseX < x + w - 7 && mouseY >= rowY && mouseY < rowY + ROW;
                if (isSelected) {
                    graphics.fill(x + 1, rowY, x + w - 7, rowY + ROW, 0x8030A0FF);
                } else if (hovered) {
                    graphics.fill(x + 1, rowY, x + w - 7, rowY + ROW, 0x40FFFFFF);
                }

                if (minecraft != null) {
                    graphics.drawString(minecraft.font, names.get(index), x + 4, rowY + 2,
                            isSelected ? 0xFFFFFF : 0xC8C8C8, false);
                }
            }
        } finally {
            graphics.disableScissor();
        }

        int barX = x + w - 6;
        int trackH = h - 2;
        graphics.fill(barX, y + 1, barX + 5, y + 1 + trackH, 0x60000000);
        int max = maxScroll();
        int thumbH = max <= 0 ? trackH : Math.max(8, trackH * trackH / (trackH + max));
        int thumbY = max <= 0 ? y + 1 : y + 1 + (trackH - thumbH) * scroll / max;
        graphics.fill(barX, thumbY, barX + 5, thumbY + thumbH, 0xFFA0A0A0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.isMouseOver(mouseX, mouseY) || button != 0) {
            return false;
        }

        int row = (int) ((mouseY - this.getY() - 1 + scroll) / ROW);
        if (row >= 0 && row < names.size()) {
            selected = names.get(row);
        }

        return true;
    }

@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.isMouseOver(mouseX, mouseY)) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) (scrollY * ROW)));
            return true;
        }

        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // 不需要朗读
    }
}
