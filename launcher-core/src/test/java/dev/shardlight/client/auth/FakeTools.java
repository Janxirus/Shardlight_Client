package dev.shardlight.client.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stand-ins for macOS {@code security} and libsecret's {@code secret-tool} that behave like the real
 * programs (arguments, exit codes, interactive command parsing), so the stores' command construction and
 * quoting can be checked without running them.
 */
final class FakeTools {
    private FakeTools() {
    }

    /** one recorded invocation */
    record Call(List<String> command, String stdin) {
    }

    /** emulates /usr/bin/security for generic passwords */
    static final class Security implements ProcessRunner {
        final Map<String, String> items = new ConcurrentHashMap<>();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile boolean locked;
        volatile boolean dropWrites;

        @Override
        public Result run(List<String> command, String stdin, Duration timeout) throws IOException {
            calls.add(new Call(List.copyOf(command), stdin));
            if (!command.get(0).endsWith("security")) {
                throw new IOException("not found: " + command.get(0));
            }
            List<String> args = command.subList(1, command.size());
            if (args.equals(List.of("-i"))) {
                StringBuilder err = new StringBuilder();
                for (String line : stdin.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Result r = execute(tokenize(line));
                    err.append(r.stderr());
                }
                // like the real tool in interactive mode: errors go to stderr, exit status stays 0
                return new Result(0, "", err.toString());
            }
            return execute(args);
        }

        private Result execute(List<String> args) {
            if (args.isEmpty()) {
                return new Result(1, "", "usage");
            }
            if (args.get(0).equals("default-keychain")) {
                return new Result(0, "    \"/Users/test/Library/Keychains/login.keychain-db\"\n", "");
            }
            if (locked) {
                return new Result(51, "", "security: SecKeychainItemCopyContent: User interaction is not allowed.\n");
            }
            String service = option(args, "-s");
            String account = option(args, "-a");
            String id = service + "\u0000" + account;
            switch (args.get(0)) {
                case "add-generic-password" -> {
                    if (!args.contains("-U") && items.containsKey(id)) {
                        return new Result(45, "", "security: SecKeychainItemCreateFromContent: The specified item already exists in the keychain.\n");
                    }
                    if (!dropWrites) {
                        items.put(id, option(args, "-w"));
                    }
                    return new Result(0, "", "");
                }
                case "find-generic-password" -> {
                    String v = items.get(id);
                    if (v == null) {
                        return new Result(44, "", "security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.\n");
                    }
                    return new Result(0, args.contains("-w") ? v + "\n" : "keychain: ...\n", "");
                }
                case "delete-generic-password" -> {
                    if (items.remove(id) == null) {
                        return new Result(44, "", "security: SecKeychainSearchCopyNext: The specified item could not be found in the keychain.\n");
                    }
                    return new Result(0, "", "");
                }
                default -> {
                    return new Result(1, "", "unknown command");
                }
            }
        }

        private static String option(List<String> args, String name) {
            int i = args.indexOf(name);
            return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : null;
        }

        /**
         * Split an interactive command line like {@code security -i} does: whitespace separates words,
         * double or single quotes group them, a backslash escapes the next character.
         */
        static List<String> tokenize(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inWord = false;
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\' && i + 1 < line.length()) {
                    cur.append(line.charAt(++i));
                    inWord = true;
                } else if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    } else {
                        cur.append(c);
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                    inWord = true;
                } else if (Character.isWhitespace(c)) {
                    if (inWord) {
                        out.add(cur.toString());
                        cur.setLength(0);
                        inWord = false;
                    }
                } else {
                    cur.append(c);
                    inWord = true;
                }
            }
            if (inWord) {
                out.add(cur.toString());
            }
            return out;
        }
    }

    /** emulates libsecret's secret-tool */
    static final class SecretTool implements ProcessRunner {
        final Map<String, String> items = new ConcurrentHashMap<>();
        final Map<String, String> labels = new ConcurrentHashMap<>();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        /** simulate a session without a running Secret Service */
        volatile boolean noDbus;
        /** simulate a broken service that forgets everything */
        volatile boolean forgetful;

        @Override
        public Result run(List<String> command, String stdin, Duration timeout) throws IOException {
            calls.add(new Call(List.copyOf(command), stdin));
            if (noDbus) {
                return new Result(1, "", "secret-tool: Cannot autolaunch D-Bus without X11 $DISPLAY\n");
            }
            List<String> args = command.subList(1, command.size());
            String verb = args.get(0);
            List<String> rest = new ArrayList<>(args.subList(1, args.size()));
            String label = null;
            if (!rest.isEmpty() && rest.get(0).startsWith("--label=")) {
                label = rest.remove(0).substring("--label=".length());
            }
            if (rest.size() % 2 != 0) {
                return new Result(2, "", "usage: secret-tool ...");
            }
            String id = String.join("\u0000", rest);
            switch (verb) {
                case "store" -> {
                    if (label == null) {
                        return new Result(2, "", "secret-tool: must specify a label for the item being stored");
                    }
                    if (!forgetful) {
                        items.put(id, stdin == null ? "" : stdin);
                        labels.put(id, label);
                    }
                    return new Result(0, "", "");
                }
                case "lookup" -> {
                    String v = items.get(id);
                    return v == null ? new Result(1, "", "") : new Result(0, v, "");
                }
                case "clear" -> {
                    items.remove(id);
                    return new Result(0, "", "");
                }
                default -> {
                    return new Result(2, "", "usage");
                }
            }
        }
    }
}
