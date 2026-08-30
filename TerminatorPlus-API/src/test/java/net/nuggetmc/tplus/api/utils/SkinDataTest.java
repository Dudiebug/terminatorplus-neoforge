package net.nuggetmc.tplus.api.utils;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkinDataTest {

    @Test
    void createsProfileWithSignedTexturesAndPreservesIdentity() {
        UUID id = UUID.randomUUID();
        SkinData skin = new SkinData("value", "signature");

        GameProfile profile = CustomGameProfile.create(id, "ConfiguredBot", skin);

        assertEquals(id, profile.id());
        assertEquals("ConfiguredBot", profile.name());
        Property texture = assertSingle(profile.properties().get("textures"));
        assertEquals("value", texture.value());
        assertEquals("signature", texture.signature());
    }

    @Test
    void extractsOnlySignedTextureProperties() {
        Property unsigned = new Property("textures", "unsigned-value");
        Property signed = new Property("textures", "value", "signature");

        assertNull(MojangAPI.extractTextures(List.of(unsigned)));
        assertEquals(new SkinData("value", "signature"), MojangAPI.extractTextures(List.of(signed)));
    }

    @Test
    void legacyAdapterRejectsMalformedDataAndCopiesValues() {
        String[] legacy = {"value", "signature"};
        SkinData skin = SkinData.fromLegacy(legacy).orElseThrow();

        legacy[0] = "changed";

        assertEquals("value", skin.value());
        assertArrayEquals(new String[]{"value", "signature"}, skin.toLegacyArray());
        assertTrue(SkinData.fromLegacy(new String[]{"value"}).isEmpty());
        assertTrue(SkinData.fromLegacy(new String[]{"", "signature"}).isEmpty());
    }

    @Test
    void preservesUsernameCasingForLookupWhileSharingCacheEntries() {
        String requested = MojangAPI.normalize("  Notch ");

        assertEquals("Notch", requested);
        assertEquals("notch", MojangAPI.cacheKey(requested));
    }

    private static Property assertSingle(Iterable<Property> properties) {
        var iterator = properties.iterator();
        assertTrue(iterator.hasNext());
        Property property = iterator.next();
        assertFalse(iterator.hasNext());
        return property;
    }
}
