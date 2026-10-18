package cn.acechat.client;

import cn.acechat.AceChat;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** ACE Chat 的通讯接入音效。 */
public final class CommsSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, AceChat.MOD_ID);
    public static final RegistryObject<SoundEvent> COMMS_BLIP = SOUND_EVENTS.register("comms_blip", () -> SoundEvent.createVariableRangeEvent(AceChat.id("comms_blip")));
    private CommsSounds() { }
}
