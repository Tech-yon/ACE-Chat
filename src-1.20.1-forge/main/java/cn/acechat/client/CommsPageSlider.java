package cn.acechat.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 翻页滑块：拖动时滑块实时跟手（只画不重建），松手时才回调切页，
 * 这样重建界面控件不会打断拖拽。左右两端各留一点内边距，避免与底栏按钮重叠。
 */
public final class CommsPageSlider extends AbstractWidget {
    private final int pages;
    private final IntConsumer onCommit;
    private int value;
    private boolean dragging;
    /** 上一次已经通知出去的页码（避免同一页重复重建）。 */
    private int lastFired = -1;

    public CommsPageSlider(int x, int y, int width, int height, int pages, int value, IntConsumer onCommit) {
        super(x, y, width, height, Component.translatable("screen.acechat.page.slider"));
        this.pages = Math.max(1, pages);
        this.value = Math.max(0, Math.min(this.pages - 1, value));
        this.onCommit = onCommit;
    }

    /** 由界面在外部页码变化时同步（例如键盘/其它入口）。 */
    public void sync(int page) {
        this.value = Math.max(0, Math.min(this.pages - 1, page));
    }

    /** 滑块可移动的像素跨度（左右各留 5px 内边距）。 */
    private int span() {
        return Math.max(1, this.width - 10);
    }

    private int knobX() {
        return this.getX() + 5 + Math.round(span() * (this.pages <= 1 ? 0.0F : this.value / (float) (this.pages - 1)));
    }

    /** 拖动过程中就通知外部切页（值没变时不重复通知，避免频繁重建）。 */
    private void fireIfChanged() {
        if (this.value != this.lastFired) {
            this.lastFired = this.value;
            this.onCommit.accept(this.value);
        }
    }

    private void updateFromMouse(double mouseX) {
        int span = Math.max(1, this.width - 10);
        float ratio = (float) ((mouseX - this.getX() - 5) / (double) span);
        ratio = Math.max(0.0F, Math.min(1.0F, ratio));
        this.value = Math.round(ratio * (this.pages - 1));
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x = this.getX();
        int y = this.getY();
        int height = this.height;
        int track = this.isHoveredOrFocused() || this.dragging ? 0xFF7FB2FF : 0xFF4A5568;
        // 轨道
        graphics.fill(x, y + height / 2 - 2, x + this.width, y + height / 2 + 2, 0xC0202838);
        // 已选段
        int knob = this.knobX();
        graphics.fill(x + 4, y + height / 2 - 2, knob, y + height / 2 + 2, 0xFF2F4468);
        // 每页一个刻度：指示滑条上每一页的位置
        if (this.pages > 1 && this.pages <= 64) {
            for (int page = 0; page < this.pages; page++) {
                int tickX = this.getX() + 5 + Math.round(span() * (page / (float) (this.pages - 1)));
                int tickTop = y + height / 2 - 5;
                graphics.fill(tickX, tickTop, tickX + 1, tickTop + 10, 0x80000000);
            }
        }

        // 滑块
        graphics.fill(knob - 4, y + 2, knob + 4, y + height - 2, track);
        graphics.fill(knob - 2, y + 4, knob + 2, y + height - 4, 0xFFE8F0FF);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.dragging = true;
        this.lastFired = this.value;
        this.updateFromMouse(mouseX);
        this.fireIfChanged();   // 点哪儿跳哪儿
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        this.updateFromMouse(mouseX);
        this.fireIfChanged();   // 拖动过程中就切页
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
        this.dragging = false;
        this.updateFromMouse(mouseX);
        this.fireIfChanged();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
