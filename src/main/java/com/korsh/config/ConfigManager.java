package com.korsh.config;

import com.korsh.ZeroHelpMod;
import com.korsh.chat.AhoCorasick;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Читает TOML-конфиг вручную (без внешних зависимостей).
 * Поддерживает секции [[groups]], вложенные [groups.overrides].
 * Потокобезопасен: чтение через ReadLock, перезагрузка через WriteLock.
 */
public class ConfigManager {

    // ── Внутренние DTO ────────────────────────────────────────────────────────

    public record TriggerGroup(
            String name,
            List<String> triggers,          // в нижнем регистре
            long cooldownMs,                // базовый кулдаун группы
            String message,                 // MiniMessage-строка ответа
            Map<String, Long> overrides     // слово → индивидуальный кулдаун (мс)
    ) {}

    // ── Поля ──────────────────────────────────────────────────────────────────

    private final Path configPath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Собранные данные после загрузки
    private volatile String prefix = "<yellow>Зеро увидел слово <white><trigger></white>...</yellow> ";
    private volatile long globalCooldownMs = 30_000L;
    private volatile List<TriggerGroup> groups = List.of();
    private volatile AhoCorasick aho = new AhoCorasick(List.of());

    public ConfigManager() {
        // Ищем config/zerohelp/zerohelp.toml относительно рабочей директории сервера
        this.configPath = Paths.get("config", "zerohelp", "zerohelp.toml");
    }

    // ── Публичное API ─────────────────────────────────────────────────────────

