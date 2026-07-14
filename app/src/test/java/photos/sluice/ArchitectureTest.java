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
}
