package com.korsh.command;

import com.korsh.ZeroHelpMod;
import com.korsh.config.ConfigManager;
import com.korsh.cooldown.CooldownManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager.RegistrationEnvironment;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Регистрирует /zerohelp reload.
 * Доступна игрокам с уровнем опки ≥ 2 или с пермишеном zerohelp.admin (через Fabric Permissions API).
 */
public class CommandManager {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                ConfigManager configManager,
                                CooldownManager cooldownManager) {
        dispatcher.register(
                literal("zerohelp")
                        .requires(src -> src.hasPermissionLevel(2) || hasAdminPermission(src))
                        .then(literal("reload")
                                .executes(ctx -> {
                                    ServerCommandSource src = ctx.getSource();
                                    try {
                                        // Перезагружаем конфиг (не сбрасываем кулдауны!)
                                        configManager.load();
                                        // Чистим протухшие кулдауны — хорошая практика при reload
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

    /** Мягкая проверка через Fabric Permissions API (если установлен). */
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
