package trafilatura.compat;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Subset of Python's {@code argparse.ArgumentParser}. Parsed values are written into the
 * public fields of a namespace object, the field names being the argparse destinations.
 */
public class ArgumentParser {
    /** An argument definition ({@code add_argument(*flags, **kwargs)}). */
    public static final class Argument {
        final List<String> flags = new ArrayList<>();
        String dest;
        String help;
        String action = "store";
        String type = "str";
        String nargs;
        Object constValue;
        Object defaultValue;
        boolean hasDefault;
        List<String> choices;
        String version;
        Group exclusive;

        public Argument help(String v) { help = v; return this; }
        public Argument action(String v) { action = v; return this; }
        /** "str" or "int" */
        public Argument type(String v) { type = v; return this; }
        public Argument nargs(String v) { nargs = v; return this; }
        public Argument constant(Object v) { constValue = v; return this; }
        public Argument defaultValue(Object v) { defaultValue = v; hasDefault = true; return this; }
        public Argument dest(String v) { dest = v; return this; }
        public Argument choices(List<String> v) { choices = v; return this; }
        public Argument version(String v) { version = v; return this; }

        Object defaultFor() {
            if (hasDefault) {
                return defaultValue;
            }
            return switch (action) {
                case "store_true" -> false;
                case "store_false" -> true;
                default -> null;
            };
        }
    }

    /** Argument group, optionally mutually exclusive. */
    public final class Group {
        final String title;
        final boolean mutuallyExclusive;

        Group(String title, boolean mutuallyExclusive) {
            this.title = title;
            this.mutuallyExclusive = mutuallyExclusive;
        }

        public Argument add_argument(String... flags) {
            Argument argument = ArgumentParser.this.add_argument(flags);
            if (mutuallyExclusive) {
                argument.exclusive = this;
            }
            return argument;
        }

        public Group add_mutually_exclusive_group() {
            return new Group(title, true);
        }
    }

    private final String description;
    private final String prog;
    private final List<Argument> arguments = new ArrayList<>();
    public PrintStream out = System.out;
    public PrintStream err = System.err;

    public ArgumentParser(String description) {
        this(description, "trafilatura");
    }

    public ArgumentParser(String description, String prog) {
        this.description = description;
        this.prog = prog;
        add_argument("-h", "--help").action("help").help("show this help message and exit");
    }

    public Argument add_argument(String... flags) {
        Argument argument = new Argument();
        for (String flag : flags) {
            argument.flags.add(flag);
        }
        String longFlag = null;
        for (String flag : flags) {
            if (flag.startsWith("--")) {
                longFlag = flag;
                break;
            }
        }
        String name = longFlag != null ? longFlag.substring(2) : flags[0].substring(1);
        argument.dest = name.replace('-', '_');
        arguments.add(argument);
        return argument;
    }

    public Group add_argument_group(String title, String description) {
        return new Group(title, false);
    }

    public Group add_mutually_exclusive_group() {
        return new Group(null, true);
    }

    /** {@code parser.get_default(dest)} */
    public Object get_default(String dest) {
        for (Argument argument : arguments) {
            if (argument.dest.equals(dest)) {
                return argument.defaultFor();
            }
        }
        return null;
    }

    /** {@code parser.error(message)}: prints the usage and the message, exits with status 2. */
    public SystemExit error(String message) {
        err.println(usage());
        err.println(prog + ": error: " + message);
        return new SystemExit(2);
    }

    private String usage() {
        return "usage: " + prog + " [options]";
    }

    public String format_help() {
        StringBuilder sb = new StringBuilder(usage()).append("\n\n").append(description).append("\n\noptions:\n");
        for (Argument argument : arguments) {
            sb.append("  ").append(String.join(", ", argument.flags));
            if (argument.help != null) {
                sb.append("\n        ").append(argument.help);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private Argument findLong(String name) {
        Argument exact = null;
        List<Argument> prefixed = new ArrayList<>();
        for (Argument argument : arguments) {
            for (String flag : argument.flags) {
                if (flag.equals(name)) {
                    exact = argument;
                } else if (flag.startsWith("--") && flag.startsWith(name)) {
                    prefixed.add(argument);
                }
            }
        }
        if (exact != null) {
            return exact;
        }
        if (prefixed.size() == 1) {
            return prefixed.get(0);
        }
        if (prefixed.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Argument argument : prefixed) {
                names.add(argument.flags.get(argument.flags.size() - 1));
            }
            throw error("ambiguous option: " + name + " could match " + String.join(", ", names));
        }
        return null;
    }

    private Argument findShort(String flag) {
        for (Argument argument : arguments) {
            if (argument.flags.contains(flag)) {
                return argument;
            }
        }
        return null;
    }

    private Object convert(Argument argument, String value) {
        if (argument.choices != null && !argument.choices.contains(value)) {
            throw error("argument " + String.join("/", argument.flags) + ": invalid choice: '" + value
                    + "' (choose from " + String.join(", ", argument.choices.stream().map(c -> "'" + c + "'").toList()) + ")");
        }
        if (argument.type.equals("int")) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw error("argument " + String.join("/", argument.flags) + ": invalid int value: '" + value + "'");
            }
        }
        return value;
    }

