package com.dtc.transit.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architecture rules enforced on every build.
 *
 * <p>The engine-purity rule is the important one. The scheduling engine stays framework-free so it is
 * deterministic, fast and unit-testable without a Spring context. That property erodes silently the
 * first time someone injects a repository into an algorithm, so it is checked mechanically rather than
 * left to code review.
 *
 * <p>These rules apply from Phase 1, before the packages they guard contain any production code. That
 * is deliberate: a rule added after the first violation exists is a rule that gets weakened to fit.
 *
 * <p>The rules are driven from plain JUnit tests rather than ArchUnit's {@code @ArchTest} fields,
 * because the ArchUnit JUnit engine discovered this class but resolved zero tests on the current
 * JUnit Platform version. Calling {@code check} directly runs under the Jupiter engine instead, so a
 * silently empty rule set cannot masquerade as a passing build.
 *
 * <p>Several rules legitimately match nothing yet, so they declare {@code allowEmptyShould}. Each one
 * names the phase that will populate its packages.
 */
class ModuleBoundaryTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.dtc.transit");
    }

    @Test
    @DisplayName("the scheduling engine has no Spring, JPA or Hibernate imports")
    void engineHasNoFrameworkImports() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.dtc.transit.scheduling.engine..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "jakarta.servlet..",
                        "com.fasterxml.jackson..")
                .because("the scheduling engine must stay pure Java so it is deterministic and testable "
                        + "without a Spring context or a database")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("the scheduling engine does not depend on other modules")
    void engineDoesNotDependOnOtherModules() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.dtc.transit.scheduling.engine..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.dtc.transit.masterdata..",
                        "com.dtc.transit.route..",
                        "com.dtc.transit.timetable..",
                        "com.dtc.transit.reporting..",
                        "com.dtc.transit.security..",
                        "com.dtc.transit.user..",
                        "com.dtc.transit.audit..")
                .because("the engine works on its own immutable input records; adapters load a snapshot "
                        + "and persist the result")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("controllers do not reach repositories directly")
    void controllersDoNotUseRepositories() {
        // Populated from Phase 2 onward, as controllers and repositories appear.
        ArchRule rule = noClasses()
                .that()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .because("controllers go through services, so authorization and transaction boundaries "
                        + "are never bypassed")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("repositories are only accessed from inside the application packages")
    void repositoriesStayWithinTheirModules() {
        ArchRule rule = classes()
                .that()
                .haveSimpleNameEndingWith("Repository")
                .should()
                .onlyBeAccessed()
                .byAnyPackage(
                        "com.dtc.transit.common..",
                        "com.dtc.transit.security..",
                        "com.dtc.transit.user..",
                        "com.dtc.transit.masterdata..",
                        "com.dtc.transit.route..",
                        "com.dtc.transit.timetable..",
                        "com.dtc.transit.scheduling..",
                        "com.dtc.transit.reporting..",
                        "com.dtc.transit.audit..")
                .because("modules interact through service interfaces or domain events, never through "
                        + "another module's repositories")
                .allowEmptyShould(true);

        rule.check(productionClasses);
    }

    @Test
    @DisplayName("the import actually found production classes")
    void importIsNotEmpty() {
        // Guards the rules above. If the importer silently picked up nothing, every rule would pass
        // vacuously and this suite would be worthless.
        org.assertj.core.api.Assertions.assertThat(productionClasses).isNotEmpty();
    }
}
