package net.nuggetmc.tplus.api.utils;

import com.mojang.authlib.properties.Property;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;

import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class MojangAPI {

    private static final int MAX_CACHE_ENTRIES = 256;
    private static final Map<String, SkinData> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, SkinData>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SkinData> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private static final Map<String, CompletableFuture<SkinLookup>> IN_FLIGHT = new ConcurrentHashMap<>();

    /**
     * Compatibility adapter for older callers. On the primary thread this only
     * returns an already-cached result; new code must use {@link #getSkinAsync}.
     */
    @Deprecated
    public static String[] getSkin(String name) {
        String requestedName = normalize(name);
        if (requestedName == null) return null;
        String key = cacheKey(requestedName);
        if (Bukkit.isPrimaryThread()) {
            SkinData cached = CACHE.get(key);
            return cached == null ? null : cached.toLegacyArray();
        }
        SkinLookup result = getSkinAsync(requestedName).join();
        return result.skin() == null ? null : result.skin().toLegacyArray();
    }

    /**
     * Resolves a username through Paper's asynchronous profile API. Only
     * successful signed texture properties enter the bounded cache.
     */
    public static CompletableFuture<SkinLookup> getSkinAsync(String name) {
        String requestedName = normalize(name);
        if (requestedName == null) return CompletableFuture.completedFuture(SkinLookup.notFound());
        String key = cacheKey(requestedName);

        SkinData cached = CACHE.get(key);
        if (cached != null) return CompletableFuture.completedFuture(SkinLookup.success(cached));

        CompletableFuture<SkinLookup> pending = new CompletableFuture<>();
        CompletableFuture<SkinLookup> existing = IN_FLIGHT.putIfAbsent(key, pending);
        if (existing != null) return existing;

        lookupProfile(requestedName, key, pending);
        return pending;
    }

    static SkinData extractTextures(Collection<Property> properties) {
        if (properties == null) return null;
        for (Property property : properties) {
            if (!"textures".equals(property.getName()) || !property.isSigned()) continue;
            String value = property.getValue();
            String signature = property.getSignature();
            if (value == null || value.isBlank() || signature == null || signature.isBlank()) continue;
            return new SkinData(value, signature);
        }
        return null;
    }

    private static void lookupProfile(String requestedName, String key, CompletableFuture<SkinLookup> result) {
        CompletableFuture.runAsync(() -> {
            try {
                java.net.URI lookup = java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/" + java.net.URLEncoder.encode(requestedName, java.nio.charset.StandardCharsets.UTF_8));
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                var response = client.send(java.net.http.HttpRequest.newBuilder(lookup).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) { completeLookup(key,result,SkinLookup.notFound()); return; }
                String id = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
                var textureResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                if (textureResponse.statusCode() != 200) { completeLookup(key,result,SkinLookup.notFound()); return; }
                var properties = com.google.gson.JsonParser.parseString(textureResponse.body()).getAsJsonObject().getAsJsonArray("properties");
                SkinData skin = null;
                if (properties != null) for (var element : properties) { var p=element.getAsJsonObject(); if ("textures".equals(p.get("name").getAsString()) && p.has("signature")) { skin=new SkinData(p.get("value").getAsString(),p.get("signature").getAsString()); break; } }
                if (skin != null) CACHE.put(key,skin);
                completeLookup(key,result,skin==null?SkinLookup.notFound():SkinLookup.success(skin));
            } catch (Exception e) { logFailure(requestedName,e); completeLookup(key,result,SkinLookup.unavailable(e)); }
        });
    }

    private static void completeLookup(String key, CompletableFuture<SkinLookup> result, SkinLookup lookup) {
        result.complete(lookup);
        IN_FLIGHT.remove(key, result);
    }

    private static void logFailure(String name, Throwable error) {
        Bukkit.getLogger().log(Level.WARNING, "Unable to resolve Minecraft skin for " + name, error);
    }

    static String normalize(String name) {
        if (name == null || name.isBlank()) return null;
        return name.trim();
    }

    static String cacheKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static void shutdown() {
        IN_FLIGHT.values().forEach(future -> future.cancel(true));
        IN_FLIGHT.clear();
        CACHE.clear();
    }

    public record SkinLookup(SkinData skin, Failure failure, Throwable error) {
        public enum Failure {
            NOT_FOUND,
            UNAVAILABLE
        }

        public static SkinLookup success(SkinData skin) {
            return new SkinLookup(skin, null, null);
        }

        public static SkinLookup notFound() {
            return new SkinLookup(null, Failure.NOT_FOUND, null);
        }

        public static SkinLookup unavailable(Throwable error) {
            return new SkinLookup(null, Failure.UNAVAILABLE, error);
        }
    }
}
