package com.korsh;

import com.korsh.chat.ChatListener;
import com.korsh.command.CommandManager;
import com.korsh.config.ConfigManager;
import com.korsh.cooldown.CooldownManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ZeroHelpMod implements ModInitializer {

    public static final String MOD_ID = "zerohelp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Пул потоков для асинхронной обработки чата (не блокирует main tick)
    public static final ExecutorService ASYNC_EXECUTOR =
            Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "ZeroHelp-Chat-Worker");
                t.setDaemon(true);
                return t;
            });

    private static ConfigManager configManager;
    private static CooldownManager cooldownManager;
    
    // Глобальная ссылка на сервер для обхода проблем с маппингами игрока
    public static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        LOGGER.info("[ZeroHelp] Initializing...");

        configManager = new ConfigManager();
        configManager.load();

        cooldownManager = new CooldownManager();

        // Регистрируем трекер сервера
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            serverInstance = server;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            serverInstance = null;
        });

        // Регистрируем слушатель чата
        ChatListener chatListener = new ChatListener(configManager, cooldownManager);
        ServerMessageEvents.CHAT_MESSAGE.register(chatListener::onChatMessage);

        // Регистрируем команду /zerohelp reload
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CommandManager.register(dispatcher, configManager, cooldownManager));

        LOGGER.info("[ZeroHelp] Ready.");
    }

    public static ConfigManager getConfigManager() { return configManager; }
    public static CooldownManager getCooldownManager() { return cooldownManager; }
}