package net.nuggetmc.tplus.compat.bukkit.potion;
public enum PotionEffectType {
    SPEED(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED), SLOWNESS(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN),
    STRENGTH(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST), REGENERATION(net.minecraft.world.effect.MobEffects.REGENERATION),
    FIRE_RESISTANCE(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE), RESISTANCE(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE),
    ABSORPTION(net.minecraft.world.effect.MobEffects.ABSORPTION), HASTE(net.minecraft.world.effect.MobEffects.DIG_SPEED),
    JUMP_BOOST(net.minecraft.world.effect.MobEffects.JUMP), BLINDNESS(net.minecraft.world.effect.MobEffects.BLINDNESS),
    POISON(net.minecraft.world.effect.MobEffects.POISON), WITHER(net.minecraft.world.effect.MobEffects.WITHER),
    NIGHT_VISION(net.minecraft.world.effect.MobEffects.NIGHT_VISION), WATER_BREATHING(net.minecraft.world.effect.MobEffects.WATER_BREATHING),
    WEAKNESS(net.minecraft.world.effect.MobEffects.WEAKNESS), GLOWING(net.minecraft.world.effect.MobEffects.GLOWING),
    LEVITATION(net.minecraft.world.effect.MobEffects.LEVITATION);
    private final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> nms;
    PotionEffectType(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> nms){this.nms=nms;}
    public net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> nms(){return nms;}
}
