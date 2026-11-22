package so.alaz.taffy.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.ConfirmationType;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The Dialog API reads the server through its static factories, so each is mocked and the dialog itself is made by
// the function the test gives.
@SuppressWarnings("UnstableApiUsage")
final class PaperDialogsTest {

    private static final Component TITLE = Component.text("Price?");
    private static final Component SUBMIT = Component.translatable("gui.done");
    private static final Component CANCEL = Component.translatable("gui.cancel");

    private final Player player = mock(Player.class);
    private final DialogLike made = mock(DialogLike.class);
    private final DialogBase.Builder baseBuilder = mock(DialogBase.Builder.class, RETURNS_SELF);
    private final DialogBase base = mock(DialogBase.class);
    private final TextDialogInput.Builder inputBuilder = mock(TextDialogInput.Builder.class, RETURNS_SELF);
    private final TextDialogInput input = mock(TextDialogInput.class);
    private final PlainMessageDialogBody body = mock(PlainMessageDialogBody.class);
    private final ActionButton.Builder submitBuilder = mock(ActionButton.Builder.class, RETURNS_SELF);
    private final ActionButton.Builder cancelBuilder = mock(ActionButton.Builder.class, RETURNS_SELF);
    private final ActionButton submit = mock(ActionButton.class);
    private final ActionButton cancel = mock(ActionButton.class);
    private final DialogAction.CustomClickAction submitAction = mock(DialogAction.CustomClickAction.class);
    private final DialogAction.CustomClickAction cancelAction = mock(DialogAction.CustomClickAction.class);
    private final ConfirmationType type = mock(ConfirmationType.class);
    private final DialogRegistryEntry.Builder entry = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
    private final List<String> submitted = new ArrayList<>();
    private final AtomicInteger cancels = new AtomicInteger();
    private final ArgumentCaptor<DialogActionCallback> callbacks = ArgumentCaptor.forClass(DialogActionCallback.class);
    private final ArgumentCaptor<ClickCallback.Options> options = ArgumentCaptor.forClass(ClickCallback.Options.class);

    // Shows a dialog for the form through mocked factories and captures the callbacks of its two buttons, submit
    // first.
    @SuppressWarnings("unchecked")
    private void show(@Nullable Component note) {
        when(baseBuilder.build()).thenReturn(base);
        when(inputBuilder.build()).thenReturn(input);
        when(submitBuilder.build()).thenReturn(submit);
        when(cancelBuilder.build()).thenReturn(cancel);
        RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory = mock(RegistryBuilderFactory.class);
        when(factory.empty()).thenReturn(entry);
        Dialogs.Form form = new Dialogs.Form(TITLE, note, "10", submitted::add, cancels::incrementAndGet);
        try (MockedStatic<DialogBase> bases = mockStatic(DialogBase.class);
             MockedStatic<DialogInput> inputs = mockStatic(DialogInput.class);
             MockedStatic<DialogBody> bodies = mockStatic(DialogBody.class);
             MockedStatic<ActionButton> buttons = mockStatic(ActionButton.class);
             MockedStatic<DialogAction> actions = mockStatic(DialogAction.class);
             MockedStatic<DialogType> types = mockStatic(DialogType.class)) {
            bases.when(() -> DialogBase.builder(TITLE)).thenReturn(baseBuilder);
            inputs.when(() -> DialogInput.text(PaperDialogs.FIELD, TITLE)).thenReturn(inputBuilder);
            if (note != null) {
                bodies.when(() -> DialogBody.plainMessage(note)).thenReturn(body);
            }
            buttons.when(() -> ActionButton.builder(SUBMIT)).thenReturn(submitBuilder);
            buttons.when(() -> ActionButton.builder(CANCEL)).thenReturn(cancelBuilder);
            actions.when(() -> DialogAction.customClick(any(DialogActionCallback.class),
                any(ClickCallback.Options.class))).thenReturn(submitAction, cancelAction);
            types.when(() -> DialogType.confirmation(submit, cancel)).thenReturn(type);

            new PaperDialogs(build -> {
                build.accept(factory);
                return made;
            }).show(player, form);

            actions.verify(() -> DialogAction.customClick(callbacks.capture(), options.capture()), times(2));
            bodies.verify(() -> DialogBody.plainMessage(any(Component.class)), note == null ? never() : times(1));
        }
    }

    @Test
    void aDialogIsAConfirmationWithOneTextFieldThatClosesOnAButtonAndWithTheEscapeKey() {
        show(null);

        verify(player).showDialog(made);
        verify(entry).base(base);
        verify(entry).type(type);
        verify(baseBuilder).canCloseWithEscape(true);
        verify(baseBuilder).afterAction(DialogBase.DialogAfterAction.CLOSE);
        verify(baseBuilder).body(List.of());
        verify(baseBuilder).inputs(List.of(input));
        verify(inputBuilder).width(PaperDialogs.FIELD_WIDTH);
        verify(inputBuilder).labelVisible(false);
        verify(inputBuilder).initial("10");
        verify(inputBuilder).maxLength(Prompts.MAX_LENGTH);
        verify(submitBuilder).action(submitAction);
        verify(cancelBuilder).action(cancelAction);
    }

    @Test
    void aNoteIsThePlainMessageAboveTheField() {
        show(Component.text("Not a number."));

        verify(baseBuilder).body(List.of(body));
    }

    @Test
    void eachButtonWorksForOnePressWithinTheTimeAnAskWaits() {
        show(null);

        assertThat(options.getAllValues()).hasSize(2).allSatisfy(each -> {
            assertThat(each.uses()).isEqualTo(1);
            assertThat(each.lifetime()).isEqualTo(Prompts.TIMEOUT);
        });
    }

    @Test
    void theSubmitButtonGivesTheTextOfTheFieldAndAnEmptyTextWhenTheResponseHasNone() {
        show(null);
        DialogResponseView typed = mock(DialogResponseView.class);
        when(typed.getText(PaperDialogs.FIELD)).thenReturn("7");

        callbacks.getAllValues().getFirst().accept(typed, mock(Audience.class));
        callbacks.getAllValues().getFirst().accept(mock(DialogResponseView.class), mock(Audience.class));

        assertThat(submitted).containsExactly("7", "");
        assertThat(cancels).hasValue(0);
    }

    @Test
    void theCancelButtonRunsTheCancelStep() {
        show(null);

        callbacks.getAllValues().getLast().accept(mock(DialogResponseView.class), mock(Audience.class));

        assertThat(cancels).hasValue(1);
        assertThat(submitted).isEmpty();
    }

    @Test
    void closeTakesTheDialogAwayAndTheDialogsOfTheServerAreBuiltWithoutReadingIt() {
        new PaperDialogs().close(player);

        verify(player).closeDialog();
        assertThat(PaperDialogs.API).isEqualTo(Dialog.class.getName());
    }
}
