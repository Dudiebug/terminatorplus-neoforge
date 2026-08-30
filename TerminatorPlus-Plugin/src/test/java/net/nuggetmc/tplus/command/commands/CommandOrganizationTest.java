package net.nuggetmc.tplus.command.commands;

import net.nuggetmc.tplus.command.annotation.Command;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOrganizationTest {

    @Test
    void botCanonicalGroupsAreVisibleAndLegacyLeavesStayHidden() throws Exception {
        for (String method : List.of("spawn", "inspect", "move", "equipment", "settings", "preset", "debug", "admin", "environment")) {
            assertTrue(command(BotCommand.class, method).visible(), method);
        }
        for (String method : List.of("create", "multi", "give", "place", "armor", "info", "count", "reset", "weapons", "combatDebug", "gather", "scatter", "setSpawn", "inventory", "loadout", "loadoutMix")) {
            assertFalse(command(BotCommand.class, method).visible(), method);
        }
    }

    @Test
    void canonicalSettingsKeepLegacyAliasesAndNormalizeGoalNames() {
        assertEquals("combat-goal", BotCommand.canonicalSettingsAction("setgoal"));
        assertEquals("target-mobs", BotCommand.canonicalSettingsAction("mobtarget"));
        assertEquals("target-region", BotCommand.canonicalSettingsAction("TARGET-REGION"));
        assertEquals("movement-v2", BotCommand.canonicalSettingsAction("movementv2"));
        assertEquals("set-spawn", BotCommand.canonicalSettingsAction("setspawn"));
        assertEquals("placement-material", BotCommand.canonicalSettingsAction("place"));
        assertEquals(null, BotCommand.canonicalSettingsAction("unknown"));
        assertEquals(EnumTargetGoal.NEAREST_HOSTILE, EnumTargetGoal.from("NEAREST-HOSTILE"));
    }

    @Test
    void aiCanonicalGroupsAreVisibleAndLegacyLeavesStayHidden() throws Exception {
        for (String method : List.of("spawn", "train", "brain", "evaluate", "inspect")) {
            assertTrue(command(AICommand.class, method).visible(), method);
        }
        for (String method : List.of("random", "reinforcement", "stop", "movement", "info")) {
            assertFalse(command(AICommand.class, method).visible(), method);
        }
    }

    private static Command command(Class<?> type, String methodName) throws Exception {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.isAnnotationPresent(Command.class)) {
                return method.getAnnotation(Command.class);
            }
        }
        throw new NoSuchMethodException(methodName);
    }
}