    private static boolean isOption(String token) {
        return token.startsWith("-") && token.length() > 1 && !token.matches("-\\d+");
    }

    /** {@code parser.parse_args(args)}: values are written into {@code namespace}. */
    public <T> T parse_args(List<String> args, T namespace) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Argument argument : arguments) {
            if (!argument.action.equals("help") && !argument.action.equals("version")) {
                values.putIfAbsent(argument.dest, argument.defaultFor());
            }
        }
        Map<Group, Argument> exclusiveSeen = new LinkedHashMap<>();
        List<String> unrecognized = new ArrayList<>();

        int i = 0;
        while (i < args.size()) {
            String token = args.get(i);
            i++;
            if (!isOption(token)) {
                unrecognized.add(token);
                continue;
            }
            String inlineValue = null;
            Argument argument;
            if (token.startsWith("--")) {
                int eq = token.indexOf('=');
                String name = eq >= 0 ? token.substring(0, eq) : token;
                inlineValue = eq >= 0 ? token.substring(eq + 1) : null;
                argument = findLong(name);
            } else {
                String flag = token.substring(0, 2);
                argument = findShort(flag);
                if (argument != null && token.length() > 2) {
                    if (argument.action.equals("count")) {
                        // -vv
                        int count = 1;
                        for (char c : token.substring(2).toCharArray()) {
                            if (c == token.charAt(1)) {
                                count++;
                            }
                        }
                        values.put(argument.dest, ((Integer) values.getOrDefault(argument.dest, 0)) + count);
                        continue;
                    }
                    inlineValue = token.substring(2);
                }
            }
            if (argument == null) {
                unrecognized.add(token);
                continue;
            }
            if (argument.exclusive != null) {
                Argument previous = exclusiveSeen.get(argument.exclusive);
                if (previous != null && previous != argument) {
                    throw error("argument " + String.join("/", argument.flags) + ": not allowed with argument "
                            + String.join("/", previous.flags));
                }
                exclusiveSeen.put(argument.exclusive, argument);
            }
            switch (argument.action) {
                case "help" -> {
                    out.print(format_help());
                    throw new SystemExit(0);
                }
                case "version" -> {
                    out.println(argument.version);
                    throw new SystemExit(0);
                }
                case "store_true" -> values.put(argument.dest, true);
                case "store_false" -> values.put(argument.dest, false);
                case "count" -> values.put(argument.dest, (values.get(argument.dest) == null
                        ? 0 : (Integer) values.get(argument.dest)) + 1);
                default -> {
                    if ("?".equals(argument.nargs)) {
                        if (inlineValue != null) {
                            values.put(argument.dest, convert(argument, inlineValue));
                        } else if (i < args.size() && !isOption(args.get(i))) {
                            values.put(argument.dest, convert(argument, args.get(i)));
                            i++;
                        } else {
                            values.put(argument.dest, argument.constValue);
                        }
                    } else if ("+".equals(argument.nargs)) {
                        List<Object> items = new ArrayList<>();
                        if (inlineValue != null) {
                            items.add(convert(argument, inlineValue));
                        }
                        while (i < args.size() && !isOption(args.get(i))) {
                            items.add(convert(argument, args.get(i)));
                            i++;
                        }
                        if (items.isEmpty()) {
                            throw error("argument " + String.join("/", argument.flags) + ": expected at least one argument");
                        }
                        values.put(argument.dest, items);
                    } else {
                        String value = inlineValue;
                        if (value == null) {
                            if (i >= args.size() || isOption(args.get(i))) {
                                throw error("argument " + String.join("/", argument.flags) + ": expected one argument");
                            }
                            value = args.get(i);
                            i++;
                        }
                        values.put(argument.dest, convert(argument, value));
                    }
                }
            }
        }
        if (!unrecognized.isEmpty()) {
            throw error("unrecognized arguments: " + String.join(" ", unrecognized));
        }
        values.forEach((dest, value) -> setattr(namespace, dest, value));
        return namespace;
    }

    public <T> T parse_args(List<String> args, Supplier<T> factory) {
        return parse_args(args, factory.get());
    }

    /** {@code setattr(namespace, name, value)} on a public field. */
    public static void setattr(Object namespace, String name, Object value) {
        try {
            Field field = namespace.getClass().getField(name);
            Class<?> type = field.getType();
            if (value == null && type.isPrimitive()) {
                return;
            }
            if (type == int.class && value instanceof Integer v) {
                field.setInt(namespace, v);
            } else if (type == boolean.class && value instanceof Boolean v) {
                field.setBoolean(namespace, v);
            } else {
                field.set(namespace, value);
            }
        } catch (NoSuchFieldException | IllegalAccessException err) {
            throw new IllegalArgumentException("namespace has no attribute " + name, err);
        }
    }

    /** {@code getattr(namespace, name)} on a public field. */
    public static Object getattr(Object namespace, String name) {
        try {
            return namespace.getClass().getField(name).get(namespace);
        } catch (NoSuchFieldException | IllegalAccessException err) {
            throw new IllegalArgumentException("namespace has no attribute " + name, err);
        }
    }
}
