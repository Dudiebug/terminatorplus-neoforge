package net.nuggetmc.tplus.compat.bukkit.craftbukkit;
public class CraftServer extends net.nuggetmc.tplus.compat.bukkit.Server { private CraftServer(){super();} public net.minecraft.server.MinecraftServer getServer(){return getHandle();} }
