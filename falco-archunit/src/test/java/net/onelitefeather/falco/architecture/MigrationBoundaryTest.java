package net.onelitefeather.falco.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the promise that {@code falco-migration} converts stored NBT without a running server.
 *
 * <p>The engine has to be usable before anything boots: a world is upgraded on disk, offline, and
 * only afterwards handed to a server that can load it. Nothing in the build enforces that today — a
 * single {@code import net.minestom....} would compile happily, because the compiler cannot tell
 * "runs on a server" from "runs on stored bytes" for us. This rule reads the bytecode instead and
 * fails the moment that boundary is crossed.
 */
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
class MigrationBoundaryTest {

    private static final String MIGRATION = "net.onelitefeather.falco.migration..";

    /**
     * The engine converts stored NBT and must run without a server, which is what lets a world be
     * converted before anything boots.
     */
    @ArchTest
    static final ArchRule migrationKnowsNoMinestom = noClasses()
            .that().resideInAPackage(MIGRATION)
            .should().dependOnClassesThat().resideInAnyPackage("net.minestom..")
            .because("the engine converts stored NBT and must run without a server, which is what "
                   + "lets a world be converted before anything boots");
}
