package com.korsh.command;

import com.korsh.ZeroHelpMod;
import com.korsh.config.ConfigManager;
import com.korsh.cooldown.CooldownManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Регистрирует /zerohelp reload.
 */
public class CommandManager {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                ConfigManager configManager,
                                CooldownManager cooldownManager) {
        dispatcher.register(
                literal("zerohelp")
                        .requires(src -> checkPermission(src, 2))
                        .then(literal("reload")
                                .executes(ctx -> {
                                    ServerCommandSource src = ctx.getSource();
                                    try {
                                        configManager.load();
                                        cooldownManager.evictExpired();

                                        src.sendFeedback(() ->
                                                Text.literal("§a[ZeroHelp] Config reloaded successfully."), true);
                                        ZeroHelpMod.LOGGER.info("[ZeroHelp] Config reloaded by {}", src.getName());
                                    } catch (Exception e) {
                                        src.sendError(Text.literal("§c[ZeroHelp] Reload failed: " + e.getMessage()));
                                        ZeroHelpMod.LOGGER.error("[ZeroHelp] Reload error", e);
                                    }
                                    return 1;
                                })
                        )
        );
    }

    /**
     * Универсальный метод проверки прав, устойчивый к изменениям названий методов в маппингах.
     */
    private static boolean checkPermission(ServerCommandSource src, int level) {
        if (hasAdminPermission(src)) {
            return true;
        }
        
        try {
            // Если команда отправлена из консоли или командного блока, разрешаем её выполнение
            java.lang.reflect.Method getEntityMethod = src.getClass().getMethod("getEntity");
            Object entity = getEntityMethod.invoke(src);
            if (entity == null) {
                return true; 
            }
            
            // Если это игрок, проверяем его OP статус через менеджер игроков
            if (ZeroHelpMod.serverInstance != null) {
                java.lang.reflect.Method getGameProfileMethod = entity.getClass().getMethod("getGameProfile");
                Object profile = getGameProfileMethod.invoke(entity);
                
                Object playerManager = ZeroHelpMod.serverInstance.getPlayerManager();
                java.lang.reflect.Method isOperatorMethod = playerManager.getClass().getMethod("isOperator", profile.getClass());
                return (boolean) isOperatorMethod.invoke(playerManager, profile);
            }
        } catch (Exception e) {
            // Последний рубеж: если структуры сервера недоступны, пробуем вызвать методы маппингов через рефлексию
            try {
                java.lang.reflect.Method m = src.getClass().getMethod("hasPermissionLevel", int.class);
                return (boolean) m.invoke(src, level);
            } catch (Exception ignored) {
                try {
                    java.lang.reflect.Method m = src.getClass().getMethod("hasPermission", int.class);
                    return (boolean) m.invoke(src, level);
                } catch (Exception ignored2) {}
            }
        }
        return false;
    }

    private static boolean hasAdminPermission(ServerCommandSource src) {
        try {
            Class<?> perms = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            var method = perms.getMethod("check",
                    net.minecraft.command.CommandSource.class, String.class, boolean.class);
            return (boolean) method.invoke(null, src, "zerohelp.admin", false);
        } catch (Exception ignored) {
            return false;
        }
    }
}