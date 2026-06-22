package com.korsh.chat;

import com.korsh.ZeroHelpMod;
import com.korsh.config.ConfigManager;
import com.korsh.config.ConfigManager.TriggerGroup;
import com.korsh.cooldown.CooldownManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.Locale;

/**
 * Перехватывает серверные сообщения чата.
 * Парсинг вынесен в ASYNC_EXECUTOR, ответ — обратно на main-поток через server.execute().
 */
public class ChatListener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ConfigManager config;
    private final CooldownManager cooldown;

    public ChatListener(ConfigManager config, CooldownManager cooldown) {
        this.config = config;
        this.cooldown = cooldown;
    }

    /**
     * Вызывается Fabric API на главном потоке сервера.
     * Тяжёлая логика немедленно передаётся в асинхронный пул.
     */
    public void onChatMessage(SignedMessage message, ServerPlayerEntity sender,
                               net.minecraft.server.filter.FilteredMessage<net.minecraft.network.message.MessageType.Parameters> params) {
        String rawText = message.getSignedContent();

        // Передаём в async-поток, чтобы не блокировать tick
        ZeroHelpMod.ASYNC_EXECUTOR.submit(() -> processMessage(rawText, sender));
    }

    // ── Асинхронная обработка ─────────────────────────────────────────────────

    private void processMessage(String rawText, ServerPlayerEntity sender) {
        try {
            // 1. LuckPerms bypass-проверка
            if (hasLuckPermsPermission(sender, "zerohelp.bypass")) return;

            // 2. Глобальный кулдаун
            if (cooldown.isGlobalOnCooldown()) return;

            String lower = rawText.toLowerCase(Locale.ROOT);

            // 3. Быстрый поиск через Aho-Corasick
            String matched = config.getAho().findFirst(lower);
            if (matched == null) return;

            // 4. Найти группу, которой принадлежит триггер
            TriggerGroup group = findGroupByTrigger(matched);
            if (group == null) return;

            // 5. Проверяем индивидуальный кулдаун слова (если задан оверрайд)
            Long wordOverride = group.overrides().get(matched);
            if (wordOverride != null && cooldown.isWordOnCooldown(matched)) return;

            // 6. Проверяем кулдаун группы
            if (cooldown.isGroupOnCooldown(group.name())) return;

            // 7. Устанавливаем кулдауны ДО отправки, чтобы избежать гонки при параллельных сообщениях
            cooldown.setGlobalCooldown(config.getGlobalCooldownMs());
            if (wordOverride != null) {
                cooldown.setWordCooldown(matched, wordOverride);
                cooldown.setGroupCooldown(group.name(), group.cooldownMs());
            } else {
                cooldown.setGroupCooldown(group.name(), group.cooldownMs());
            }

            // 8. Формируем итоговый MiniMessage-текст
            String prefixPart = config.getPrefix().replace("<trigger>", matched);
            String fullMM = prefixPart + group.message();

            // 9. Рендерим на main-потоке и рассылаем всем игрокам
            var server = sender.getServer();
            if (server == null) return;
            server.execute(() -> {
                try {
                    // Конвертируем MiniMessage → Adventure Component → Gson → Minecraft Text
                    var component = MM.deserialize(fullMM);
                    String json = GsonComponentSerializer.gson().serialize(component);
                    Text text = Text.Serialization.fromJson(json, server.getRegistryManager());
                    if (text != null) {
                        server.getPlayerManager().broadcast(text, false);
                    }
                } catch (Exception e) {
                    ZeroHelpMod.LOGGER.error("[ZeroHelp] Failed to send message", e);
                }
            });

        } catch (Exception e) {
            ZeroHelpMod.LOGGER.error("[ZeroHelp] Error in async chat processing", e);
        }
    }

    // ── Вспомогательные методы ────────────────────────────────────────────────

    /** Находит группу по совпавшему триггеру. */
    private TriggerGroup findGroupByTrigger(String trigger) {
        for (TriggerGroup g : config.getGroups()) {
            if (g.triggers().contains(trigger)) return g;
        }
        return null;
    }

    /**
     * Проверяет permission через LuckPerms API (soft-dependency).
     * Если LP не установлен, считаем bypass=false.
     */
    private boolean hasLuckPermsPermission(ServerPlayerEntity player, String permission) {
        try {
            // Fabric Permissions API (входит в Fabric API 0.56+)
            // me.lucko.fabric.api.permissions.v0.Permissions
            Class<?> perms = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            var method = perms.getMethod("check",
                    net.minecraft.command.CommandSource.class, String.class, boolean.class);
            return (boolean) method.invoke(null, player, permission, false);
        } catch (ClassNotFoundException ignored) {
            // Fabric Permissions API не установлен — используем vanilla op-check
            return false;
        } catch (Exception e) {
            ZeroHelpMod.LOGGER.warn("[ZeroHelp] Permission check error: {}", e.getMessage());
            return false;
        }
    }
}
