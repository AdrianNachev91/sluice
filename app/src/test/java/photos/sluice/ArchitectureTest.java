package photos.sluice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Package visibility is flat, so the layering is only a convention until something checks it. These
 * rules turn a boundary violation into a build failure instead of a review-time catch, which is the
 * only mechanical guard on the architecture in a single-module project.
 */
// Each rule field is discovered reflectively by the ArchUnit JUnit engine, never referenced from
// source, so the IDE's unused-symbol inspection flags them as a false positive.
@SuppressWarnings("unused")
@AnalyzeClasses(packages = "photos.sluice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // The core owns the ports and models it needs; it must never reach outward to use cases, effect
    // implementations, or framework wiring. This is the arrow that keeps the domain testable in
    // isolation.
    @ArchTest
    static final ArchRule domainIsAnIsland =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..application..", "..adapter..", "..config..")
                    .as("domain must not depend on application, adapter, or config");

    @ArchTest
    static final ArchRule domainIsFrameworkFree =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "javafx..")
                    .as("domain must stay free of Spring and JavaFX");

    // The domain models the filesystem as Path values only; touching the disk or a stream is an
    // adapter's responsibility.
    @ArchTest
    static final ArchRule domainDoesNoFileIo =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().belongToAnyOf(
                            Files.class, File.class, InputStream.class, OutputStream.class,
                            Reader.class, Writer.class, RandomAccessFile.class)
                    .as("domain must not perform filesystem or stream I/O");

    // Use cases depend on ports (abstractions), never on the adapters that implement them or on the
    // Spring wiring that assembles them.
    @ArchTest
    static final ArchRule applicationDependsOnPortsNotAdapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..")
                    .as("application must depend on ports, not adapters or config");

    // Adapters are effect implementations reached only through their ports. Only config, the
    // composition root, may name a concrete adapter type (to wire it); anything else depending on a
    // concrete adapter has bypassed the port it should go through.
    @ArchTest
    static final ArchRule adaptersReachedOnlyThroughPorts =
            noClasses().that().resideOutsideOfPackages("..adapter..", "..config..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .as("only config (the composition root) may depend on a concrete adapter; everything else goes through ports");

    // Config is the composition root: it may depend on adapters (to wire them), but the arrow must
    // not point back - an adapter depending on config would mean the wiring layer's concerns leak
    // into the effect implementation it's supposed to just assemble.
    @ArchTest
    static final ArchRule adaptersDoNotDependOnConfig =
            noClasses().that().resideInAPackage("..adapter..")
                    .should().dependOnClassesThat().resideInAPackage("..config..")
                    .as("adapters receive resolved values from wiring; they must not depend on config");

    // Adapters are siblings, each an isolated effect implementation. If one needed another's
    // behavior, it should depend on the port that other adapter implements - Spring already injects
    // every adapter by its port, so a concrete adapter-to-adapter dependency is never necessary.
    @ArchTest
    static final ArchRule adaptersAreSiblings =
            slices().matching("..adapter.(*)..")
                    .should().notDependOnEachOther()
                    .as("adapters must not depend on other adapters; depend on the other adapter's port instead");

    // The slice rule above only buckets classes that sit in an adapter subpackage (fs, imaging,
    // ...) - a class placed directly in adapter itself would have no subpackage segment to key a
    // slice on, so it would fall outside every slice and go unchecked in both directions. These two
    // rules close that gap explicitly. Empty-should is allowed on this direction only, since the
    // `that()` clause can legitimately match zero classes rather than finding zero violations among
    // some.
    @ArchTest
    static final ArchRule looseAdapterClassesDoNotReachIntoSubpackages =
            noClasses().that().resideInAPackage("..adapter")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.*..")
                    .as("a class placed directly in adapter must not depend on an adapter subpackage; depend on its port instead")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule adapterSubpackagesDoNotReachIntoLooseAdapterClasses =
            noClasses().that().resideInAPackage("..adapter.*..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter")
                    .as("an adapter subpackage must not depend on a class placed directly in adapter; depend on its port instead");
}
