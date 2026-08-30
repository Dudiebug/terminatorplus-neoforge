package net.nuggetmc.tplus.compat.bukkit.potion;
public final class PotionEffect {
    private final PotionEffectType type; private final int duration, amplifier; private final boolean ambient, particles, icon;
    public PotionEffect(PotionEffectType type,int duration,int amplifier){this(type,duration,amplifier,false,true,true);}
    public PotionEffect(PotionEffectType type,int duration,int amplifier,boolean ambient,boolean particles,boolean icon){this.type=type;this.duration=duration;this.amplifier=amplifier;this.ambient=ambient;this.particles=particles;this.icon=icon;}
    public PotionEffectType getType(){return type;} public int getDuration(){return duration;} public int getAmplifier(){return amplifier;}
    public net.minecraft.world.effect.MobEffectInstance toNms(){return new net.minecraft.world.effect.MobEffectInstance(type.nms(),duration,amplifier,ambient,particles,icon);}
}
