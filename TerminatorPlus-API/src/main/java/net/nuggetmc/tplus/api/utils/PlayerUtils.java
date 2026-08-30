package net.nuggetmc.tplus.api.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.GameMode;
import net.nuggetmc.tplus.compat.bukkit.Location;

import net.nuggetmc.tplus.api.agent.legacyagent.LegacyMats;

import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PlayerUtils {

    // Synchronized set so USERNAME_CACHE.add() from fillUsernameCache() can safely
    // race with randomName()'s reads on another thread (Debugger and async bot
    // spawning both call randomName from worker threads).
    private static final Set<String> USERNAME_CACHE = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean usernameCacheLoaded = false;
    private static final Object USERNAME_CACHE_LOCK = new Object();

    public static boolean isInvincible(GameMode mode) {
        return mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE && mode != null;
    }

    public static String randomName() {
        ensureUsernameCacheLoaded();
        return MathUtils.getRandomSetElement(USERNAME_CACHE);
    }

    /**
     * Load the user cache once. Double-checked locking on a volatile flag so
     * concurrent first-callers from async bot-spawn tasks don't double-parse
     * the JSON file (before: the old {@code if (USERNAME_CACHE.isEmpty())} check
     * allowed the second thread to re-enter {@code fillUsernameCache} and
     * produce duplicated parse work with a stomp on the shared set).
     */
    private static void ensureUsernameCacheLoaded() {
        if (usernameCacheLoaded) return;
        synchronized (USERNAME_CACHE_LOCK) {
            if (usernameCacheLoaded) return;
            fillUsernameCache();
            usernameCacheLoaded = true;
        }
    }

    public static void fillUsernameCache() {
        String file = Bukkit.getServer().getWorldContainer().getAbsolutePath();
        file = file.substring(0, file.length() - 1) + "usercache.json";

        try {
            JsonArray array = JsonParser.parseReader(new FileReader(file)).getAsJsonArray();

            for (JsonElement obj : array) {
                JsonObject jsonOBJ = obj.getAsJsonObject();
                String username = jsonOBJ.get("name").getAsString();

                USERNAME_CACHE.add(username);
            }
        } catch (IOException | JsonParseException e) {
            DebugLogUtils.log("Failed to fetch from the usercache.");
        }
    }

    public static void clearUsernameCache() {
        synchronized (USERNAME_CACHE_LOCK) {
            USERNAME_CACHE.clear();
            usernameCacheLoaded = false;
        }
    }

    public static Location findAbove(Location loc, int amount) {
        boolean check = false;

        for (int i = 0; i <= amount; i++) {
            if (LegacyMats.isSolid(loc.clone().add(0, i, 0).getBlock().getType())) {
                check = true;
                break;
            }
        }

        if (check) {
            return loc;
        } else {
            return loc.clone().add(0, amount, 0);
        }
    }

    public static Location findBottom(Location loc) {
        loc.setY(loc.getBlockY());

        for (int i = 0; i < 255; i++) {
            Location check = loc.clone().add(0, -i, 0);

            if (check.getY() <= 0) {
                break;
            }

            if (LegacyMats.isSolid(check.getBlock().getType())) {
                return check.add(0, 1, 0);
            }
        }

        return loc;
    }
}
