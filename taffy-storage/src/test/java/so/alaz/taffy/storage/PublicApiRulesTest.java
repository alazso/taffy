package so.alaz.taffy.storage;

import org.junit.jupiter.api.Test;
import so.alaz.taffy.test.ApiRules;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the module's design rules against every class and file of its compiled main output. */
class PublicApiRulesTest {

    @Test
    void scanFindsTheModuleClasses() throws Exception {
        assertThat(ApiRules.classes(StorageException.class))
            .contains(StorageException.class, Dialect.class, MoneyColumn.class, MoneyOutOfRangeException.class,
                Params.class, Row.class, Migration.class, Tx.class, Tx.Binder.class, Tx.Mapper.class,
                TxWork.class, CommitUnknownException.class, StorageSettings.class, StorageSettings.Server.class,
                StorageSettings.Pool.class, StorageSettings.SslMode.class, Storage.class)
            .allSatisfy(type -> assertThat(type.getPackageName()).startsWith(getClass().getPackageName()));
    }

    @Test
    void publicSignaturesNameOnlyAllowedTypes() throws Exception {
        assertThat(ApiRules.signatureViolations(StorageException.class)).isEmpty();
    }

    @Test
    void noStaticFieldHoldsAPluginLoggerExecutorOrThread() throws Exception {
        assertThat(ApiRules.staticHolderViolations(StorageException.class)).isEmpty();
    }

    @Test
    void mainOutputHoldsNoResourceFiles() throws Exception {
        assertThat(ApiRules.resourceFiles(StorageException.class)).isEmpty();
    }
}
