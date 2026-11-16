package so.alaz.taffy.hooks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.ScopedNode;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryOptionsRegistry;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.platform.ThrowLog;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

// Loaded only after LuckPerms was found installed, enabled and with its API present. The API supplier throws an
// IllegalStateException until LuckPerms has finished loading, which counts as not available and is not reported.
final class LuckPermsPermissions implements PermissionProvider {

    private static final String SOURCE = "hook.luckperms";

    private final Plugin luckPerms;
    private final Supplier<LuckPerms> api;
    private final ThrowLog throwLog;
    private final ErrorReports errors;

    LuckPermsPermissions(Plugin luckPerms, Supplier<LuckPerms> api, Logger logger, ErrorReports errors) {
        this.luckPerms = luckPerms;
        this.api = api;
        this.throwLog = new ThrowLog(logger);
        this.errors = errors;
    }

    static PermissionProvider create(Plugin plugin, Logger logger, ErrorReports errors) {
        ApiLink.staticMethod(LuckPermsProvider.class, "get", LuckPerms.class);
        ApiLink.instanceMethod(LuckPerms.class, "getUserManager", UserManager.class);
        ApiLink.instanceMethod(LuckPerms.class, "getQueryOptionsRegistry", QueryOptionsRegistry.class);
        ApiLink.instanceMethod(LuckPerms.class, "getNodeBuilderRegistry", NodeBuilderRegistry.class);
        ApiLink.instanceMethod(UserManager.class, "getUser", User.class, UUID.class);
        ApiLink.instanceMethod(UserManager.class, "loadUser", CompletableFuture.class, UUID.class);
        ApiLink.instanceMethod(UserManager.class, "saveUser", CompletableFuture.class, User.class);
        ApiLink.instanceMethod(User.class, "getPrimaryGroup", String.class);
        ApiLink.instanceMethod(User.class, "getInheritedGroups", Collection.class, QueryOptions.class);
        ApiLink.instanceMethod(User.class, "data", NodeMap.class);
        ApiLink.instanceMethod(Group.class, "getName", String.class);
        ApiLink.instanceMethod(QueryOptionsRegistry.class, "defaultNonContextualOptions", QueryOptions.class);
        ApiLink.instanceMethod(NodeMap.class, "add", DataMutateResult.class, Node.class);
        ApiLink.instanceMethod(NodeMap.class, "add", DataMutateResult.WithMergedNode.class, Node.class,
            TemporaryNodeMergeStrategy.class);
        ApiLink.instanceMethod(NodeMap.class, "remove", DataMutateResult.class, Node.class);
        ApiLink.instanceMethod(NodeMap.class, "clear", void.class, Predicate.class);
        ApiLink.instanceMethod(NodeMap.class, "toCollection", Collection.class);
        ApiLink.instanceMethod(DataMutateResult.class, "wasSuccessful", boolean.class);
        ApiLink.instanceMethod(DataMutateResult.WithMergedNode.class, "getResult", DataMutateResult.class);
        ApiLink.instanceMethod(NodeBuilderRegistry.class, "forInheritance", InheritanceNode.Builder.class);
        ApiLink.instanceMethod(NodeBuilderRegistry.class, "forPermission", PermissionNode.Builder.class);
        ApiLink.instanceMethod(InheritanceNode.Builder.class, "group", InheritanceNode.Builder.class, String.class);
        ApiLink.instanceMethod(InheritanceNode.Builder.class, "expiry", NodeBuilder.class, long.class,
            TimeUnit.class);
        ApiLink.instanceMethod(InheritanceNode.Builder.class, "build", ScopedNode.class);
        ApiLink.instanceMethod(PermissionNode.Builder.class, "permission", PermissionNode.Builder.class,
            String.class);
        ApiLink.instanceMethod(PermissionNode.Builder.class, "value", NodeBuilder.class, boolean.class);
        ApiLink.instanceMethod(PermissionNode.Builder.class, "build", ScopedNode.class);
        ApiLink.instanceMethod(PermissionNode.class, "getPermission", String.class);
        return new LuckPermsPermissions(plugin, LuckPermsProvider::get, logger, errors);
    }

    @Override
    public boolean available() {
        try {
            return api() != null;
        } catch (Exception | LinkageError ex) {
            fail("tell whether it is loaded", ex);
            return false;
        }
    }

