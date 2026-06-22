package com.korsh.chat;

import java.util.*;

/**
 * Алгоритм Ахо-Корасик — находит все вхождения паттернов за O(n + m + z),
 * где n — длина текста, m — суммарная длина паттернов, z — число совпадений.
 * Нечувствителен к регистру (все паттерны передаются в lower-case).
 */
public class AhoCorasick {

    private static final int ALPHA = 65536; // Unicode BMP

    // Узел автомата
    private static final class Node {
        final int[] ch;       // переходы по символам
        int fail;             // суффиксная ссылка
        String output;        // слово-совпадение (null если нет)
        String dictSuffix;    // словарная суффиксная ссылка (ближайший выходной предок)

        Node() {
            ch = new int[0]; // ленивое выделение; полное дерево занимает много RAM
            fail = 0;
            output = null;
            dictSuffix = null;
        }
    }

    // Компактный узел с HashMap для экономии памяти при большом алфавите
    private static final class CompactNode {
        final Map<Character, Integer> ch = new HashMap<>(4);
        int fail = 0;
        String output = null;
        String dictSuffix = null;
    }

    private final List<CompactNode> nodes = new ArrayList<>();
    private boolean built = false;

    public AhoCorasick(Collection<String> patterns) {
        // Корень — узел 0
        nodes.add(new CompactNode());
        for (String p : patterns) {
            if (p != null && !p.isEmpty()) addPattern(p);
        }
        build();
    }

    // ── Построение автомата ───────────────────────────────────────────────────

    private void addPattern(String word) {
        int cur = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i); // уже lower-case
            int next = nodes.get(cur).ch.getOrDefault(c, -1);
            if (next == -1) {
                next = nodes.size();
                nodes.add(new CompactNode());
                nodes.get(cur).ch.put(c, next);
            }
            cur = next;
        }
        // Сохраняем более длинное слово (приоритет длинным триггерам)
        CompactNode leaf = nodes.get(cur);
        if (leaf.output == null || leaf.output.length() < word.length()) {
            leaf.output = word;
        }
    }

    /** BFS для построения суффиксных ссылок. */
    private void build() {
        Queue<Integer> queue = new ArrayDeque<>();
        CompactNode root = nodes.get(0);
        root.fail = 0;

        // Дети корня: fail → 0
        for (Map.Entry<Character, Integer> e : root.ch.entrySet()) {
            int child = e.getValue();
            nodes.get(child).fail = 0;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            int u = queue.poll();
            CompactNode nu = nodes.get(u);

            // dictSuffix
            CompactNode failNode = nodes.get(nu.fail);
            nu.dictSuffix = (failNode.output != null) ? failNode.output : failNode.dictSuffix;

            for (Map.Entry<Character, Integer> e : nu.ch.entrySet()) {
                char c = e.getKey();
                int v = e.getValue();
                // Суффиксная ссылка для v: идём по fail-цепочке от u
                int f = nu.fail;
                while (f != 0 && !nodes.get(f).ch.containsKey(c)) {
                    f = nodes.get(f).fail;
                }
                int fNext = nodes.get(f).ch.getOrDefault(c, 0);
                nodes.get(v).fail = (fNext == v) ? 0 : fNext;
                queue.add(v);
            }
        }
        built = true;
    }

    // ── Поиск ────────────────────────────────────────────────────────────────

    /**
     * Находит первое совпадение паттерна в тексте (lower-case).
     * @return найденное ключевое слово или null
     */
    public String findFirst(String text) {
        if (!built || nodes.size() == 1) return null;
        int cur = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Идём по fail до тех пор, пока не найдём переход или не дойдём до корня
            while (cur != 0 && !nodes.get(cur).ch.containsKey(c)) {
                cur = nodes.get(cur).fail;
            }
            cur = nodes.get(cur).ch.getOrDefault(c, 0);

            CompactNode node = nodes.get(cur);
            if (node.output != null) return node.output;
            if (node.dictSuffix != null) return node.dictSuffix;
        }
        return null;
    }

    /** Возвращает все уникальные совпадения. */
    public List<String> findAll(String text) {
        if (!built || nodes.size() == 1) return List.of();
        List<String> results = new ArrayList<>();
        int cur = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            while (cur != 0 && !nodes.get(cur).ch.containsKey(c)) {
                cur = nodes.get(cur).fail;
            }
            cur = nodes.get(cur).ch.getOrDefault(c, 0);
            CompactNode node = nodes.get(cur);
            if (node.output != null)     results.add(node.output);
            if (node.dictSuffix != null) results.add(node.dictSuffix);
        }
        return results;
    }
}
