package com.storeops.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.storeops.shared.error.AppError;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Deterministic enforcement of the StoreOps architecture rules (Section 3.5). These run as part
 * of {@code mvn test}; any violation fails the build.
 */
class ArchitectureRulesTest {

    private static final List<String> MODULES = List.of("activities", "programmes", "staff", "alerts", "reports");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.isJar() || location.contains("storeops-"))
                .importPackages("com.storeops");
    }

    /** Guards against rules passing vacuously because module classes were not imported. */
    @Test
    void importsClassesFromEveryModule() {
        for (String module : MODULES) {
            String pkg = "com.storeops." + module;
            assertThat(classes.stream().anyMatch(c -> c.getPackageName().startsWith(pkg)))
                    .as("classes imported from %s", pkg).isTrue();
        }
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching("com.storeops.(*)..").should().beFreeOfCycles().check(classes);
    }

    /** Module boundary: other modules may only use a module's {@code api} and {@code model} packages. */
    @Test
    void crossModuleAccessGoesThroughPublicApiOnly() {
        for (String module : MODULES) {
            String base = "com.storeops." + module;
            ArchRule rule = noClasses()
                    .that().resideOutsideOfPackage(base + "..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(base + ".repository..", base + ".service..", base + ".routes..",
                            base + ".events..")
                    .because("cross-module reads must go through " + base + ".api (module boundary rule)");
            rule.check(classes);
        }
    }

    /** Event bus only: nothing may depend on alerts or reports; they react to events. */
    @Test
    void alertsAndReportsAreReachedViaEventBusOnly() {
        noClasses().that().resideOutsideOfPackage("com.storeops.alerts..")
                .should().dependOnClassesThat().resideInAPackage("com.storeops.alerts..")
                .because("alerts must be triggered via EventBus.emit(), never by direct import")
                .check(classes);
        noClasses().that().resideOutsideOfPackage("com.storeops.reports..")
                .should().dependOnClassesThat().resideInAPackage("com.storeops.reports..")
                .because("reports must be triggered via EventBus.emit(), never by direct import")
                .check(classes);
    }

    /** Layer separation: routes never touch repositories directly. */
    @Test
    void routesDoNotAccessRepositories() {
        noClasses().that().resideInAPackage("..routes..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .check(classes);
    }

    /** Layer separation: repositories contain no HTTP or service logic. */
    @Test
    void repositoriesDoNotDependOnWebOrServiceLayers() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..routes..", "..service..", "org.springframework.web..", "org.springframework.http..",
                        "com.storeops.shared.events..")
                .check(classes);
    }

    /** Layer separation: services are HTTP-agnostic. */
    @Test
    void servicesDoNotDependOnWebLayer() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..routes..", "org.springframework.web..", "org.springframework.http..")
                .check(classes);
    }

    /** Error contract: services and routes only construct exceptions from the AppError hierarchy. */
    @Test
    void servicesAndRoutesOnlyThrowAppErrors() {
        DescribedPredicate<JavaConstructorCall> rawThrowable = DescribedPredicate.describe(
                "a Throwable that is not an AppError",
                call -> call.getTargetOwner().isAssignableTo(Throwable.class)
                        && !call.getTargetOwner().isAssignableTo(AppError.class));
        noClasses().that().resideInAnyPackage("..service..", "..routes..")
                .should().callConstructorWhere(rawThrowable)
                .because("services and routes must use the AppError hierarchy (error contract)")
                .check(classes);
    }

    /** Read-only reports: reports may only use the activities read API. */
    @Test
    void reportsOnlyReadFromOtherModules() {
        noClasses().that().resideInAPackage("com.storeops.reports..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.storeops.activities.service..", "com.storeops.programmes.service..",
                        "com.storeops.staff.service..", "com.storeops.shared.events..")
                .because("reports aggregates read-only; it never writes to or emits on behalf of other modules")
                .check(classes);
    }
}