    @Override
    public @Nullable String primaryGroup(UUID player) {
        Objects.requireNonNull(player, "player");
        try {
            LuckPerms luckPerms = api();
            User user = luckPerms == null ? null : luckPerms.getUserManager().getUser(player);
            return user != null ? user.getPrimaryGroup() : null;
        } catch (Exception | LinkageError ex) {
            fail("read a primary group", ex);
            return null;
        }
    }

    @Override
    public List<String> groups(UUID player) {
        Objects.requireNonNull(player, "player");
        try {
            LuckPerms luckPerms = api();
            User user = luckPerms == null ? null : luckPerms.getUserManager().getUser(player);
            if (user == null) {
                return List.of();
            }
            return user.getInheritedGroups(luckPerms.getQueryOptionsRegistry().defaultNonContextualOptions())
                .stream()
                .map(group -> group.getName())
                .toList();
        } catch (Exception | LinkageError ex) {
            fail("read the groups of a player", ex);
            return List.of();
        }
    }

    @Override
    public boolean addGroup(UUID player, String group) {
        Objects.requireNonNull(group, "group");
        return mutate(player, "add a player to a group", (luckPerms, user) -> user.data()
            .add(luckPerms.getNodeBuilderRegistry().forInheritance().group(group).build())
            .wasSuccessful());
    }

    @Override
    public boolean addTempGroup(UUID player, String group, Duration duration) {
        Objects.requireNonNull(group, "group");
        if (duration.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("a temporary group lasts at least one second, not " + duration);
        }
        try {
            Instant.now().plus(duration);
        } catch (DateTimeException | ArithmeticException tooLong) {
            throw new IllegalArgumentException("a temporary group ends before Instant.MAX, not " + duration
                + " from now", tooLong);
        }
        return mutate(player, "add a player to a group for a time", (luckPerms, user) -> user.data()
            .add(luckPerms.getNodeBuilderRegistry().forInheritance().group(group)
                .expiry(duration.toSeconds(), TimeUnit.SECONDS).build(),
                TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER)
            .getResult()
            .wasSuccessful());
    }

    @Override
    public boolean removeGroup(UUID player, String group) {
        Objects.requireNonNull(group, "group");
        return mutate(player, "remove a player from a group", (luckPerms, user) -> user.data()
            .remove(luckPerms.getNodeBuilderRegistry().forInheritance().group(group).build())
            .wasSuccessful());
    }

    @Override
    public boolean setPermission(UUID player, String node, boolean value) {
        Objects.requireNonNull(node, "node");
        return mutate(player, "set a permission", (luckPerms, user) -> user.data()
            .add(luckPerms.getNodeBuilderRegistry().forPermission().permission(node).value(value).build())
            .wasSuccessful());
    }

    @Override
    public boolean unsetPermission(UUID player, String node) {
        Objects.requireNonNull(node, "node");
        // Matches the node by name, whatever its stored value or contexts, in the saved data only: transient nodes are
        // neither looked at nor cleared.
        return mutate(player, "unset a permission", (luckPerms, user) -> {
            Predicate<Node> matches = candidate ->
                candidate instanceof PermissionNode permission && permission.getPermission().equals(node);
            boolean exists = user.data().toCollection().stream().anyMatch(matches);
            if (!exists) {
                return false;
            }
            user.data().clear(matches);
            return true;
        });
    }

    // Null when LuckPerms is disabled or has not finished loading.
    private @Nullable LuckPerms api() {
        if (!luckPerms.isEnabled()) {
            return null;
        }
        try {
            return api.get();
        } catch (IllegalStateException notLoaded) {
            return null;
        }
    }

    private boolean mutate(UUID player, String what, BiFunction<LuckPerms, User, Boolean> action) {
        Objects.requireNonNull(player, "player");
        try {
            LuckPerms luckPerms = api();
            if (luckPerms == null) {
                return false;
            }
            UserManager manager = luckPerms.getUserManager();
            User user = manager.getUser(player);
            if (user == null) {
                user = manager.loadUser(player).join();
            }
            boolean changed = action.apply(luckPerms, user);
            if (changed) {
                manager.saveUser(user).join();
            }
            return changed;
        } catch (Exception | LinkageError ex) {
            fail(what, ex);
            return false;
        }
    }

    // Logged at most once a minute for each kind of call, and reported every time.
    private void fail(String what, Throwable failure) {
        throwLog.warn(what, "LuckPerms could not " + what + " (" + failure + ").", failure);
        errors.report(SOURCE, failure);
    }
}
