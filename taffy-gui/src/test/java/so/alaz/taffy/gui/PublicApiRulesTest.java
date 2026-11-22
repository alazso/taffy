package so.alaz.taffy.gui;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(Pager.class))
            .contains(Pager.class, Pager.Page.class, MenuLayout.class, ItemLayout.class, PagerLayout.class,
                MenuKind.class, Icon.class, Icon.Button.class, Icon.Filler.class, Icon.Real.class, Icon.Saved.class,
                StackPlan.class, StackPlan.Tooltip.class, MenuText.class, PreviewMarker.class, Canvas.class,
                PaperCanvas.class, IconStacks.class, Inventories.class, Inventories.Surface.class,
                PaperInventories.class, Route.class, MenuPrompts.class,
                MenuSettings.class, ClickGuard.class, Debounce.class, Cue.class, Feedback.class,
                ViewMemory.class, Menu.class, MenuSession.class, Menus.class, Menus.Parts.class, Frame.class,
                Frame.Spec.class, Frame.Entry.class, Button.class, Click.class, ClickHandler.class, Footer.class,
                MenuListener.class, PromptMode.class, PromptRequest.class, PromptEnd.class, Answer.class,
                Answer.Accept.class, Answer.Retry.class, Answer.Later.class, CancelLink.class, BedrockPlayers.class,
                Pending.class, Prompts.class, PaperCancelLink.class, AnvilInput.class,
                AnvilInput.Open.class, PaperPromptListener.class, TextRequest.class, Ask.class, Dialogs.class,
                Dialogs.Form.class, DialogPrompt.class, PaperDialogs.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void thePublicTopLevelTypesAreExactlyTheOnesAPluginNames() throws Exception {
        assertThat(ApiRules.classes(Pager.class).stream()
            .filter(type -> type.getEnclosingClass() == null && Modifier.isPublic(type.getModifiers()))
            .map(Class::getSimpleName))
            .containsExactlyInAnyOrder("Answer", "BedrockPlayers", "Click", "ClickGuard", "ClickHandler",
                "Cue", "Frame", "Icon", "ItemLayout", "Menu", "MenuKind", "MenuLayout", "MenuSession",
                "MenuSettings", "MenuText", "Menus", "Pager", "PagerLayout", "PromptEnd", "PromptMode",
                "PromptRequest", "Prompts", "Route", "TextRequest", "TextSource", "ViewMemory");
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(Pager.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(Pager.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(Pager.class)).isEmpty();
    }
}
