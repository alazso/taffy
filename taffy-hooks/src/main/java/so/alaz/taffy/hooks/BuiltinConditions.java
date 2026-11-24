package so.alaz.taffy.hooks;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** The condition types a new {@link ConditionRegistry} holds. */
final class BuiltinConditions {

    private enum Operator {
        EQUALS(false, "equals", "==", "="),
        NOT_EQUALS(false, "!=", "ne", "not"),
        CONTAINS(false, "contains"),
        GREATER(true, ">", "gt"),
        AT_LEAST(true, ">=", "gte"),
        LESS(true, "<", "lt"),
        AT_MOST(true, "<=", "lte");

        private final boolean numeric;
        private final List<String> names;

        Operator(boolean numeric, String... names) {
            this.numeric = numeric;
            this.names = List.of(names);
        }

        static @Nullable Operator named(String written) {
            String name = written.trim().toLowerCase(Locale.ROOT);
            for (Operator operator : values()) {
                if (operator.names.contains(name)) {
                    return operator;
                }
            }
            return null;
        }

        boolean matches(String resolved, String value) {
            if (!numeric) {
                return switch (this) {
                    case NOT_EQUALS -> !resolved.equalsIgnoreCase(value);
                    case CONTAINS -> resolved.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
                    default -> resolved.equalsIgnoreCase(value);
                };
            }
            Double left = number(resolved);
            Double right = number(value);
            if (left == null || right == null) {
                return false;
            }
            return switch (this) {
                case GREATER -> left > right;
                case AT_LEAST -> left >= right;
                case LESS -> left < right;
                default -> left <= right;
            };
        }
    }

    private BuiltinConditions() {
    }

    static void register(ConditionRegistry registry, BiFunction<String, Player, String> placeholders,
                         Supplier<@Nullable RegionProvider> regions) {
        registry.register(ConditionRegistry.PERMISSION, BuiltinConditions::permission);
        registry.register(ConditionRegistry.WORLD, BuiltinConditions::world);
        registry.register(ConditionRegistry.PLACEHOLDER, config -> placeholder(config, placeholders));
        registry.register(ConditionRegistry.REGION, config -> region(config, regions));
    }

    private static @Nullable Condition permission(ConditionConfig config) {
        String node = config.requiredText("permission");
        ConditionDenial denial = config.denial(ConditionRegistry.PERMISSION_KEY);
        if (node == null) {
            return null;
        }
        return player -> player.hasPermission(node) ? ConditionResult.pass() : denial.result(player);
    }

    private static @Nullable Condition world(ConditionConfig config) {
        List<String> worlds = names(config, "worlds");
        ConditionDenial denial = config.denial(ConditionRegistry.WORLD_KEY);
        if (worlds == null) {
            return null;
        }
        Set<String> allowed = Set.copyOf(worlds);
        return player -> allowed.contains(player.getWorld().getName()) ? ConditionResult.pass()
            : denial.result(player);
    }

    private static @Nullable Condition placeholder(ConditionConfig config,
                                                   BiFunction<String, Player, String> placeholders) {
        String placeholder = config.requiredText("placeholder");
        String written = config.text("operator");
        String value = config.text("value");
        Operator operator = written == null ? Operator.EQUALS : Operator.named(written);
        if (operator == null) {
            config.error("operator", "is \"" + written + "\", which is not an operator",
                "use one of equals, !=, contains, >, >=, < and <=");
        }
        if (config.value("value") == null) {
            config.error("value", "is missing", null);
        } else if (value != null && operator != null && operator.numeric && number(value) == null) {
            config.error("value", "is \"" + value + "\", which is not a number, and the operator " + written
                + " compares numbers", null);
        }
        ConditionDenial denial = config.denial(ConditionRegistry.NOT_MET_KEY);
        if (placeholder == null || operator == null || value == null) {
            return null;
        }
        return player -> operator.matches(placeholders.apply(placeholder, player), value) ? ConditionResult.pass()
            : denial.result(player);
    }

    private static @Nullable Condition region(ConditionConfig config,
                                              Supplier<@Nullable RegionProvider> regions) {
        List<String> wanted = names(config, "regions");
        ConditionDenial denial = config.denial(ConditionRegistry.REGION_KEY);
        ConditionDenial unavailable = config.denial(ConditionRegistry.REGION_UNAVAILABLE_KEY);
        if (wanted == null) {
            return null;
        }
        return player -> {
            RegionProvider provider = regions.get();
            if (provider == null || !provider.available()) {
                return unavailable.result(player);
            }
            for (String id : provider.regionsAt(player.getLocation())) {
                for (String region : wanted) {
                    if (region.equalsIgnoreCase(id)) {
                        return ConditionResult.pass();
                    }
                }
            }
            return denial.result(player);
        };
    }

    /** The list at {@code key}, or {@code null} after an error: an absent key, an empty list or a bad entry. */
    private static @Nullable List<String> names(ConditionConfig config, String key) {
        int before = config.errors();
        List<String> names = config.texts(key);
        if (config.errors() > before) {
            return null;
        }
        if (names.isEmpty()) {
            config.error(key, config.value(key) == null ? "is missing" : "is an empty list", "list at least one name");
            return null;
        }
        return names;
    }

    private static @Nullable Double number(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
