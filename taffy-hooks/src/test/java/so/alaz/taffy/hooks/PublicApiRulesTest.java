package so.alaz.taffy.hooks;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(ApiLink.class))
            .contains(ApiLink.class, HookState.class, HookStatus.class, Detected.class, Detection.class, Presence.class,
                PluginPresence.class, CustomItemProvider.class, CustomItemPlugin.class, CustomItemRef.class,
                ItemsAdderProvider.class, NexoProvider.class, OraxenProvider.class, HeadDatabaseProvider.class,
                CraftEngineProvider.class, MMOItemsProvider.class,
                CustomItems.class, CustomItems.Resolution.class, CustomItems.Built.class,
                CustomItems.Built.Reason.class, EconomyResult.class, EconomyResult.Status.class,
                EconomyReceipt.class, EconomyProvider.class, VaultOptions.class, VaultOptions.ThreadRule.class,
                VaultOptions.FundsCheck.class, Emulated.class, VaultEconomy.class, MissingEconomy.class,
                EconomyLookup.class, EconomyWatcher.class, VaultWatch.class, PlayerPointsEconomy.class,
                EconomyCurrency.class, DoubleMoney.class, VaultUnlockedEconomy.class, CoinsEngineLookup.class,
                CoinsEngineEconomy.class,
                PermissionProvider.class, LuckPermsPermissions.class,
                RegionProvider.class, WorldGuardRegions.class, BedrockProvider.class, PluginBedrock.class,
                FloodgateBedrock.class, GeyserBedrock.class, PlaceholderExpansionBuilder.class,
                PlaceholderRegistration.class, PapiExpansion.class, MiniExpansion.class, PlaceholderPrefixes.class,
                PlaceholderCache.class, NpcPlugin.class, NpcProvider.class, PluginNpcs.class, NpcBackend.class,
                NpcClicks.class, CitizensNpcBackend.class, FancyNpcsBackend.class, ZnpcsPlusBackend.class,
                Condition.class, ConditionResult.class, ConditionType.class, ConditionConfig.class,
                ConditionDenial.class, ConditionProblems.class, ConditionRegistry.class, BuiltinConditions.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(ApiLink.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(ApiLink.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(ApiLink.class)).isEmpty();
    }
}
