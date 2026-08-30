package net.nuggetmc.tplus.api.utils;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.util.UUID;

public class CustomGameProfile {

    public static GameProfile create(UUID uuid, String name, SkinData skin) {
        GameProfile profile = new GameProfile(uuid, name);
        if (skin != null && skin.value() != null && !skin.value().isBlank()
                && skin.signature() != null && !skin.signature().isBlank()) {
            profile.getProperties().put("textures", new Property("textures", skin.value(), skin.signature()));
        }
        return profile;
    }

    @Deprecated
    public static GameProfile create(UUID uuid, String name, String[] skin) {
        return create(uuid, name, SkinData.fromLegacy(skin).orElse(null));
    }

    @Deprecated
    public static GameProfile create(UUID uuid, String name, String skinName) {
        return create(uuid, name, MojangAPI.getSkin(skinName));
    }
}
