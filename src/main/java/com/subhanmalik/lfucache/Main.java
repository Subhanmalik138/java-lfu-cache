package com.subhanmalik.lfucache;

import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

/**
 * Small REPL for playing with the LFU cache by hand, plus a scripted
 * {@code --demo} that shows why frequency beats recency.
 *
 * <p>Usage:
 * <pre>
 *   java -jar lfu-cache.jar [--capacity N] [--demo]
 * </pre>
 *
 * REPL commands: {@code put <key> <value>}, {@code get <key>},
 * {@code remove <key>}, {@code freq <key>}, {@code dump},
 * {@code stats}, {@code clear}, {@code help}, {@code exit}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int capacity = 3;
        boolean demo = false;
        for (int i = 0; i < args.length; i++) {
            if ("--capacity".equals(args[i]) && i + 1 < args.length) {
                capacity = Integer.parseInt(args[++i]);
            } else if ("--demo".equals(args[i])) {
                demo = true;
            } else {
                System.err.println("Unknown argument: " + args[i]);
                System.err.println("Usage: java -jar lfu-cache.jar [--capacity N] [--demo]");
                System.exit(2);
            }
        }

        LfuCache<String, String> cache = new LfuCache<>(capacity);
        if (demo) {
            runDemo(cache);
            return;
        }
        runRepl(cache);
    }

    /** Shows a hot key surviving while one-hit wonders get evicted. */
    private static void runDemo(LfuCache<String, String> cache) {
        System.out.println("LFU demo — capacity " + cache.capacity());
        put(cache, "home", "/");
        put(cache, "about", "/about");
        put(cache, "contact", "/contact");
        get(cache, "home");
        get(cache, "home");
        get(cache, "about");
        System.out.println("home is hot (freq " + cache.frequencyOf("home")
                + "), contact was never read (freq " + cache.frequencyOf("contact") + ")");
        put(cache, "pricing", "/pricing"); // cache is full: contact must go
        System.out.println("after inserting pricing:");
        dump(cache);
        System.out.println(cache.stats());
        System.out.println("contact evicted even though it was inserted last — "
                + "LFU evicts by frequency, not by age.");
    }

    private static void runRepl(LfuCache<String, String> cache) {
        System.out.println("LFU cache REPL — capacity " + cache.capacity()
                + ". Type 'help' for commands, 'exit' to quit.");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("lfu> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", 3);
            String command = parts[0].toLowerCase(Locale.ROOT);
            try {
                switch (command) {
                    case "exit", "quit" -> {
                        System.out.println("Bye.");
                        return;
                    }
                    case "help" -> printHelp();
                    case "put" -> {
                        require(parts, 3, "put <key> <value>");
                        cache.put(parts[1], parts[2]);
                        System.out.println("ok");
                    }
                    case "get" -> {
                        require(parts, 2, "get <key>");
                        String value = cache.get(parts[1]);
                        System.out.println(value == null ? "(miss)" : value);
                    }
                    case "remove" -> {
                        require(parts, 2, "remove <key>");
                        System.out.println(cache.remove(parts[1]) ? "removed" : "(absent)");
                    }
                    case "freq" -> {
                        require(parts, 2, "freq <key>");
                        System.out.println(cache.frequencyOf(parts[1]));
                    }
                    case "dump" -> dump(cache);
                    case "stats" -> System.out.println(cache.stats());
                    case "clear" -> {
                        cache.clear();
                        System.out.println("cleared");
                    }
                    default -> System.out.println("unknown command: " + command
                            + " (try 'help')");
                }
            } catch (IllegalArgumentException e) {
                System.out.println("error: " + e.getMessage());
            }
        }
    }

    private static void printHelp() {
        System.out.println("""
                put <key> <value>   store a value (updates count as an access)
                get <key>           fetch a value, or (miss)
                remove <key>        drop a key
                freq <key>          access frequency of a key (0 if absent)
                dump                show keys grouped by frequency
                stats               hits, misses, evictions, hit rate
                clear               empty the cache and reset stats
                exit                quit""");
    }

    private static void require(String[] parts, int n, String usage) {
        if (parts.length < n) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static void put(LfuCache<String, String> cache, String key, String value) {
        cache.put(key, value);
        System.out.println("put " + key + " = " + value);
    }

    private static void get(LfuCache<String, String> cache, String key) {
        System.out.println("get " + key + " -> " + cache.get(key));
    }

    private static void dump(LfuCache<String, String> cache) {
        for (Map.Entry<Integer, java.util.List<String>> e : cache.keysByFrequency().entrySet()) {
            System.out.println("  freq " + e.getKey() + ": " + e.getValue());
        }
        if (cache.size() == 0) {
            System.out.println("  (empty)");
        }
    }
}
