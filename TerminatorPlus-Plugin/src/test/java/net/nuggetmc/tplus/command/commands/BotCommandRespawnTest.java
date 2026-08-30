package net.nuggetmc.tplus.command.commands;

import net.nuggetmc.tplus.command.annotation.Require;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BotCommandRespawnTest {

    @Test
    void acceptsOnlyExplicitBooleanValues() {
        assertEquals(Boolean.TRUE, BotCommand.parseBoolean("TRUE"));
        assertEquals(Boolean.FALSE, BotCommand.parseBoolean("false"));
        assertNull(BotCommand.parseBoolean("on"));
        assertNull(BotCommand.parseBoolean(""));
    }

    @Test
    void setSpawnUsesAdminPermissionAndCanonicalName() throws NoSuchMethodException {
        Require require = BotCommand.class
                .getMethod("setSpawn", CommandSender.class, String.class)
                .getAnnotation(Require.class);

        assertEquals("terminatorplus.admin", require.value());
        assertEquals("set-spawn", BotCommand.canonicalSettingsAction("setspawn"));
    }
}
