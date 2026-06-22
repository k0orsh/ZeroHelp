package com.korsh.chat;

import com.korsh.ZeroHelpMod;
import com.korsh.config.ConfigManager;
import com.korsh.config.ConfigManager.TriggerGroup;
import com.korsh.cooldown.CooldownManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.message.MessageType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import com.mojang.serialization.JsonOps;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.Locale;

/**
 * Перехватывает серверные сообщения чата.
 */
public class ChatListener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ConfigManager config;
    private final CooldownManager cooldown;

    public ChatListener(ConfigManager config, CooldownManager cooldown) {
        this.config = config;
        this.cooldown = cooldown;
    }

    public void onChatMessage(SignedMessage message, ServerPlayerEntity sender, MessageType.Parameters params) {
        String rawText = message.getSignedContent();
        ZeroHelpMod.ASYNC_EXECUTOR.submit(() -> processMessage(rawText, sender));
    }

    private void processMessage(String rawText, ServerPlayerEntity sender) {
        try {
            if (hasLuckPermsPermission(sender, "zerohelp.bypass")) return;
            if (cooldown.isGlobalOnCooldown()) return;

            String lower = rawText.toLowerCase(Locale.ROOT);

            String matched = config.getAho().findFirst(lower);
            if (matched == null) return;

            TriggerGroup group = findGroupByTrigger(matched);
            if (group == null) return;

            Long wordOverride = group.overrides().get(matched);
            if (wordOverride != null && cooldown.isWordOnCooldown(matched)) return;
            if (cooldown.isGroupOnCooldown(group.name())) return;

            cooldown.setGlobalCooldown(config.getGlobalCooldownMs());
            if (wordOverride != null) {
                cooldown.setWordCooldown(matched, wordOverride);
                cooldown.setGroupCooldown(group.name(), group.cooldownMs());
            } else {
                cooldown.setGroupCooldown(group.name(), group.cooldownMs());
            }

            String prefixPart = config.getPrefix().replace("<trigger>", matched);
            String fullMM = prefixPart + group.message();

            // Используем безопасный глобальный инстанс сервера
            var server = ZeroHelpMod.serverInstance;
            if (server == null) return;
            
            server.execute(() -> {
                try {
                    var component = MM.deserialize(fullMM);
                    String json = GsonComponentSerializer.gson().serialize(component);

                    JsonElement jsonElement = JsonParser.parseString(json);
                    
                    Text text = TextCodecs.CODEC.parse(JsonOps.INSTANCE, jsonElement)
                            .result()
                            .orElse(null);

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

    private TriggerGroup findGroupByTrigger(String trigger) {
        for (TriggerGroup g : config.getGroups()) {
            if (g.triggers().contains(trigger)) return g;
        }
        return null;
    }

    private boolean hasLuckPermsPermission(ServerPlayerEntity player, String permission) {
        try {
            Class<?> perms = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            var method = perms.getMethod("check",
                    net.minecraft.command.CommandSource.class, String.class, boolean.class);
            return (boolean) method.invoke(null, player, permission, false);
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Exception e) {
            ZeroHelpMod.LOGGER.warn("[ZeroHelp] Permission check error: {}", e.getMessage());
            return false;
        }
    }
}