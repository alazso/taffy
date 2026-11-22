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
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Prompt dialogs built with the Paper Dialog API: a confirmation dialog, whose second button the client presses when
 * the player closes the dialog with the escape key. The client closes the dialog when a button is pressed. Each
 * button works for one press, and for {@link Prompts#TIMEOUT} after the dialog was built. The buttons carry the
 * client's own text for {@code gui.done} and {@code gui.cancel}.
 */
@SuppressWarnings("UnstableApiUsage")
final class PaperDialogs implements Dialogs {

    /** The class whose presence tells that the server has the Dialog API. */
    static final String API = "io.papermc.paper.dialog.Dialog";
    /** The key of the text field in the response. */
    static final String FIELD = "answer";
    /** The width of the text field. */
    static final int FIELD_WIDTH = 300;

    private static final Component SUBMIT = Component.translatable("gui.done");
    private static final Component CANCEL = Component.translatable("gui.cancel");
    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder().uses(1)
        .lifetime(Prompts.TIMEOUT).build();

    private final Function<Consumer<RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder>>,
        DialogLike> dialogs;

    /** Creates the dialogs of the server. */
    PaperDialogs() {
        this(Dialog::create);
    }

    // Dialog reads the server's registry when it is initialised, which Dialog::create does only when it is called.
    PaperDialogs(Function<Consumer<RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder>>,
        DialogLike> dialogs) {
        this.dialogs = dialogs;
    }

    @Override
    public void show(Player player, Form form) {
        player.showDialog(dialogs.apply(factory -> factory.empty().base(base(form)).type(DialogType.confirmation(
            button(SUBMIT, (response, audience) -> form.submit().accept(text(response))),
            button(CANCEL, (response, audience) -> form.cancel().run())))));
    }

    @Override
    public void close(Player player) {
        player.closeDialog();
    }

    private static DialogBase base(Form form) {
        return DialogBase.builder(form.title())
            .canCloseWithEscape(true)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(form.note() == null ? List.of() : List.of(DialogBody.plainMessage(form.note())))
            .inputs(List.of(DialogInput.text(FIELD, form.title()).width(FIELD_WIDTH).labelVisible(false)
                .initial(form.initial()).maxLength(Prompts.MAX_LENGTH).build()))
            .build();
    }

    private static ActionButton button(Component label, DialogActionCallback pressed) {
        return ActionButton.builder(label).action(DialogAction.customClick(pressed, OPTIONS)).build();
    }

    // A response holds whatever the client sent, so the field may be missing.
    private static String text(DialogResponseView response) {
        String text = response.getText(FIELD);
        return text == null ? "" : text;
    }
}
