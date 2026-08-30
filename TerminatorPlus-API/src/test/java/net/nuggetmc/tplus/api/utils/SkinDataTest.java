package net.nuggetmc.tplus.api.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkinDataTest {

    @Test
    void createsProfileWithSignedTexturesAndPreservesIdentity() throws Exception {
        UUID id = UUID.randomUUID();
        SkinData skin = new SkinData("value", "signature");

        Object profile = CustomGameProfile.class
                .getMethod("create", UUID.class, String.class, SkinData.class)
                .invoke(null, id, "ConfiguredBot", skin);

        assertEquals(id, profile.getClass().getMethod("id").invoke(profile));
        assertEquals("ConfiguredBot", profile.getClass().getMethod("name").invoke(profile));
        Object properties = profile.getClass().getMethod("properties").invoke(profile);
        Object textureValues = properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
        Object texture = assertSingle((Iterable<?>) textureValues);
        assertEquals("value", texture.getClass().getMethod("value").invoke(texture));
        assertEquals("signature", texture.getClass().getMethod("signature").invoke(texture));
    }

    @Test
    void extractsOnlySignedTextureProperties() throws Exception {
        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
        Object unsigned = propertyClass.getConstructor(String.class, String.class)
                .newInstance("textures", "unsigned-value");
        Object signed = propertyClass.getConstructor(String.class, String.class, String.class)
                .newInstance("textures", "value", "signature");
        Method extract = MojangAPI.class.getDeclaredMethod("extractTextures", Collection.class);
        extract.setAccessible(true);

        assertNull(extract.invoke(null, List.of(unsigned)));
        assertEquals(new SkinData("value", "signature"), extract.invoke(null, List.of(signed)));
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

    private static Object assertSingle(Iterable<?> properties) {
        var iterator = properties.iterator();
        assertTrue(iterator.hasNext());
        Object property = iterator.next();
        assertFalse(iterator.hasNext());
        return property;
    }
}
