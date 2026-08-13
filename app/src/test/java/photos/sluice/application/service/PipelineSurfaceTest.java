package photos.sluice.application.service;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

// Two things that are true of Pipeline's public surface as a whole, so neither is a test of any one
// method.
// Each rule field is discovered reflectively by the ArchUnit JUnit engine, never referenced from
// source, so the IDE's unused-symbol inspection flags them as a false positive.
@SuppressWarnings("unused")
@AnalyzeClasses(packages = "photos.sluice", importOptions = ImportOption.DoNotIncludeTests.class)
class PipelineSurfaceTest {

    private static final String ROOT_CHECK = "requireUsableRoots";

    // The app's API, in one place. Every driving adapter comes through here, and a command-line one
    // maps its verbs onto these roughly one for one. So a method arriving or leaving is a decision
    // about the product's surface rather than an implementation detail. This fails on any change, to
    // ask whether it was meant, and whether every adapter over the facade now has to account for it.
    private static final Set<String> ENTRY_POINTS = Set.of(
            "armWatchesForResumableRuns()",
            "sweepExpiredDisasterDrawers()",
            "sort(SortScope)",
            "commit(CommitScope)",
            "rescue(String)",
            "cull(CullScope)",
            "curate(SortScope)",
            "resume(Path, boolean)",
            "cullRuns()",
            "startWatching(Path)",
            "stopWatching(Path)",
            "stopAllWatching()",
            "troubleshoot(Path)",
            "purgeCompleted()",
            "discard(Path)");

    // The methods that must run whatever the roots say. Named rather than detected, because what
    // exempts one is what its caller is doing, which no property of the method reveals.
    // stopAllWatching's caller is a folder root that just moved. Refusing there would strand every
    // watcher on a folder nothing is working in, for the life of the process. It resolves no path
    // of its own, so the check has nothing here to protect.
    private static final Set<String> EXEMPT_FROM_THE_ROOT_CHECK = Set.of("stopAllWatching()");

    // The facade is where every driving adapter passes through, so it is where the folder-root check
    // belongs. A guard written into a screen would be walked past by a command line. This reads the
    // call rather than a list of names, so a new entry point is covered the moment it exists. Public
    // only: the package-private test seam resolves no path. The exemptions above are the one list
    // this cannot read, and adding to it is the decision this rule exists to force.
    @ArchTest
    static final ArchRule everyEntryPointChecksTheFolderRoots =
            methods().that().areDeclaredIn(Pipeline.class).and().arePublic()
                    .and(isNotExempt())
                    .should(callTheFolderRootCheck())
                    .as("every public Pipeline method must check the folder roots before resolving one");

    @ArchTest
    static final ArchRule theEntryPointsAreTheOnesPinnedHere =
            methods().that().areDeclaredIn(Pipeline.class).and().arePublic()
                    .should(bePinned())
                    .as("Pipeline's public surface must be the pinned one");

    // ArchUnit's own callMethod condition is class-scoped, which would only say the class calls the
    // check somewhere. The question here is per method, so this reads each method's own calls.
    private static ArchCondition<JavaMethod> callTheFolderRootCheck() {
        return new ArchCondition<>("call " + ROOT_CHECK) {
            @Override
            public void check(final JavaMethod method, final ConditionEvents events) {
                final boolean checks = method.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTarget().getName().equals(ROOT_CHECK));
                events.add(new SimpleConditionEvent(method, checks,
                        method.getFullName() + (checks ? " calls " : " does not call ") + ROOT_CHECK));
            }
        };
    }

    private static DescribedPredicate<JavaMethod> isNotExempt() {
        return DescribedPredicate.describe("not exempt from the folder-root check",
                method -> !EXEMPT_FROM_THE_ROOT_CHECK.contains(signature(method)));
    }

    private static ArchCondition<JavaMethod> bePinned() {
        return new ArchCondition<>("be one of the pinned entry points") {
            @Override
            public void check(final JavaMethod method, final ConditionEvents events) {
                final String signature = signature(method);
                events.add(new SimpleConditionEvent(method, ENTRY_POINTS.contains(signature),
                        signature + " is not pinned - decide whether it belongs on the facade, then pin it here"));
            }
        };
    }

    // Signature rather than bare name, so an overload of an already-pinned method counts as the new
    // entry point it is.
    private static String signature(final JavaMethod method) {
        return method.getRawParameterTypes().stream()
                .map(JavaClass::getSimpleName)
                .collect(Collectors.joining(", ", method.getName() + "(", ")"));
    }
}
