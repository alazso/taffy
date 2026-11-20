package so.alaz.taffy.platform;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import so.alaz.taffy.test.JvmDefaults;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

final class SkullBuilderTest {

    private static final String TEXTURE = "eyJ0ZXh0dXJlcyI6e319";
    private static final Executor SAME_THREAD = Runnable::run;

    private record Skin(UUID profileId, @Nullable String texture) {
    }

    private final Map<ItemStack, List<?>> constructorArguments = new IdentityHashMap<>();
    private final Map<PlayerProfile, Skin> profileSkins = new IdentityHashMap<>();
    private final Map<SkullMeta, Skin> metaSkins = new IdentityHashMap<>();
    private final Map<ItemStack, Skin> headSkins = new IdentityHashMap<>();
    private final UUID playerId = UUID.randomUUID();
    // System.nanoTime may return any value and overflow: every test that moves the clock crosses Long.MAX_VALUE.
    private final AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - Duration.ofSeconds(1).toNanos());
    private MockedConstruction<ItemStack> stacks;
    private MockedStatic<Bukkit> bukkit;

    // Every new ItemStack on this thread is a mock. As on a server, a meta takes the profile's state when the
    // profile is set, and a stack takes the meta's state when the meta is set.
    @BeforeEach
    void server() {
        stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            constructorArguments.put(stack, context.arguments());
            SkullMeta meta = mock(SkullMeta.class);
            doAnswer(call -> {
                metaSkins.put(meta, profileSkins.get(call.<PlayerProfile>getArgument(0)));
                return null;
            }).when(meta).setPlayerProfile(any());
            when(stack.getItemMeta()).thenReturn(meta);
            when(stack.setItemMeta(any())).thenAnswer(call -> {
                headSkins.put(stack, metaSkins.get(call.<SkullMeta>getArgument(0)));
                return true;
            });
        });
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createProfile(any(UUID.class))).thenAnswer(call -> emptyProfile(call.getArgument(0)));
    }

    @AfterEach
    void close() {
        stacks.close();
        if (bukkit != null) {
            bukkit.close();
        }
    }

    private PlayerProfile emptyProfile(UUID id) {
        PlayerProfile profile = mock(PlayerProfile.class);
        profileSkins.put(profile, new Skin(id, null));
        doAnswer(call -> {
            ProfileProperty property = call.getArgument(0);
            if (property.getName().equals("textures")) {
                profileSkins.put(profile, new Skin(id, property.getValue()));
            }
            return null;
        }).when(profile).setProperty(any());
        return profile;
    }

    // A profile that carries the texture only once complete(true) has run, and none at all for a null texture.
    private static PlayerProfile resolvable(@Nullable String texture) {
        PlayerProfile profile = mock(PlayerProfile.class);
        AtomicBoolean completed = new AtomicBoolean();
        when(profile.complete(true)).thenAnswer(call -> {
            completed.set(true);
            return texture != null;
        });
        when(profile.getProperties()).thenAnswer(call -> completed.get() && texture != null
            ? Set.of(new ProfileProperty("textures", texture))
            : Set.of());
        return profile;
    }

    // Each lookup of the name gets the next profile, and the last one from then on.
    private void nameResolvesTo(String name, PlayerProfile first, PlayerProfile... next) {
        bukkit.<PlayerProfile>when(() -> Bukkit.createProfile(name)).thenReturn(first, next);
    }

    private void uuidResolvesTo(UUID id, PlayerProfile first, PlayerProfile... next) {
        bukkit.<PlayerProfile>when(() -> Bukkit.createProfile(id)).thenReturn(first, next);
    }

    private SkullBuilder clockedSkulls() {
        return new SkullBuilder(SAME_THREAD, nanos::get);
    }

    private void advance(Duration time) {
        nanos.addAndGet(time.toNanos());
    }

    private static Skin skinOf(String texture) {
        return new Skin(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), texture);
    }

    private void assertTextured(ItemStack head, String texture) {
        assertThat(constructorArguments.get(head)).isEqualTo(List.of(Material.PLAYER_HEAD));
        assertThat(headSkins.get(head)).isEqualTo(skinOf(texture));
    }

    private void assertBlank(ItemStack head) {
        assertThat(constructorArguments.get(head)).isEqualTo(List.of(Material.PLAYER_HEAD));
        assertThat(headSkins).doesNotContainKey(head);
    }

    @Test
    void fromTextureSetsTheTextureOnAProfileWhoseIdComesFromTheTexture() {
        ItemStack head = SkullBuilder.fromTexture(TEXTURE);

        assertThat(stacks.constructed()).containsExactly(head);
        assertThat(constructorArguments.get(head)).isEqualTo(List.of(Material.PLAYER_HEAD));
        assertThat(headSkins.get(head)).isEqualTo(
            new Skin(UUID.nameUUIDFromBytes(TEXTURE.getBytes(StandardCharsets.UTF_8)), TEXTURE));
    }

    @Test
    void equalTexturesShareAProfileIdAndDifferentTexturesDoNot() {
        Skin first = headSkins.get(SkullBuilder.fromTexture(TEXTURE));
        Skin same = headSkins.get(SkullBuilder.fromTexture(TEXTURE));
        Skin other = headSkins.get(SkullBuilder.fromTexture(TEXTURE + "x"));

        assertThat(same).isEqualTo(first);
        assertThat(other.profileId()).isNotEqualTo(first.profileId());
    }

    @Test
    void fromUrlWrapsTheUrlInTheTexturesJsonAsBase64() {
        ItemStack head = SkullBuilder.fromUrl("http://textures.minecraft.net/texture/abc123");

        String texture = headSkins.get(head).texture();
        assertThat(new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8))
            .isEqualTo("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\"}}}");
        assertTextured(head, texture);
    }

    @Test
    void fromUrlRejectsADoubleQuote() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SkullBuilder.fromUrl("http://textures.minecraft.net/texture/a\"b"))
            .withMessageContaining("double quote");

        assertThat(stacks.constructed()).isEmpty();
    }

    @Test
    void fromUrlRejectsABackslash() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SkullBuilder.fromUrl("http://textures.minecraft.net/texture/a\\b"))
            .withMessageContaining("backslash");

        assertThat(stacks.constructed()).isEmpty();
    }

    @Test
    void textureValueIsTheValueOfTheTexturesProperty() {
        PlayerProfile profile = mock(PlayerProfile.class);
        Set<ProfileProperty> properties = new LinkedHashSet<>();
        properties.add(new ProfileProperty("cape", "other"));
        properties.add(new ProfileProperty("textures", TEXTURE));
        when(profile.getProperties()).thenReturn(properties);

        assertThat(SkullBuilder.textureValue(profile)).isEqualTo(TEXTURE);
    }

    @Test
    void textureValueIsNullWithoutATexturesProperty() {
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.getProperties()).thenReturn(Set.of(new ProfileProperty("cape", "other")));

        assertThat(SkullBuilder.textureValue(profile)).isNull();
    }

    @Test
    void fromNameGivesAHeadWithTheSkinTheNameResolvesTo() {
        nameResolvesTo("Notch", resolvable(TEXTURE));

        CompletableFuture<ItemStack> head = new SkullBuilder(SAME_THREAD).fromName("Notch");

        assertThat(head).isCompleted();
        assertTextured(head.join(), TEXTURE);
    }

    @Test
    void fromUuidGivesAHeadWithTheSkinTheUuidResolvesTo() {
        uuidResolvesTo(playerId, resolvable(TEXTURE));

        CompletableFuture<ItemStack> head = new SkullBuilder(SAME_THREAD).fromUuid(playerId);

        assertThat(head).isCompleted();
        assertTextured(head.join(), TEXTURE);
    }

    @Test
    void aNameWithoutASkinGivesABlankHeadAndIsLookedUpAgainAfterTwoMinutes() {
        nameResolvesTo("Notch", resolvable(null), resolvable(TEXTURE));
        SkullBuilder skulls = clockedSkulls();

        assertBlank(skulls.fromName("Notch").join());
        advance(SkullBuilder.FAILED_TTL.minusNanos(1));
        assertBlank(skulls.fromName("Notch").join());
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(1));

        advance(Duration.ofNanos(1));
        assertTextured(skulls.fromName("Notch").join(), TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(2));
    }

    @Test
    void aNameLookupThatThrowsGivesABlankHeadAndIsRetriedAfterTwoMinutes() {
        PlayerProfile resolved = resolvable(TEXTURE);
        bukkit.when(() -> Bukkit.createProfile("Notch"))
            .thenThrow(new IllegalStateException("no route to host"))
            .thenReturn(resolved);
        SkullBuilder skulls = clockedSkulls();

        CompletableFuture<ItemStack> failed = skulls.fromName("Notch");
        assertThat(failed).isCompleted();
        assertBlank(failed.join());

        advance(SkullBuilder.FAILED_TTL.minusNanos(1));
        assertBlank(skulls.fromName("Notch").join());
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(1));

        advance(Duration.ofNanos(1));
        assertTextured(skulls.fromName("Notch").join(), TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(2));
    }

    @Test
    void aUuidLookupThatThrowsGivesABlankHeadAndIsRetriedAfterTwoMinutes() {
        PlayerProfile unreachable = mock(PlayerProfile.class);
        when(unreachable.complete(true)).thenThrow(new IllegalStateException("no route to host"));
        uuidResolvesTo(playerId, unreachable, resolvable(TEXTURE));
        SkullBuilder skulls = clockedSkulls();

        CompletableFuture<ItemStack> failed = skulls.fromUuid(playerId);
        assertThat(failed).isCompleted();
        assertBlank(failed.join());

        advance(SkullBuilder.FAILED_TTL.minusNanos(1));
        assertBlank(skulls.fromUuid(playerId).join());
        bukkit.verify(() -> Bukkit.createProfile(playerId), times(1));

        advance(Duration.ofNanos(1));
        assertTextured(skulls.fromUuid(playerId).join(), TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile(playerId), times(2));
    }

    @Test
    void aLookupThatThrowsAnErrorCompletesExceptionallyAndIsRetriedAfterTwoMinutes() {
        PlayerProfile broken = mock(PlayerProfile.class);
        when(broken.complete(true)).thenThrow(new NoClassDefFoundError("com/mojang/authlib/GameProfile"));
        uuidResolvesTo(playerId, broken, resolvable(TEXTURE));
        SkullBuilder skulls = clockedSkulls();

        CompletableFuture<ItemStack> failed = skulls.fromUuid(playerId);

        assertThat(failed).isCompletedExceptionally();
        assertThatThrownBy(failed::join).hasCauseInstanceOf(NoClassDefFoundError.class);

        advance(SkullBuilder.FAILED_TTL.minusNanos(1));
        assertThatThrownBy(skulls.fromUuid(playerId)::join).hasCauseInstanceOf(NoClassDefFoundError.class);
        assertThat(stacks.constructed()).isEmpty();
        bukkit.verify(() -> Bukkit.createProfile(playerId), times(1));

        advance(Duration.ofNanos(1));
        assertTextured(skulls.fromUuid(playerId).join(), TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile(playerId), times(2));
    }

    @Test
    void aResolvedNameIsLookedUpOnceAndEveryCallGetsItsOwnHead() {
        nameResolvesTo("Notch", resolvable(TEXTURE));
        SkullBuilder skulls = new SkullBuilder(SAME_THREAD);

        ItemStack first = skulls.fromName("Notch").join();
        ItemStack second = skulls.fromName("Notch").join();

        assertThat(second).isNotSameAs(first);
        assertTextured(first, TEXTURE);
        assertTextured(second, TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(1));
    }

    @Test
    void aResolvedUuidIsLookedUpOnce() {
        uuidResolvesTo(playerId, resolvable(TEXTURE));
        SkullBuilder skulls = new SkullBuilder(SAME_THREAD);

        skulls.fromUuid(playerId).join();
        assertTextured(skulls.fromUuid(playerId).join(), TEXTURE);

        bukkit.verify(() -> Bukkit.createProfile(playerId), times(1));
    }

    @Test
    void aResolvedTextureIsLookedUpAgainAfterSixtyMinutes() {
        String changed = TEXTURE + "x";
        nameResolvesTo("Notch", resolvable(TEXTURE), resolvable(changed));
        SkullBuilder skulls = clockedSkulls();

        assertTextured(skulls.fromName("Notch").join(), TEXTURE);
        advance(SkullBuilder.RESOLVED_TTL.minusNanos(1));
        assertTextured(skulls.fromName("Notch").join(), TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(1));

        advance(Duration.ofNanos(1));
        assertTextured(skulls.fromName("Notch").join(), changed);
        assertTextured(skulls.fromName("Notch").join(), changed);
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(2));
    }

    @Test
    void theSixtyMinutesAreCountedFromTheMomentTheLookupCompleted() {
        uuidResolvesTo(playerId, resolvable(TEXTURE), resolvable(TEXTURE));
        List<Runnable> submitted = new ArrayList<>();
        SkullBuilder skulls = new SkullBuilder(submitted::add, nanos::get);

        CompletableFuture<ItemStack> head = skulls.fromUuid(playerId);
        advance(Duration.ofMinutes(10));
        submitted.getFirst().run();
        assertTextured(head.join(), TEXTURE);

        advance(SkullBuilder.RESOLVED_TTL.minusNanos(1));
        assertThat(skulls.fromUuid(playerId)).isCompleted();
        assertThat(submitted).hasSize(1);

        advance(Duration.ofNanos(1));
        assertThat(skulls.fromUuid(playerId)).isNotDone();
        assertThat(submitted).hasSize(2);
    }

    @Test
    void aLookupThatHasNotCompletedIsSharedHoweverLongItTakes() {
        nameResolvesTo("Notch", resolvable(TEXTURE));
        List<Runnable> submitted = new ArrayList<>();
        SkullBuilder skulls = new SkullBuilder(submitted::add, nanos::get);

        skulls.fromName("Notch");
        advance(SkullBuilder.RESOLVED_TTL.multipliedBy(2));
        skulls.fromName("Notch");

        assertThat(submitted).hasSize(1);
    }

    @Test
    void theCacheHoldsNoMoreThanItsBoundAndDropsTheLookupThatStartedLongestAgo() {
        List<String> lookedUp = new ArrayList<>();
        bukkit.when(() -> Bukkit.createProfile(anyString())).thenAnswer(call -> {
            lookedUp.add(call.getArgument(0));
            return resolvable(TEXTURE);
        });
        SkullBuilder skulls = clockedSkulls();

        for (int player = 0; player < SkullBuilder.MAX_CACHED; player++) {
            skulls.fromName("player" + player).join();
        }
        assertThat(skulls.cached()).isEqualTo(SkullBuilder.MAX_CACHED);

        skulls.fromName("newcomer").join();
        assertThat(skulls.cached()).isEqualTo(SkullBuilder.MAX_CACHED);

        lookedUp.clear();
        skulls.fromName("player1").join();
        skulls.fromName("newcomer").join();
        assertThat(lookedUp).isEmpty();

        skulls.fromName("player0").join();
        assertThat(lookedUp).containsExactly("player0");
        assertThat(skulls.cached()).isEqualTo(SkullBuilder.MAX_CACHED);
    }

    @Test
    void aLookupRepeatedAfterItExpiredCountsAsTheNewestInTheCache() {
        List<String> lookedUp = new ArrayList<>();
        bukkit.when(() -> Bukkit.createProfile(anyString())).thenAnswer(call -> {
            lookedUp.add(call.getArgument(0));
            return resolvable(TEXTURE);
        });
        SkullBuilder skulls = clockedSkulls();

        skulls.fromName("first").join();
        skulls.fromName("second").join();
        advance(SkullBuilder.RESOLVED_TTL);
        skulls.fromName("first").join();
        for (int player = 2; player < SkullBuilder.MAX_CACHED + 1; player++) {
            skulls.fromName("player" + player).join();
        }

        lookedUp.clear();
        skulls.fromName("first").join();
        assertThat(lookedUp).isEmpty();
        skulls.fromName("second").join();
        assertThat(lookedUp).containsExactly("second");
    }

    @Test
    void aNameIsCachedWithoutRegardToLetterCase() {
        nameResolvesTo("Notch", resolvable(TEXTURE));
        SkullBuilder skulls = new SkullBuilder(SAME_THREAD);

        skulls.fromName("Notch").join();
        assertTextured(skulls.fromName("NOTCH").join(), TEXTURE);

        bukkit.verify(() -> Bukkit.createProfile("NOTCH"), never());
    }

    @Test
    void aNameIsCachedWithoutRegardToLetterCaseUnderATurkishDefaultLocale() throws Exception {
        nameResolvesTo("Miner", resolvable(TEXTURE));
        SkullBuilder skulls = new SkullBuilder(SAME_THREAD);

        JvmDefaults.withLocale(JvmDefaults.TURKISH, () -> {
            skulls.fromName("Miner").join();
            assertTextured(skulls.fromName("MINER").join(), TEXTURE);
        });

        bukkit.verify(() -> Bukkit.createProfile("MINER"), never());
    }

    @Test
    void eachBuilderKeepsItsOwnCache() {
        nameResolvesTo("Notch", resolvable(TEXTURE), resolvable(TEXTURE));

        new SkullBuilder(SAME_THREAD).fromName("Notch").join();
        new SkullBuilder(SAME_THREAD).fromName("Notch").join();

        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(2));
    }

    @Test
    void theLookupRunsOnTheSuppliedExecutorAndCallsMadeMeanwhileShareIt() {
        nameResolvesTo("Notch", resolvable(TEXTURE));
        List<Runnable> submitted = new ArrayList<>();
        SkullBuilder skulls = new SkullBuilder(submitted::add);

        CompletableFuture<ItemStack> first = skulls.fromName("Notch");
        CompletableFuture<ItemStack> second = skulls.fromName("Notch");

        assertThat(submitted).hasSize(1);
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        assertThat(stacks.constructed()).isEmpty();
        bukkit.verify(() -> Bukkit.createProfile("Notch"), never());

        submitted.getFirst().run();

        assertThat(second.join()).isNotSameAs(first.join());
        assertTextured(first.join(), TEXTURE);
        assertTextured(second.join(), TEXTURE);
        bukkit.verify(() -> Bukkit.createProfile("Notch"), times(1));
    }

    @Test
    void anExecutorThatRejectsGivesABlankHeadAndTheLookupIsTriedAgainLater() {
        nameResolvesTo("Notch", resolvable(TEXTURE));
        AtomicBoolean accepting = new AtomicBoolean();
        SkullBuilder skulls = new SkullBuilder(task -> {
            if (!accepting.get()) {
                throw new RejectedExecutionException("not accepting work");
            }
            task.run();
        });

        CompletableFuture<ItemStack> rejected = skulls.fromName("Notch");

        assertThat(rejected).isCompleted();
        assertBlank(rejected.join());
        bukkit.verify(() -> Bukkit.createProfile("Notch"), never());

        accepting.set(true);
        assertTextured(skulls.fromName("Notch").join(), TEXTURE);
    }

    @Test
    void anExecutorThatThrowsAnotherExceptionThrowsItAndTheNextCallStartsANewLookup() {
        nameResolvesTo("Notch", resolvable(TEXTURE));
        List<Runnable> submitted = new ArrayList<>();
        SkullBuilder skulls = new SkullBuilder(task -> {
            submitted.add(task);
            if (submitted.size() == 1) {
                throw new IllegalStateException("plugin is disabled");
            }
            task.run();
        });

        assertThatThrownBy(() -> skulls.fromName("Notch"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("plugin is disabled");
        assertThat(skulls.cached()).isZero();

        CompletableFuture<ItemStack> next = skulls.fromName("Notch");

        assertThat(submitted).hasSize(2);
        assertThat(next).isCompleted();
        assertTextured(next.join(), TEXTURE);
    }

    @Test
    void anExecutorThatRejectsGivesABlankHeadForAUuidToo() {
        SkullBuilder skulls = new SkullBuilder(task -> {
            throw new RejectedExecutionException("not accepting work");
        });

        CompletableFuture<ItemStack> rejected = skulls.fromUuid(playerId);

        assertThat(rejected).isCompleted();
        assertBlank(rejected.join());
        bukkit.verify(() -> Bukkit.createProfile(playerId), never());
    }
}
