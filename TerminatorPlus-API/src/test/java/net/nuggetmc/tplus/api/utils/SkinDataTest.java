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

        assertEquals(id, invokeAccessor(profile, "id", "getId"));
        assertEquals("ConfiguredBot", invokeAccessor(profile, "name", "getName"));
        Object properties = invokeAccessor(profile, "properties", "getProperties");
        Object textureValues = invokeMethod(properties, "get", "textures");
        Object texture = assertSingle((Iterable<?>) textureValues);
        assertEquals("value", invokeAccessor(texture, "value", "getValue"));
        assertEquals("signature", invokeAccessor(texture, "signature", "getSignature"));
    }

    @Test
    void extractsOnlySignedTextureProperties() throws Exception {
        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
        Object unsigned = constructProperty(propertyClass, "textures", "unsigned-value");
        Object signed = constructProperty(propertyClass, "textures", "value", "signature");
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

    private static Object invokeAccessor(Object target, String... names) throws Exception {
        for (String name : names) {
            try {
                return invokeMethod(target, name);
            } catch (NoSuchMethodException ignored) {
                // Authlib changed from Java-record accessors to bean accessors
                // between Minecraft mappings; accept either shape.
            }
        }
        throw new NoSuchMethodException(java.util.Arrays.toString(names));
    }

    private static Object invokeMethod(Object target, String name, Object... arguments) throws Exception {
        Class<?> type = target.getClass();
        Class<?>[] signature = java.util.Arrays.stream(arguments)
                .map(value -> value == null ? Object.class : value.getClass())
                .toArray(Class<?>[]::new);
        Method method = null;
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == arguments.length) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            method = type.getDeclaredMethod(name, signature);
            method.setAccessible(true);
        }
        return method.invoke(target, arguments);
    }

    private static Object constructProperty(Class<?> type, String... values) throws Exception {
        for (var constructor : type.getConstructors()) {
            if (constructor.getParameterCount() != values.length) continue;
            Object[] arguments = java.util.Arrays.copyOf(values, values.length, Object[].class);
            return constructor.newInstance(arguments);
        }
        throw new NoSuchMethodException("Property constructor");
    }
}
