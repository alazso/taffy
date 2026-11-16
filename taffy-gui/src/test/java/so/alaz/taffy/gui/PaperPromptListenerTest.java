package so.alaz.taffy.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class PaperPromptListenerTest {

    private static final long TOKEN = 7;

    private final PreviewMarker marker = new PreviewMarker(new NamespacedKey("taffytest", "preview"));
    private final Prompts prompts = mock(Prompts.class);
    private final UUID id = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final AnvilView view = anvil();
    private final AnvilInput anvil = new AnvilInput(marker, (who, question) -> view, text -> mock(ItemStack.class));
    private PaperPromptListener listener;
    private @Nullable InventoryView open;

    private AnvilView anvil() {
        AnvilView anvilView = mock(AnvilView.class);
        AnvilInventory top = mock(AnvilInventory.class);
        when(anvilView.getTopInventory()).thenReturn(top);
        when(anvilView.getPlayer()).thenReturn(player);
        return anvilView;
    }

    @BeforeEach
    void askInTheAnvil() {
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getOpenInventory()).thenAnswer(call -> open);
        doAnswer(call -> open = call.getArgument(0)).when(player).openInventory(any(InventoryView.class));
        assertThat(anvil.open(player, Component.text("Price?"), "10", TOKEN)).isTrue();
        when(prompts.anvil()).thenReturn(anvil);
        listener = new PaperPromptListener(prompts);
        clearInvocations(prompts);
    }

    private static InventoryClickEvent click(InventoryView view, int rawSlot) {
        return new InventoryClickEvent(view, InventoryType.SlotType.RESULT, rawSlot, ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    }

    private static InventoryDragEvent drag(InventoryView view) {
        return new InventoryDragEvent(view, null, mock(ItemStack.class), false, Map.of(0, mock(ItemStack.class)));
    }

    @Test
    void eventsOfAViewThatIsNoPromptAnvilAreLeftAlone() {
        AnvilView other = anvil();
        InventoryClickEvent click = click(other, 2);
        InventoryDragEvent drag = drag(other);
        PrepareAnvilEvent prepare = new PrepareAnvilEvent(other, null);
        InventoryCloseEvent close = new InventoryCloseEvent(other, InventoryCloseEvent.Reason.PLAYER);

        listener.onAnvilClick(click);
        listener.recancelAnvilClick(click);
        listener.onAnvilDrag(drag);
        listener.recancelAnvilDrag(drag);
        listener.onAnvilPrepare(prepare);
        listener.onAnvilClose(close);

        assertThat(click.isCancelled()).isFalse();
        assertThat(drag.isCancelled()).isFalse();
        assertThat(prepare.getResult()).isNull();
        verify(other, never()).setRepairCost(anyInt());
        verifyNoInteractions(other.getTopInventory());
        verifyNoInteractions(prompts);
        assertThat(anvil.showing(id, TOKEN)).isTrue();
    }

    @Test
    void overPromptsWithoutAnAnvilEveryHandlerDoesNothing() {
        Prompts chatOnly = mock(Prompts.class);
        PaperPromptListener idle = new PaperPromptListener(chatOnly);
        InventoryClickEvent click = click(view, Prompts.ANVIL_RESULT_SLOT);
        InventoryDragEvent drag = drag(view);
        PrepareAnvilEvent prepare = new PrepareAnvilEvent(view, null);

        idle.onAnvilClick(click);
        idle.recancelAnvilClick(click);
        idle.onAnvilDrag(drag);
        idle.recancelAnvilDrag(drag);
        idle.onAnvilPrepare(prepare);
        idle.onAnvilClose(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLAYER));

        assertThat(click.isCancelled()).isFalse();
        assertThat(drag.isCancelled()).isFalse();
        assertThat(prepare.getResult()).isNull();
        verify(chatOnly).anvil();
        verifyNoMoreInteractions(chatOnly);
        verify(view.getTopInventory(), never()).clear();
        assertThat(anvil.showing(id, TOKEN)).isTrue();
    }

    @Test
    void aClickIsCancelledAndPassedOnWithTheTokenTheSlotAndTheNameField() {
        when(view.getRenameText()).thenReturn("42");

        InventoryClickEvent result = click(view, Prompts.ANVIL_RESULT_SLOT);
        listener.onAnvilClick(result);
        InventoryClickEvent input = click(view, 0);
        listener.onAnvilClick(input);

        assertThat(result.isCancelled()).isTrue();
        assertThat(input.isCancelled()).isTrue();
        verify(prompts).anvilClick(id, TOKEN, Prompts.ANVIL_RESULT_SLOT, "42");
        verify(prompts).anvilClick(id, TOKEN, 0, "42");
    }

    @Test
    void theLastHandlersCancelAgainAClickOrDragAnotherPluginUncancelled() {
        InventoryClickEvent click = click(view, 0);
        InventoryDragEvent drag = drag(view);

        listener.recancelAnvilClick(click);
        listener.onAnvilDrag(drag);
        assertThat(click.isCancelled()).isTrue();
        assertThat(drag.isCancelled()).isTrue();

        click.setCancelled(false);
        drag.setCancelled(false);
        listener.recancelAnvilClick(click);
        listener.recancelAnvilDrag(drag);

        assertThat(click.isCancelled()).isTrue();
        assertThat(drag.isCancelled()).isTrue();
        verifyNoInteractions(prompts);
    }

    @Test
    void theResultIsAMarkedPaperNamedAfterTheNameFieldAtNoCost() {
        ItemMeta meta = mock(ItemMeta.class);
        try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            assertThat(context.arguments()).isEqualTo(List.of(Material.PAPER));
            when(stack.editMeta(any())).thenAnswer(call -> {
                Consumer<ItemMeta> edit = call.getArgument(0);
                edit.accept(meta);
                return true;
            });
        })) {
            when(view.getRenameText()).thenReturn("Diamond", "", null);

            PrepareAnvilEvent typed = new PrepareAnvilEvent(view, null);
            listener.onAnvilPrepare(typed);
            listener.onAnvilPrepare(new PrepareAnvilEvent(view, null));
            listener.onAnvilPrepare(new PrepareAnvilEvent(view, null));

            assertThat(stacks.constructed()).hasSize(3);
            ItemStack result = stacks.constructed().getFirst();
            assertThat(typed.getResult()).isSameAs(result);
            verify(result).editPersistentDataContainer(any());
        }

        verify(meta).itemName(Component.text("Diamond"));
        verify(meta, times(2)).itemName(Component.text(" "));
        verify(view, times(4)).setRepairCost(0);
        verifyNoInteractions(prompts);
    }

    @Test
    void aCloseClearsTheAnvilAndTellsThePromptsOnce() {
        InventoryCloseEvent close = new InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLAYER);

        listener.onAnvilClose(close);
        listener.onAnvilClose(close);

        verify(view.getTopInventory()).clear();
        verify(prompts).surfaceClosed(id, TOKEN, InventoryCloseEvent.Reason.PLAYER);
        assertThat(anvil.owns(view)).isFalse();
        assertThat(anvil.showing(id, TOKEN)).isFalse();

        InventoryClickEvent late = click(view, Prompts.ANVIL_RESULT_SLOT);
        listener.onAnvilClick(late);
        assertThat(late.isCancelled()).isFalse();
        verify(prompts, never()).anvilClick(any(UUID.class), anyLong(), anyInt(), any());
    }

    @Test
    void aCloseOverWhichAnotherInventoryOpenedPassesItsReason() {
        listener.onAnvilClose(new InventoryCloseEvent(view, InventoryCloseEvent.Reason.OPEN_NEW));

        verify(prompts).surfaceClosed(id, TOKEN, InventoryCloseEvent.Reason.OPEN_NEW);
    }
}
