package cn.acechat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 通用列表选择器：皮肤/立绘等“从框里自选”的场景。
 * 列表 + 滚轮滚动 + 点选即回。
 */
public class ChoiceListScreen extends Screen {
    private final Screen parent;
    private final List<String> items;      // 第一项建议为空串（表示「无」）
    private final Consumer<String> onPick;
    private int scroll;

    public ChoiceListScreen(Screen parent, String title, List<String> items, Consumer<String> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.items = items;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        build();
    }

    private int rowHeight() {
        return 20;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - 80) / rowHeight());
    }

    private void build() {
        int maxScroll = Math.max(0, this.items.size() - visibleRows());
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
        int x = this.width / 2 - 140;
        int y = 50;
        for (int i = 0; i < visibleRows() && this.scroll + i < this.items.size(); i++) {
            String item = this.items.get(this.scroll + i);
            String label = item == null || item.isBlank() ? "（无）" : item;
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
                this.onPick.accept(item);
                Minecraft.getInstance().setScreen(this.parent);
            }).bounds(x, y + i * rowHeight(), 280, 18).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("关闭"),
                b -> Minecraft.getInstance().setScreen(this.parent))
                .bounds(x, this.height - 32, 280, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll -= (int) Math.signum(scrollY) * 2;
        build();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, 0xE0101014);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        int maxScroll = Math.max(0, this.items.size() - visibleRows());
        if (maxScroll > 0) {
            graphics.drawString(this.font, (this.scroll + 1) + "/" + (maxScroll + 1),
                    this.width / 2 + 150, 26, 0xFF909090, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}