package so.alaz.taffy.hooks;

import io.papermc.paper.plugin.configuration.PluginMeta;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.messaging.MessagingService;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilderRegistry;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.QueryOptionsRegistry;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class LuckPermsPermissionsTest {

    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final LuckPerms luckPerms = mock(LuckPerms.class);
    private final UserManager users = mock(UserManager.class);
    private final User user = mock(User.class);
    private final NodeMap data = mock(NodeMap.class);
    private final NodeBuilderRegistry builders = mock(NodeBuilderRegistry.class);
    private final InheritanceNode.Builder inheritance = mock(InheritanceNode.Builder.class);
    private final InheritanceNode vip = mock(InheritanceNode.class);
    private final PermissionNode.Builder permission = mock(PermissionNode.Builder.class);
    private final QueryOptionsRegistry queries = mock(QueryOptionsRegistry.class);
    private final QueryOptions nonContextual = mock(QueryOptions.class);
    private final List<String> reported = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final ErrorReports errors = new ErrorReports();
    private final CapturedLog logged = new CapturedLog();

    @BeforeEach
    void luckPerms() {
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(luckPerms.getUserManager()).thenReturn(users);
        when(luckPerms.getNodeBuilderRegistry()).thenReturn(builders);
        when(luckPerms.getQueryOptionsRegistry()).thenReturn(queries);
        when(queries.defaultNonContextualOptions()).thenReturn(nonContextual);
        when(builders.forInheritance()).thenReturn(inheritance);
        when(inheritance.group("vip")).thenReturn(inheritance);
        when(inheritance.build()).thenReturn(vip);
        when(builders.forPermission()).thenReturn(permission);
        when(permission.permission("fly.use")).thenReturn(permission);
        when(user.data()).thenReturn(data);
        when(users.getUser(PLAYER)).thenReturn(user);
        when(users.saveUser(user)).thenReturn(CompletableFuture.completedFuture(null));
        errors.install((source, error) -> {
            reported.add(source);
            failures.add(error);
        });
    }

    private LuckPermsPermissions permissions() {
        return new LuckPermsPermissions(plugin, () -> luckPerms, logged.logger, errors);
    }

    private static Group group(String name) {
        Group group = mock(Group.class);
        when(group.getName()).thenReturn(name);
        return group;
    }

    private static PermissionNode node(String name) {
        PermissionNode node = mock(PermissionNode.class);
        when(node.getPermission()).thenReturn(name);
        return node;
    }

    @Test
    void aDisabledLuckPermsIsUnavailableAndNeverAsked() {
        when(plugin.isEnabled()).thenReturn(false);
        @SuppressWarnings("unchecked")
        Supplier<LuckPerms> api = mock(Supplier.class);
        LuckPermsPermissions permissions = new LuckPermsPermissions(plugin, api, logged.logger, errors);

        assertThat(permissions.available()).isFalse();
        assertThat(permissions.primaryGroup(PLAYER)).isNull();
        assertThat(permissions.groups(PLAYER)).isEmpty();
        assertThat(permissions.addGroup(PLAYER, "vip")).isFalse();
        verifyNoInteractions(api);
        assertThat(reported).isEmpty();
    }

    @Test
    void aLuckPermsThatHasNotFinishedLoadingIsUnavailableWithoutAReport() {
        LuckPermsPermissions permissions =
            new LuckPermsPermissions(plugin, LuckPermsProvider::get, logged.logger, errors);

        assertThat(permissions.available()).isFalse();
        assertThat(permissions.primaryGroup(PLAYER)).isNull();
        assertThat(permissions.groups(PLAYER)).isEmpty();
        assertThat(permissions.setPermission(PLAYER, "fly.use", true)).isFalse();
        assertThat(reported).isEmpty();
    }

    @Test
    void aLoadedLuckPermsIsAvailable() {
        assertThat(permissions().available()).isTrue();
        assertThat(reported).isEmpty();
    }

    @Test
    void anApiThatFailsOtherwiseIsUnavailableAndReported() {
        IllegalArgumentException broken = new IllegalArgumentException("broken");
        LuckPermsPermissions permissions = new LuckPermsPermissions(plugin, () -> {
            throw broken;
        }, logged.logger, errors);

        assertThat(permissions.available()).isFalse();
        assertThat(reported).containsExactly("hook.luckperms");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly(
            "WARNING LuckPerms could not tell whether it is loaded (java.lang.IllegalArgumentException: broken).");
        assertThat(logged.records.getFirst().getThrown()).isSameAs(broken);
    }

    @Test
    void theFactoryBuildsAProviderOnTheRealApiEntryPoint() {
        PermissionProvider provider = LuckPermsPermissions.create(plugin, logged.logger, errors);

        assertThat(provider).isInstanceOf(LuckPermsPermissions.class);
        assertThat(provider.available()).isFalse();
        assertThat(reported).isEmpty();
    }

    @Test
    void theLoadedUsersPrimaryGroupIsRead() {
        when(user.getPrimaryGroup()).thenReturn("default");

        assertThat(permissions().primaryGroup(PLAYER)).isEqualTo("default");
    }

    @Test
    void aUserWhoseDataIsNotLoadedHasNoGroupsAndIsNotLoadedForARead() {
        when(users.getUser(PLAYER)).thenReturn(null);
        LuckPermsPermissions permissions = permissions();

        assertThat(permissions.primaryGroup(PLAYER)).isNull();
        assertThat(permissions.groups(PLAYER)).isEmpty();
        verify(users, never()).loadUser(any(UUID.class));
        assertThat(reported).isEmpty();
    }

    @Test
    void groupsAreTheNamesOfTheInheritedGroupsWithoutContext() {
        List<Group> inherited = List.of(group("vip"), group("default"));
        when(user.getInheritedGroups(nonContextual)).thenReturn(inherited);

        assertThat(permissions().groups(PLAYER)).containsExactly("vip", "default");
    }

    @Test
    void aFailingGroupReadGivesNoGroupsAndOneReport() {
        IllegalStateException broken = new IllegalStateException("storage gone");
        when(user.getInheritedGroups(nonContextual)).thenThrow(broken);

        assertThat(permissions().groups(PLAYER)).isEmpty();
        assertThat(reported).containsExactly("hook.luckperms");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly("WARNING LuckPerms could not read the groups of a player"
            + " (java.lang.IllegalStateException: storage gone).");
    }

    @Test
    void repeatedFailuresAreLoggedOnceAMinuteForEachKindOfCallAndEachIsReported() {
        IllegalStateException broken = new IllegalStateException("storage gone");
        when(user.getInheritedGroups(nonContextual)).thenThrow(broken);
        when(user.getPrimaryGroup()).thenThrow(broken);
        LuckPermsPermissions permissions = permissions();

        permissions.groups(PLAYER);
        permissions.groups(PLAYER);
        permissions.primaryGroup(PLAYER);

        assertThat(reported).containsExactly("hook.luckperms", "hook.luckperms", "hook.luckperms");
        assertThat(logged.lines()).containsExactly(
            "WARNING LuckPerms could not read the groups of a player (java.lang.IllegalStateException: storage gone).",
            "WARNING LuckPerms could not read a primary group (java.lang.IllegalStateException: storage gone).");
    }

    @Test
    void aFailingPrimaryGroupReadGivesNullAndOneReport() {
        when(user.getPrimaryGroup()).thenThrow(new NoSuchMethodError("getPrimaryGroup"));

        assertThat(permissions().primaryGroup(PLAYER)).isNull();
        assertThat(reported).containsExactly("hook.luckperms");
        assertThat(logged.lines()).containsExactly(
            "WARNING LuckPerms could not read a primary group (java.lang.NoSuchMethodError: getPrimaryGroup).");
    }

    @Test
    void anErrorOtherThanALinkageErrorIsRethrownAndNotReported() {
        when(user.getPrimaryGroup()).thenThrow(new OutOfMemoryError("heap"));

        assertThatThrownBy(() -> permissions().primaryGroup(PLAYER)).isInstanceOf(OutOfMemoryError.class);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void addingAGroupSavesWhenTheUserGainedIt() {
        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);

        assertThat(permissions().addGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
    }

    @Test
    void addingAGroupTheUserAlreadyHasDoesNotSave() {
        when(data.add(vip)).thenReturn(DataMutateResult.FAIL_ALREADY_HAS);

        assertThat(permissions().addGroup(PLAYER, "vip")).isFalse();
        verify(users, never()).saveUser(any());
        assertThat(reported).isEmpty();
    }

    private void temporary(long seconds, DataMutateResult result) {
        DataMutateResult.WithMergedNode merged = mock(DataMutateResult.WithMergedNode.class);
        when(merged.getResult()).thenReturn(result);
        when(inheritance.expiry(seconds, TimeUnit.SECONDS)).thenReturn(inheritance);
        when(data.add(vip, TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER)).thenReturn(merged);
    }

    @Test
    void aTemporaryGroupCarriesItsExpiryInWholeSeconds() {
        temporary(90L, DataMutateResult.SUCCESS);

        assertThat(permissions().addTempGroup(PLAYER, "vip", Duration.ofMillis(90_400))).isTrue();
        verify(inheritance).expiry(90L, TimeUnit.SECONDS);
        verify(data).add(vip, TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
        verify(data, never()).add(vip);
        verify(users).saveUser(user);
    }

    @Test
    void aTemporaryGroupOfExactlyOneSecondIsAdded() {
        temporary(1L, DataMutateResult.SUCCESS);

        assertThat(permissions().addTempGroup(PLAYER, "vip", Duration.ofSeconds(1))).isTrue();
        verify(inheritance).expiry(1L, TimeUnit.SECONDS);
        verify(users).saveUser(user);
    }

    @Test
    void aTemporaryGroupTheUserHoldsUntilALaterTimeIsLeftAsItIsAndNotSaved() {
        temporary(60L, DataMutateResult.FAIL_ALREADY_HAS);

        assertThat(permissions().addTempGroup(PLAYER, "vip", Duration.ofMinutes(1))).isFalse();
        verify(users, never()).saveUser(any());
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aTemporaryGroupEndingAfterTheLastInstantIsRefusedBeforeLuckPermsIsAsked() {
        LuckPermsPermissions permissions = permissions();
        Duration tooLong = Duration.ofSeconds(999_999_999_999_999_999L);

        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, "vip", tooLong))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a temporary group ends before Instant.MAX, not " + tooLong + " from now");
        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, "vip", Duration.ofSeconds(Long.MAX_VALUE)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(luckPerms);
    }

    @Test
    void aTemporaryGroupUnderOneSecondIsRefusedBeforeLuckPermsIsAsked() {
        LuckPermsPermissions permissions = permissions();

        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, "vip", Duration.ofMillis(999)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a temporary group lasts at least one second, not PT0.999S");
        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, "vip", Duration.ofSeconds(-5)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(luckPerms);
    }

    private static InheritanceNode membership(String group) {
        InheritanceNode node = mock(InheritanceNode.class);
        when(node.getGroupName()).thenReturn(group);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Predicate<? super Node> cleared() {
        ArgumentCaptor<Predicate<? super Node>> cleared = ArgumentCaptor.forClass(Predicate.class);
        verify(data).clear(cleared.capture());
        return cleared.getValue();
    }

    @SuppressWarnings("unchecked")
    private Predicate<? super Node> clearedIn(InOrder order) {
        ArgumentCaptor<Predicate<? super Node>> cleared = ArgumentCaptor.forClass(Predicate.class);
        order.verify(data).clear(cleared.capture());
        return cleared.getValue();
    }

    @Test
    void removingAGroupClearsAPermanentMembershipAndSaves() {
        InheritanceNode permanent = membership("vip");
        InheritanceNode other = membership("staff");
        InheritanceNode longer = membership("vip2");
        InheritanceNode prefixed = membership("svip");
        PermissionNode named = node("vip");
        when(data.toCollection()).thenReturn(List.of(other, longer, prefixed, named, permanent));

        assertThat(permissions().removeGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
        verify(data, never()).remove(any());

        Predicate<? super Node> test = cleared();
        assertThat(test.test(permanent)).isTrue();
        assertThat(test.test(other)).isFalse();
        assertThat(test.test(longer)).isFalse();
        assertThat(test.test(prefixed)).isFalse();
        assertThat(test.test(named)).isFalse();
    }

    @Test
    void removingAGroupClearsATemporaryMembership() {
        InheritanceNode temporary = membership("vip");
        when(temporary.hasExpiry()).thenReturn(true);
        when(data.toCollection()).thenReturn(List.of(temporary));

        assertThat(permissions().removeGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
        assertThat(cleared().test(temporary)).isTrue();
    }

    @Test
    void removingAGroupClearsAMembershipHeldInAContext() {
        InheritanceNode contextual = membership("vip");
        ImmutableContextSet survival = mock(ImmutableContextSet.class);
        when(contextual.getContexts()).thenReturn(survival);
        when(data.toCollection()).thenReturn(List.of(contextual));

        assertThat(permissions().removeGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
        assertThat(cleared().test(contextual)).isTrue();
    }

    @Test
    void removingAGroupMatchesItsNameWithoutRegardToCase() {
        InheritanceNode stored = membership("vip");
        when(data.toCollection()).thenReturn(List.of(stored));

        assertThat(permissions().removeGroup(PLAYER, "VIP")).isTrue();
        assertThat(cleared().test(stored)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void removingAGroupTheUserLacksChangesNothing() {
        InheritanceNode other = membership("staff");
        PermissionNode named = node("vip");
        when(data.toCollection()).thenReturn(List.of(other, named));

        assertThat(permissions().removeGroup(PLAYER, "vip")).isFalse();
        verify(data, never()).clear(any(Predicate.class));
        verify(users, never()).saveUser(any());
        assertThat(reported).isEmpty();
    }

    private static ImmutableContextSet contexts(boolean empty) {
        ImmutableContextSet contexts = mock(ImmutableContextSet.class);
        when(contexts.isEmpty()).thenReturn(empty);
        return contexts;
    }

    private static PermissionNode held(String name, boolean value) {
        PermissionNode node = node(name);
        ImmutableContextSet none = contexts(true);
        when(node.getValue()).thenReturn(value);
        when(node.getContexts()).thenReturn(none);
        return node;
    }

    private static PermissionNode heldInAContext(String name, boolean value) {
        PermissionNode node = node(name);
        ImmutableContextSet survival = contexts(false);
        when(node.getValue()).thenReturn(value);
        when(node.getContexts()).thenReturn(survival);
        return node;
    }

    // The node setPermission builds for fly.use at the value.
    private PermissionNode setting(boolean value) {
        PermissionNode built = node("fly.use");
        PermissionNode.Builder valued = mock(PermissionNode.Builder.class);
        when(permission.value(value)).thenReturn(valued);
        when(valued.build()).thenReturn(built);
        when(data.add(built)).thenReturn(DataMutateResult.SUCCESS);
        return built;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPermissionTheUserLacksIsAddedAndSaved() {
        PermissionNode granted = setting(true);
        PermissionNode other = held("chat.color", false);
        when(data.toCollection()).thenReturn(List.of(vip, other));

        assertThat(permissions().setPermission(PLAYER, "fly.use", true)).isTrue();
        verify(data).add(granted);
        verify(data, never()).clear(any(Predicate.class));
        verify(users).saveUser(user);
    }

    @Test
    void aGrantedPermissionIsSetDenied() {
        PermissionNode denied = setting(false);
        PermissionNode granted = held("fly.use", true);
        PermissionNode other = held("chat.color", true);
        when(data.toCollection()).thenReturn(List.of(vip, other, granted));

        assertThat(permissions().setPermission(PLAYER, "fly.use", false)).isTrue();

        InOrder order = inOrder(data, users);
        Predicate<? super Node> test = clearedIn(order);
        order.verify(data).add(denied);
        order.verify(users).saveUser(user);
        assertThat(test.test(granted)).isTrue();
        assertThat(test.test(other)).isFalse();
        assertThat(test.test(vip)).isFalse();
    }

    @Test
    void aDeniedPermissionIsSetGranted() {
        PermissionNode granted = setting(true);
        PermissionNode denied = held("fly.use", false);
        when(data.toCollection()).thenReturn(List.of(denied));

        assertThat(permissions().setPermission(PLAYER, "fly.use", true)).isTrue();

        InOrder order = inOrder(data, users);
        Predicate<? super Node> test = clearedIn(order);
        order.verify(data).add(granted);
        order.verify(users).saveUser(user);
        assertThat(test.test(denied)).isTrue();
    }

    @Test
    void aNewNodeRefusedOnceTheHeldOnesAreClearedIsReportedAndTheClearedDataIsSaved() {
        PermissionNode denied = setting(false);
        PermissionNode granted = held("fly.use", true);
        when(data.toCollection()).thenReturn(List.of(granted));
        when(data.add(denied)).thenReturn(DataMutateResult.FAIL_ALREADY_HAS);

        assertThat(permissions().setPermission(PLAYER, "fly.use", false)).isTrue();

        InOrder order = inOrder(data, users);
        clearedIn(order);
        order.verify(data).add(denied);
        order.verify(users).saveUser(user);
        assertThat(reported).containsExactly("hook.luckperms");
        assertThat(logged.lines()).anyMatch(line -> line.contains("LuckPerms could not set a permission")
            && line.contains("refused the new one"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPermissionAlreadyHeldAtTheValueChangesNothingAndIsNotSaved() {
        setting(true);
        setting(false);
        PermissionNode granted = held("fly.use", true);
        when(data.toCollection()).thenReturn(List.of(granted));
        LuckPermsPermissions permissions = permissions();

        assertThat(permissions.setPermission(PLAYER, "fly.use", true)).isFalse();

        PermissionNode denied = held("fly.use", false);
        when(data.toCollection()).thenReturn(List.of(denied));

        assertThat(permissions.setPermission(PLAYER, "fly.use", false)).isFalse();
        verify(data, never()).clear(any(Predicate.class));
        verify(data, never()).add(any());
        verify(users, never()).saveUser(any());
        assertThat(reported).isEmpty();
    }

    @Test
    void aTemporaryPermissionOfTheSameNameIsReplacedByThePermanentOne() {
        PermissionNode granted = setting(true);
        PermissionNode temporary = held("fly.use", true);
        when(temporary.hasExpiry()).thenReturn(true);
        when(data.toCollection()).thenReturn(List.of(temporary));

        assertThat(permissions().setPermission(PLAYER, "fly.use", true)).isTrue();

        InOrder order = inOrder(data, users);
        Predicate<? super Node> test = clearedIn(order);
        order.verify(data).add(granted);
        order.verify(users).saveUser(user);
        assertThat(test.test(temporary)).isTrue();
    }

    @Test
    void aTemporaryPermissionBesideThePermanentOneIsClearedWithIt() {
        PermissionNode granted = setting(true);
        PermissionNode permanent = held("fly.use", true);
        PermissionNode temporary = held("fly.use", false);
        when(temporary.hasExpiry()).thenReturn(true);
        when(data.toCollection()).thenReturn(List.of(permanent, temporary));

        assertThat(permissions().setPermission(PLAYER, "fly.use", true)).isTrue();

        InOrder order = inOrder(data, users);
        Predicate<? super Node> test = clearedIn(order);
        order.verify(data).add(granted);
        order.verify(users).saveUser(user);
        assertThat(test.test(permanent)).isTrue();
        assertThat(test.test(temporary)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPermissionHeldOnlyInAContextIsLeftAloneAndTheNodeIsAddedBesideIt() {
        PermissionNode denied = setting(false);
        PermissionNode contextual = heldInAContext("fly.use", true);
        when(data.toCollection()).thenReturn(List.of(contextual));

        assertThat(permissions().setPermission(PLAYER, "fly.use", false)).isTrue();
        verify(data).add(denied);
        verify(data, never()).clear(any(Predicate.class));
        verify(users).saveUser(user);
    }

    @Test
    void aPermissionHeldInAContextIsNotClearedWhenTheOneWithoutContextIsFlipped() {
        setting(false);
        PermissionNode granted = held("fly.use", true);
        PermissionNode contextual = heldInAContext("fly.use", true);
        when(data.toCollection()).thenReturn(List.of(contextual, granted));

        assertThat(permissions().setPermission(PLAYER, "fly.use", false)).isTrue();

        Predicate<? super Node> test = cleared();
        assertThat(test.test(granted)).isTrue();
        assertThat(test.test(contextual)).isFalse();
        verify(users).saveUser(user);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsettingAPermissionTheUserLacksChangesNothing() {
        PermissionNode other = node("chat.color");
        when(data.toCollection()).thenReturn(List.of(other, vip));

        assertThat(permissions().unsetPermission(PLAYER, "fly.use")).isFalse();
        verify(data, never()).clear(any(Predicate.class));
        verify(users, never()).saveUser(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsettingAPermissionClearsEveryNodeOfThatNameAndSaves() {
        PermissionNode granted = node("fly.use");
        PermissionNode other = node("chat.color");
        when(data.toCollection()).thenReturn(List.of(vip, other, granted));
        ArgumentCaptor<Predicate<? super Node>> cleared = ArgumentCaptor.forClass(Predicate.class);

        assertThat(permissions().unsetPermission(PLAYER, "fly.use")).isTrue();
        verify(data).clear(cleared.capture());
        verify(users).saveUser(user);

        Predicate<? super Node> test = cleared.getValue();
        assertThat(test.test(granted)).isTrue();
        assertThat(test.test(node("fly.use"))).isTrue();
        assertThat(test.test(other)).isFalse();
        assertThat(test.test(vip)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPermissionHeldOnlyForTheSessionIsNotClearedAndNothingIsSaved() {
        PermissionNode sessionOnly = node("fly.use");
        PermissionNode saved = node("chat.color");
        when(user.getNodes(NodeType.PERMISSION)).thenReturn(List.of(sessionOnly));
        when(data.toCollection()).thenReturn(List.of(saved));

        assertThat(permissions().unsetPermission(PLAYER, "fly.use")).isFalse();
        verify(data, never()).clear(any(Predicate.class));
        verify(users, never()).saveUser(any());
    }

    @Test
    void aNullArgumentThrowsAtTheCallAndIsNotReported() {
        LuckPermsPermissions permissions = permissions();

        assertThatThrownBy(() -> permissions.addGroup(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, null, Duration.ofMinutes(1)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.removeGroup(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.setPermission(PLAYER, null, true))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.unsetPermission(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.addGroup(null, "vip")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.primaryGroup(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.groups(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(luckPerms);
        assertThat(reported).isEmpty();
    }

    @Test
    void aWriteOnAServerThreadThrowsBeforeLuckPermsIsAsked() {
        when(server.isPrimaryThread()).thenReturn(true);
        LuckPermsPermissions permissions = permissions();
        String message = "a LuckPerms write blocks until the data is saved and runs off the server thread, not on "
            + Thread.currentThread().getName() + "; run it through Scheduler.async(...)";

        assertThatThrownBy(() -> permissions.addGroup(PLAYER, "vip"))
            .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, "vip", Duration.ofMinutes(1)))
            .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> permissions.removeGroup(PLAYER, "vip"))
            .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> permissions.setPermission(PLAYER, "fly.use", true))
            .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> permissions.unsetPermission(PLAYER, "fly.use"))
            .isInstanceOf(IllegalStateException.class).hasMessage(message);
        verifyNoInteractions(luckPerms);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aWriteRunInlineOnAServerThreadAsWhileTheOwnerDisablesThrowsAndLoadsAndSavesNothing() {
        when(server.isPrimaryThread()).thenReturn(true);
        LuckPermsPermissions permissions = permissions();
        Runnable revoke = () -> permissions.removeGroup(PLAYER, "vip");

        assertThatThrownBy(revoke::run).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Scheduler.async");
        verifyNoInteractions(users);
        verifyNoInteractions(luckPerms);
    }

    @Test
    void aWriteOnAServerThreadThrowsWhileLuckPermsIsDisabled() {
        when(server.isPrimaryThread()).thenReturn(true);
        when(plugin.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> permissions().addGroup(PLAYER, "vip")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anArgumentIsCheckedBeforeTheThread() {
        when(server.isPrimaryThread()).thenReturn(true);
        LuckPermsPermissions permissions = permissions();

        assertThatThrownBy(() -> permissions.addGroup(PLAYER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.addGroup(null, "vip")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> permissions.addTempGroup(PLAYER, "vip", Duration.ofMillis(999)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReadOnAServerThreadIsAnswered() {
        when(server.isPrimaryThread()).thenReturn(true);
        when(user.getPrimaryGroup()).thenReturn("default");
        List<Group> inherited = List.of(group("default"));
        when(user.getInheritedGroups(nonContextual)).thenReturn(inherited);
        LuckPermsPermissions permissions = permissions();

        assertThat(permissions.available()).isTrue();
        assertThat(permissions.primaryGroup(PLAYER)).isEqualTo("default");
        assertThat(permissions.groups(PLAYER)).containsExactly("default");
    }

    @Test
    void aWriteLoadsAUserWhoseDataIsNotCached() {
        User loaded = mock(User.class);
        NodeMap loadedData = mock(NodeMap.class);
        when(loaded.data()).thenReturn(loadedData);
        when(loadedData.add(vip)).thenReturn(DataMutateResult.SUCCESS);
        when(users.getUser(PLAYER)).thenReturn(null);
        when(users.loadUser(PLAYER)).thenReturn(CompletableFuture.completedFuture(loaded));
        when(users.saveUser(loaded)).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(permissions().addGroup(PLAYER, "vip")).isTrue();
        verify(users).loadUser(PLAYER);
        verify(users).saveUser(loaded);
    }

    @Test
    void aFailedLoadGivesFalseAndOneReport() {
        when(users.getUser(PLAYER)).thenReturn(null);
        when(users.loadUser(PLAYER)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));

        assertThat(permissions().addGroup(PLAYER, "vip")).isFalse();
        assertThat(reported).containsExactly("hook.luckperms");
        assertThat(failures.getFirst()).isInstanceOf(CompletionException.class)
            .hasRootCauseMessage("db down");
        assertThat(logged.lines()).containsExactly("WARNING LuckPerms could not add a player to a group"
            + " (java.util.concurrent.CompletionException: java.lang.IllegalStateException: db down).");
        verify(data, never()).add(any());
    }

    @Test
    void aFailedSaveGivesFalseAndOneReport() {
        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);
        when(users.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));

        assertThat(permissions().addGroup(PLAYER, "vip")).isFalse();
        assertThat(reported).containsExactly("hook.luckperms");
    }

    @Test
    void aSavedWriteIsPushedThroughTheMessagingServiceAfterTheSave() {
        MessagingService messaging = mock(MessagingService.class);
        when(luckPerms.getMessagingService()).thenReturn(Optional.of(messaging));
        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);

        assertThat(permissions().addGroup(PLAYER, "vip")).isTrue();

        InOrder order = inOrder(users, messaging);
        order.verify(users).saveUser(user);
        order.verify(messaging).pushUserUpdate(user);
        assertThat(reported).isEmpty();
    }

    @Test
    void aWriteIsSavedAndPushesNothingWithoutAMessagingService() {
        when(luckPerms.getMessagingService()).thenReturn(Optional.empty());
        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);

        assertThat(permissions().addGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
        assertThat(reported).isEmpty();
        assertThat(logged.lines()).isEmpty();
    }

    @Test
    void aFailingPushLeavesTheSavedWriteTrueAndIsReported() {
        MessagingService messaging = mock(MessagingService.class);
        IllegalStateException broken = new IllegalStateException("redis gone");
        doThrow(broken).when(messaging).pushUserUpdate(user);
        when(luckPerms.getMessagingService()).thenReturn(Optional.of(messaging));
        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);

        assertThat(permissions().addGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
        assertThat(reported).containsExactly("hook.luckperms");
        assertThat(failures).containsExactly(broken);
        assertThat(logged.lines()).containsExactly("WARNING LuckPerms could not push a saved change to the other"
            + " servers (java.lang.IllegalStateException: redis gone).");
    }

    @Test
    void aMessagingServiceThatCannotBeReadLeavesTheSavedWriteTrueAndIsReported() {
        when(luckPerms.getMessagingService()).thenThrow(new NoSuchMethodError("getMessagingService"));
        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);

        assertThat(permissions().addGroup(PLAYER, "vip")).isTrue();
        verify(users).saveUser(user);
        assertThat(reported).containsExactly("hook.luckperms");
    }

    @Test
    void aWriteThatChangedNothingOrWasNotSavedIsNotPushed() {
        MessagingService messaging = mock(MessagingService.class);
        when(luckPerms.getMessagingService()).thenReturn(Optional.of(messaging));
        when(data.add(vip)).thenReturn(DataMutateResult.FAIL_ALREADY_HAS);
        LuckPermsPermissions permissions = permissions();

        assertThat(permissions.addGroup(PLAYER, "vip")).isFalse();

        when(data.add(vip)).thenReturn(DataMutateResult.SUCCESS);
        when(users.saveUser(user)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));

        assertThat(permissions.addGroup(PLAYER, "vip")).isFalse();
        verifyNoInteractions(messaging);
    }

    private static PluginManager pluginManager(boolean installed) {
        PluginManager plugins = mock(PluginManager.class);
        if (installed) {
            Plugin luckPerms = mock(Plugin.class);
            PluginMeta meta = mock(PluginMeta.class);
            when(meta.getVersion()).thenReturn("5.5.17");
            when(luckPerms.getPluginMeta()).thenReturn(meta);
            when(luckPerms.isEnabled()).thenReturn(true);
            when(plugins.getPlugin("LuckPerms")).thenReturn(luckPerms);
            when(plugins.isPluginEnabled("LuckPerms")).thenReturn(true);
        }
        return plugins;
    }

    private static Plugin owner(PluginManager plugins, CapturedLog log) {
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(plugins);
        Plugin owner = mock(Plugin.class);
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        return owner;
    }

    @Test
    void detectingAnEnabledLuckPermsTurnsItOn() {
        CapturedLog log = new CapturedLog();

        Detected<PermissionProvider> detected = PermissionProvider.luckPerms(owner(pluginManager(true), log), errors,
            true);

        assertThat(detected.status()).isEqualTo(new HookStatus("LuckPerms", HookState.ON, "5.5.17", null));
        assertThat(detected.provider()).isInstanceOf(LuckPermsPermissions.class);
        assertThat(detected.provider().available()).isFalse();
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void anOnLuckPermsReadsNotEnabledWhileItIsDisabled() {
        PluginManager plugins = pluginManager(true);
        Plugin luckPerms = plugins.getPlugin("LuckPerms");
        Detected<PermissionProvider> detected = PermissionProvider.luckPerms(owner(plugins, new CapturedLog()),
            errors, true);

        when(luckPerms.isEnabled()).thenReturn(false);

        assertThat(detected.status()).isEqualTo(new HookStatus("LuckPerms", HookState.NOT_ENABLED, null, null));
        assertThat(detected.provider()).isInstanceOf(LuckPermsPermissions.class);
        assertThat(detected.provider().available()).isFalse();

        when(luckPerms.isEnabled()).thenReturn(true);
        assertThat(detected.status()).isEqualTo(new HookStatus("LuckPerms", HookState.ON, "5.5.17", null));
    }

    @Test
    void detectingWithoutLuckPermsLeavesItOff() {
        CapturedLog log = new CapturedLog();

        Detected<PermissionProvider> detected = PermissionProvider.luckPerms(owner(pluginManager(false), log), errors,
            true);

        assertThat(detected.status()).isEqualTo(new HookStatus("LuckPerms", HookState.NOT_INSTALLED, null, null));
        assertThat(detected.provider()).isNull();
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aSwitchedOffLuckPermsIsNotLookedAt() {
        CapturedLog log = new CapturedLog();
        PluginManager plugins = mock(PluginManager.class);

        Detected<PermissionProvider> detected = PermissionProvider.luckPerms(owner(plugins, log), errors, false);

        assertThat(detected.status()).isEqualTo(new HookStatus("LuckPerms", HookState.SWITCHED_OFF, null, null));
        assertThat(detected.status().describe()).isEqualTo("LuckPerms switched off");
        assertThat(detected.provider()).isNull();
        verify(plugins, never()).getPlugin(any());
        verify(plugins, never()).isPluginEnabled(any(String.class));
        assertThat(log.lines()).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void detectingRefusesAMissingOwnerOrErrorSink() {
        Plugin owner = owner(pluginManager(true), new CapturedLog());

        assertThatThrownBy(() -> PermissionProvider.luckPerms(null, errors, true))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> PermissionProvider.luckPerms(owner, null, true))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
    }
}
