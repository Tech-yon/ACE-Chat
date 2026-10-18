package cn.acechat.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** 通讯 HUD 的两个快捷键。 */
public final class CommsKeybinds {
    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.comms.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H,
            "key.categories.comms");
    public static final KeyMapping PREVIEW = new KeyMapping(
            "key.comms.preview", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G,
            "key.categories.comms");
    public static final KeyMapping CONFIG = new KeyMapping(
            "key.comms.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
            "key.categories.comms");

    private CommsKeybinds() {
    }
}
