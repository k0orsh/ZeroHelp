package com.korsh.cooldown;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Управляет двумя уровнями кулдаунов:
 *  1. Глобальный — блокирует все триггеры на N секунд после любого ответа.
 *  2. Групповой / индивидуальный — per-key таймаут (ключ = имя группы или конкретное слово).
 *
 * Потокобезопасен: использует ConcurrentHashMap + volatile timestamp.
 */
public class CooldownManager {

    // Глобальный кулдаун — один timestamp для всего бота
    private volatile long globalUntil = 0L;

    // Ключ → время окончания кулдауна (мс эпохи)
    // Ключи: "group:<name>" или "word:<word>"
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>(32);

    // ── Глобальный кулдаун ────────────────────────────────────────────────────

    /** Возвращает true, если глобальный кулдаун ещё активен. */
    public boolean isGlobalOnCooldown() {
        return System.currentTimeMillis() < globalUntil;
    }

    /** Устанавливает глобальный кулдаун на durationMs миллисекунд с текущего момента. */
    public void setGlobalCooldown(long durationMs) {
        globalUntil = System.currentTimeMillis() + durationMs;
    }

    // ── Групповой / словарный кулдаун ─────────────────────────────────────────

    public boolean isGroupOnCooldown(String groupName) {
        return isKeyOnCooldown("group:" + groupName);
    }

    public void setGroupCooldown(String groupName, long durationMs) {
        setKeyCooldown("group:" + groupName, durationMs);
    }

    public boolean isWordOnCooldown(String word) {
        return isKeyOnCooldown("word:" + word);
    }

    public void setWordCooldown(String word, long durationMs) {
        setKeyCooldown("word:" + word, durationMs);
    }

    // ── Внутренние методы ─────────────────────────────────────────────────────

    private boolean isKeyOnCooldown(String key) {
        Long until = cooldowns.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    private void setKeyCooldown(String key, long durationMs) {
        cooldowns.put(key, System.currentTimeMillis() + durationMs);
    }

    /**
     * Чистка протухших записей (вызывается редко, например при reload).
     * Предотвращает утечку памяти при большом количестве уникальных триггеров.
     */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(e -> e.getValue() < now);
    }
}
