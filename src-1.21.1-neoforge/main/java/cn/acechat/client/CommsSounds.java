package cn.acechat.client;

import cn.acechat.AceChat;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.neoforged.neoforge.registries.DeferredHolder;

/** ACE Chat 的通讯接入音效。 */
public final class CommsSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(net.minecraft.core.registries.Registries.SOUND_EVENT, AceChat.MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> COMMS_BLIP = SOUND_EVENTS.register("comms_blip", () -> SoundEvent.createVariableRangeEvent(AceChat.id("comms_blip")));
    private CommsSounds() { }
}
