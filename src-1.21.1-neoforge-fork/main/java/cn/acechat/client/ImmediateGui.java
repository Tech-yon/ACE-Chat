package cn.acechat.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.Objects;

/**
 * 立即模式的小四边形绘制器（Forge 1.20.1 版）。
 *
 * <p>HUD 的面板、半身像和文字需要严格控制前后遮挡顺序，所以按提交顺序直接绘制。
 */
public final class ImmediateGui implements AutoCloseable {
    private final GuiGraphics graphics;
    private final Matrix4f matrix;
    private BufferBuilder builder;
    private ResourceLocation boundTexture;

    public ImmediateGui(GuiGraphics graphics) {
        this.graphics = graphics;
        this.matrix = graphics.pose().last().pose();
    }

    /** 纯色矩形，argb 为 0xAARRGGBB。 */
    public void rect(float x0, float y0, float x1, float y1, int argb) {
        rect(x0, y0, x1, y1, argb, argb);
    }

    /**
     * 把当前批次立刻提交。
     * 贴图部分改用原版 {@code GuiGraphics.blit} 绘制（它自己也用 Tesselator 的共享 builder），
     * 所以调用 blit 之前必须先在这里把纯色批次画完，避免共用 builder 互相踩。
     */
    public void flush() {
        finishBatch();
    }

    /**
     * 上下渐变矩形。
     *
     * <p>这里【立即提交】（画完 4 个顶点就 end/draw），不再把批次留到下一次调用：
     * 原版 {@code GuiGraphics.blit} 用的是同一个 Tesselator builder，如果这里留着未提交的批次，
     * 贴图绘制就会抛 {@code IllegalStateException: Already building!}，而一旦抛出来，
     * 共享 builder 的状态就坏了 —— 之后整个 GUI（包括配置界面）都会画乱。
     * 每帧纯色矩形只有几个，多几次 draw call 的开销可以忽略。
     */
    public void rect(float x0, float y0, float x1, float y1, int topArgb, int bottomArgb) {
        beginSolid();
        float[] top = rgba(topArgb);
        float[] bottom = rgba(bottomArgb);
        builder.addVertex(matrix, x0, y0, 0.0F).setColor(top[0], top[1], top[2], top[3]);
        builder.addVertex(matrix, x0, y1, 0.0F).setColor(bottom[0], bottom[1], bottom[2], bottom[3]);
        builder.addVertex(matrix, x1, y1, 0.0F).setColor(bottom[0], bottom[1], bottom[2], bottom[3]);
        builder.addVertex(matrix, x1, y0, 0.0F).setColor(top[0], top[1], top[2], top[3]);
        finishBatch();
    }

    /** 贴图矩形。UV 用 0..1 归一化值，64x64 与 128x128 高清皮肤都能取到正确的像素。 */
    public void textured(ResourceLocation texture, float x0, float y0, float x1, float y1,
                         float u0, float v0, float u1, float v1, int argb) {
        beginTextured(texture);
        float[] c = rgba(argb);
        builder.addVertex(matrix, x0, y0, 0.0F).setUv(u0, v0).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(matrix, x0, y1, 0.0F).setUv(u0, v1).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(matrix, x1, y1, 0.0F).setUv(u1, v1).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(matrix, x1, y0, 0.0F).setUv(u1, v0).setColor(c[0], c[1], c[2], c[3]);
    }

    /** 近似圆角矩形；半径为 0 时就是普通矩形。 */
    public void roundedRect(float x, float y, float width, float height, int radius, int argb) {
        int safe = Math.max(0, Math.min(radius, (int) Math.min(width, height) / 2));
        if (safe == 0) { rect(x, y, x + width, y + height, argb); return; }
        rect(x + safe, y, x + width - safe, y + height, argb);
        rect(x, y + safe, x + safe, y + height - safe, argb);
        rect(x + width - safe, y + safe, x + width, y + height - safe, argb);
        for (int row = 0; row < safe; row++) {
            float inset = safe - (float) Math.sqrt(Math.max(0, safe * safe - (safe - row - 0.5F) * (safe - row - 0.5F)));
            rect(x + inset, y + row, x + width - inset, y + row + 1, argb);
            rect(x + inset, y + height - row - 1, x + width - inset, y + height - row, argb);
        }
    }

    /** 任意四边形（斜角标记等）。 */
    public void quad(float x0, float y0, float x1, float y1, float x2, float y2,
                     float x3, float y3, int argb) {
        beginSolid();
        float[] c = rgba(argb);
        builder.addVertex(matrix, x0, y0, 0.0F).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(matrix, x1, y1, 0.0F).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(matrix, x2, y2, 0.0F).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(matrix, x3, y3, 0.0F).setColor(c[0], c[1], c[2], c[3]);
    }

    /** 斜角面板（带上下渐变）。 */
    public void chamferGradient(float x, float y, float width, float height, int cut,
                                 int topArgb, int bottomArgb) {
        beginSolid();
        int rows = Math.max(0, (int) height);
        int safeCut = Math.max(0, Math.min(cut, (int) Math.min(width, height) / 2));
        for (int row = 0; row < rows; row++) {
            float insetTop = row < safeCut ? safeCut - row : 0.0F;
            int fromBottom = rows - 1 - row;
            float insetBottom = fromBottom < safeCut ? safeCut - fromBottom : 0.0F;
            float left = x + insetTop;
            float right = x + width - insetBottom;
            if (right > left) {
                float progress = rows <= 1 ? 0.0F : row / (rows - 1.0F);
                rect(left, y + row, right, y + row + 1, mix(topArgb, bottomArgb, progress));
            }
        }
    }

    /** 斜角描边：铺一整块边框色，随后在其上画内缩的面板本体，露出的一圈即描边。 */
    public void chamferOutline(float x, float y, float width, float height, int cut, int argb) {
        chamferGradient(x, y, width, height, cut, argb, argb);
    }

    /** 上传当前批次并复原混合状态。 */
    public void finish() {
        if (builder != null) {
            BufferUploader.drawWithShader(builder.build());
            builder = null;
            boundTexture = null;
        }
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void close() {
        finish();
        graphics.flush();
    }

    private void beginSolid() {
        if (builder != null && boundTexture == null) {
            return;
        }
        finishBatch();
        graphics.flush();
        startCommon();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
                builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boundTexture = null;
    }

    private void beginTextured(ResourceLocation texture) {
        if (builder != null && Objects.equals(boundTexture, texture)) {
            return;
        }
        finishBatch();
        graphics.flush();
        startCommon();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        boundTexture = texture;
    }

    private void startCommon() {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private void finishBatch() {
        if (builder != null) {
            BufferUploader.drawWithShader(builder.build());
            builder = null;
            boundTexture = null;
        }
    }

    private static float[] rgba(int argb) {
        return new float[]{
                ((argb >> 16) & 0xFF) / 255.0F,
                ((argb >> 8) & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F,
                ((argb >> 24) & 0xFF) / 255.0F
        };
    }

    private static int mix(int from, int to, float progress) {
        int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * progress);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * progress);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * progress);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * progress);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 把颜色与 0..1 的透明度相乘，方便局部贴图控制透明度。 */
    public static int withAlpha(int argb, float alpha) {
        int a = (int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);
        return (argb & 0x00FF_FFFF) | (a << 24);
    }
}