    /** Загружает (или перезагружает) конфиг с диска. */
    public void load() {
        lock.writeLock().lock();
        try {
            ensureDefaultConfig();
            parseToml();
            ZeroHelpMod.LOGGER.info("[ZeroHelp] Config loaded: {} group(s).", groups.size());
        } catch (Exception e) {
            ZeroHelpMod.LOGGER.error("[ZeroHelp] Failed to load config!", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getPrefix() {
        lock.readLock().lock();
        try { return prefix; } finally { lock.readLock().unlock(); }
    }

    public long getGlobalCooldownMs() {
        lock.readLock().lock();
        try { return globalCooldownMs; } finally { lock.readLock().unlock(); }
    }

    public List<TriggerGroup> getGroups() {
        lock.readLock().lock();
        try { return groups; } finally { lock.readLock().unlock(); }
    }

    /** Возвращает Aho-Corasick автомат для быстрого поиска триггеров. */
    public AhoCorasick getAho() {
        lock.readLock().lock();
        try { return aho; } finally { lock.readLock().unlock(); }
    }

    // ── Приватная логика парсинга ─────────────────────────────────────────────

    private void parseToml() throws IOException {
        List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);

        String newPrefix = "<yellow>Зеро увидел слово <white><trigger></white>...</yellow> ";
        long newGlobal = 30_000L;
        List<TriggerGroup> newGroups = new ArrayList<>();

        // Состояние парсера
        String currentSection = "";       // "", "global", "group"
        String groupName = "";
        List<String> groupTriggers = new ArrayList<>();
        long groupCooldown = 600_000L;
        String groupMessage = "";
        Map<String, Long> groupOverrides = new LinkedHashMap<>();
        boolean inOverrides = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Заголовок секции
            if (line.startsWith("[[") && line.endsWith("]]")) {
                // Сохраняем предыдущую группу
                if ("group".equals(currentSection) && !groupName.isEmpty()) {
                    newGroups.add(buildGroup(groupName, groupTriggers, groupCooldown, groupMessage, groupOverrides));
                }
                currentSection = "group";
                groupName = unquote(line.substring(2, line.length() - 2).trim());
                groupTriggers = new ArrayList<>();
                groupCooldown = 600_000L;
                groupMessage = "";
                groupOverrides = new LinkedHashMap<>();
                inOverrides = false;
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                String header = line.substring(1, line.length() - 1).trim();
                if ("global".equals(header)) {
                    if ("group".equals(currentSection) && !groupName.isEmpty()) {
                        newGroups.add(buildGroup(groupName, groupTriggers, groupCooldown, groupMessage, groupOverrides));
                        groupName = "";
                    }
                    currentSection = "global";
                } else if (header.endsWith(".overrides")) {
                    inOverrides = true;
                }
                continue;
            }

            // Пары ключ = значение
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();

            if ("global".equals(currentSection)) {
                switch (key) {
                    case "prefix"          -> newPrefix = unquote(val);
                    case "global_cooldown" -> newGlobal = parseSeconds(val) * 1000L;
                }
            } else if ("group".equals(currentSection)) {
                if (inOverrides) {
                    // override: "слово" = секунды
                    groupOverrides.put(unquote(key).toLowerCase(Locale.ROOT), parseSeconds(val) * 1000L);
                } else {
                    switch (key) {
                        case "triggers" -> groupTriggers = parseStringArray(val);
                        case "cooldown"  -> groupCooldown = parseSeconds(val) * 1000L;
                        case "message"   -> groupMessage  = unquote(val);
                    }
                }
            }
        }

        // Последняя группа
        if ("group".equals(currentSection) && !groupName.isEmpty()) {
            newGroups.add(buildGroup(groupName, groupTriggers, groupCooldown, groupMessage, groupOverrides));
        }

        // Атомарно применяем
        prefix = newPrefix;
        globalCooldownMs = newGlobal;
        groups = Collections.unmodifiableList(newGroups);

        // Собираем все триггеры для Aho-Corasick
        List<String> allKeywords = new ArrayList<>();
        for (TriggerGroup g : groups) allKeywords.addAll(g.triggers());
        aho = new AhoCorasick(allKeywords);
    }

    private TriggerGroup buildGroup(String name, List<String> triggers, long cooldown,
                                    String message, Map<String, Long> overrides) {
        List<String> lower = triggers.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
        return new TriggerGroup(name, lower, cooldown, message, Map.copyOf(overrides));
    }

    // ── Вспомогательные методы парсинга ──────────────────────────────────────

    /** "слово" → слово (убирает кавычки) */
    private static String unquote(String s) {
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) ||
            (s.startsWith("'")  && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** ["a", "b", "c"] → List.of("a","b","c") */
    private static List<String> parseStringArray(String s) {
        s = s.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]"))   s = s.substring(0, s.length() - 1);
        List<String> result = new ArrayList<>();
        for (String part : s.split(",")) {
            String v = unquote(part.trim());
            if (!v.isEmpty()) result.add(v.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** Парсит целое число из строки (убирает возможные кавычки). */
    private static long parseSeconds(String s) {
        try { return Long.parseLong(unquote(s).trim()); }
        catch (NumberFormatException e) { return 600L; }
    }

    // ── Создание дефолтного конфига ───────────────────────────────────────────

    private void ensureDefaultConfig() throws IOException {
        if (Files.exists(configPath)) return;
        Files.createDirectories(configPath.getParent());

        String def = """
# ZeroHelp Configuration
# Формат TOML. Перезагрузка: /zerohelp reload

[global]
# Шаблон начала ответа. <trigger> заменяется на слово, написанное игроком.
prefix = "<yellow>Зеро увидел слово <white><trigger></white>...</yellow> "
# Глобальный кулдаун в секундах (молчание бота после любого ответа)
global_cooldown = 30

[[end]]
triggers = ["энд", "end", "конец", "дракон"]
cooldown = 600
message = "<green>Энд откроется после того, как кто-то убьёт визера!"

[[end.overrides]]
# Здесь можно переопределить кулдаун для конкретного слова из этой группы
# "энд" = 1200

[[discord]]
triggers = ["дискорд", "discord", "дс", "dc"]
cooldown = 600
message = "<aqua>Наш Discord: <white><click:open_url:'https://discord.gg/example'>discord.gg/example</click></white>"

[[discord.overrides]]
"дискорд" = 1800

[[ip]]
triggers = ["айпи", "ip", "адрес", "зайти"]
cooldown = 300
message = "<gold>IP сервера: <white>play.example.com:25565</white>"

[[ip.overrides]]
""";
        Files.writeString(configPath, def, StandardCharsets.UTF_8);
        ZeroHelpMod.LOGGER.info("[ZeroHelp] Default config created at {}", configPath.toAbsolutePath());
    }
}
