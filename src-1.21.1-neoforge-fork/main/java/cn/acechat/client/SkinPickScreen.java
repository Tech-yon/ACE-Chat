package cn.acechat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Consumer;

/**
 * 皮肤选择器：每行带头像预览（8×8 脸 + 帽子层），点选即回。
 * 只用于工坊「形象」页；立绘仍走通用文本选择器。
 */
public class SkinPickScreen extends Screen {
    private final Screen parent;
    private final List<String> items;
    private final String selected;
    private final Consumer<String> onPick;
    private int scroll;

    private static final int ROW_H = 28;
    private static final int LIST_TOP = 48;

    public SkinPickScreen(Screen parent, List<String> items, String selected, Consumer<String> onPick) {
        super(Component.literal("选择皮肤（带头像预览）"));
        this.parent = parent;
        this.items = items;
        this.selected = selected == null ? "" : selected;
        this.onPick = onPick;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - LIST_TOP - 40) / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, this.items.size() - visibleRows());
    }

    private int rowX() {
        return this.width / 2 - 150;
    }

    private int rowW() {
        return 300;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
        this.addRenderableWidget(Button.builder(Component.literal("关闭"),
                        b -> Minecraft.getInstance().setScreen(this.parent))
                .bounds(this.width / 2 - 60, this.height - 32, 120, 18).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll -= (int) Math.signum(scrollY) * 2;
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int x = rowX();
        if (mouseX < x || mouseX > x + rowW() || mouseY < LIST_TOP) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        int index = this.scroll + (int) ((mouseY - LIST_TOP) / ROW_H);
        if (index < 0 || index >= this.items.size()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        String picked = this.items.get(index);
        this.onPick.accept(picked);
        Minecraft.getInstance().setScreen(this.parent);
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, 0xE0101014);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, "滚轮翻页 · 点击选择 · Esc 返回", this.width / 2, 32, 0xFF9A9A9A);

        int x = rowX();
        int w = rowW();
        int rows = visibleRows();
        for (int i = 0; i < rows && this.scroll + i < this.items.size(); i++) {
            int index = this.scroll + i;
            String ref = this.items.get(index);
            int y = LIST_TOP + i * ROW_H;
            boolean active = ref.equals(this.selected);
            int bg = active ? 0xFF35506A : 0x80303038;
            graphics.fill(x, y, x + w, y + ROW_H - 2, bg);
            drawHead(graphics, ref, x + 4, y + 3, 20);
            String label = ref == null || ref.isBlank() ? "（使用玩家在线皮肤）" : ref;
            graphics.drawString(this.font, label, x + 30, y + 10, active ? 0xFFFFD27A : 0xFFEDEDED, false);
        }

        if (maxScroll() > 0) {
            graphics.drawString(this.font, (this.scroll + 1) + "/" + (maxScroll() + 1),
                    x + w + 8, 24, 0xFF909090, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /** 从皮肤贴图裁 8×8 脸 + 帽子层，按贴图分辨率换算 UV。 */
    private void drawHead(GuiGraphics graphics, String ref, int x, int y, int size) {
        if (ref == null || ref.isBlank()) {
            graphics.fill(x, y, x + size, y + size, 0xFF3A3A44);
            return;
        }
        try {
            ResourceLocation id = CardTextures.skin(ref);
            if (id == null) {
                graphics.fill(x, y, x + size, y + size, 0xFF3A3A44);
                return;
            }
            int[] dim = CardTextures.dimensions(ref, true);
            int texW = Math.max(64, dim[0]);
            int texH = Math.max(64, dim[1]);
            float f = texW / 64.0F;
            int u = Math.round(8 * f);
            int v = Math.round(8 * f);
            int uv = Math.max(1, Math.round(8 * f));
            RenderSystem.enableBlend();
            graphics.blit(id, x, y, size, size, u, v, uv, uv, texW, texH);
            // 帽子/外层：轻微叠在上面
            graphics.blit(id, x, y, size, size, Math.round(40 * f), v, uv, uv, texW, texH);
        } catch (RuntimeException | LinkageError ignored) {
            graphics.fill(x, y, x + size, y + size, 0xFF3A3A44);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(this.parent);
    }
}