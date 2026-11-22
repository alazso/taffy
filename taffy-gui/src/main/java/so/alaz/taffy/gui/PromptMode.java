package so.alaz.taffy.gui;

import org.jetbrains.annotations.ApiStatus;

/**
 * How a menu asks a player for text. A later release may add constants, so a {@code switch} over the modes has a
 * {@code default} branch.
 */
public enum PromptMode {
    /** The next chat line. */
    CHAT,
    /** The name field of an anvil. */
    ANVIL,
    /**
     * The text field of a Paper dialog with a submit and a cancel button. The dialog's title is the question. The
     * field starts with the initial text of the request, cut to {@link Prompts#MAX_LENGTH} characters, and takes that
     * many. The cancel button cancels the ask, and so does closing the dialog with the escape key, which the client
     * reports as that button. A refused answer shows the dialog again on the next tick with the
     * {@link Prompts#REFUSED} text above the field and the refused text in it, and sends nothing to chat. While an
     * {@link Answer.Later} runs no dialog shows. Bedrock players answer in chat.
     *
     * <p>Paper marks its Dialog API as experimental and may change it, so this mode is marked experimental too. On a
     * server without that API, building the prompts logs one warning and every ask is asked in chat. When the API of
     * the server cannot be linked, as after Paper changed it, the first ask logs one warning and that ask and every
     * later ask of those prompts are asked in chat.
     */
    @ApiStatus.Experimental
    DIALOG
}
