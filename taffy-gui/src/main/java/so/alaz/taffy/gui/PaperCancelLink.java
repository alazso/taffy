package so.alaz.taffy.gui;

import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * The cancel link of a chat prompt as a Paper chat click callback. Each link works for one click, and for
 * {@link Prompts#TIMEOUT} after it was made, as long as an ask waits.
 */
final class PaperCancelLink implements CancelLink {

    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder().uses(1)
        .lifetime(Prompts.TIMEOUT).build();

    /** Creates the cancel link maker. */
    PaperCancelLink() {
    }

    @Override
    public ClickEvent link(Runnable action) {
        return ClickEvent.callback(audience -> action.run(), OPTIONS);
    }
}
