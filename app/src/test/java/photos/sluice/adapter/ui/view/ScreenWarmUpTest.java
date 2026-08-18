package photos.sluice.adapter.ui.view;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import javafx.scene.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// A screen introducing a control has no reason to think about the warm-up, and a control missing
// from it costs nothing that shows up in a build. It comes back as a screen that opens slowly,
// months later, with nothing pointing at the cause. So the list is read off what the screens
// actually construct rather than maintained by hand.
class ScreenWarmUpTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void everyNodeTypeTheScreensBuildIsWarmed() throws Exception {
        assertThat(warmedTypes()).containsExactlyInAnyOrderElementsOf(nodeTypesTheScreensBuild());
    }

    private static Set<Class<?>> warmedTypes() throws Exception {
        return WaitForAsyncUtils.asyncFx(() -> ScreenWarmUp.nodes().stream()
                        .map(Object::getClass)
                        .collect(Collectors.<Class<?>>toSet()))
                .get(10, TimeUnit.SECONDS);
    }

    // Constructor calls rather than dependencies, so a type a screen only names in a signature
    // stays out. Warming one of those would warm nothing: what is dear is building an instance.
    private static Set<Class<?>> nodeTypesTheScreensBuild() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("photos.sluice.adapter.ui.view").stream()
                .filter(screen -> !screen.getName().startsWith(ScreenWarmUp.class.getName()))
                .flatMap(screen -> screen.getConstructorCallsFromSelf().stream())
                .map(JavaAccess::getTargetOwner)
                .filter(built -> built.isAssignableTo(Node.class))
                .map(JavaClass::reflect)
                .collect(Collectors.toSet());
    }
}
