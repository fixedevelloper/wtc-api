package com.wtc;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

/**
 * Architecture exécutable (ADR 0001) : le build échoue si une règle est violée. Ces règles
 * protègent le domaine de toute dépendance technique et imposent des frontières nettes entre
 * modules.
 */
class ArchitectureTest {

    private static final List<String> MODULES =
            List.of(
                    "identity",
                    "customer",
                    "reference",
                    "wallet",
                    "transfer",
                    "payment",
                    "backoffice",
                    "notification");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.wtc");
    }

    @Test
    void domainDoesNotDependOnFrameworksOrOuterLayers() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("..domain..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "org.springframework..",
                                "jakarta..",
                                "org.hibernate..",
                                "tools.jackson..",
                                "com.fasterxml..",
                                "..infrastructure..",
                                "..application..",
                                "..api..")
                        .allowEmptyShould(true);
        rule.check(classes);
    }

    @Test
    void applicationDoesNotDependOnApiOrInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("..application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..api..", "..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void apiAndInfrastructureAreIndependentOfEachOther() {
        noClasses()
                .that()
                .resideInAPackage("..api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..infrastructure..")
                .allowEmptyShould(true)
                .check(classes);
        noClasses()
                .that()
                .resideInAPackage("..infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void modulesOnlyUseEachOthersApplicationLayer() {
        for (String module : MODULES) {
            String[] foreignInternals =
                    MODULES.stream()
                            .filter(other -> !other.equals(module))
                            .flatMap(
                                    other ->
                                            List.of(
                                                    "com.wtc." + other + ".api..",
                                                    "com.wtc." + other + ".domain..",
                                                    "com.wtc." + other + ".infrastructure..")
                                                    .stream())
                            .toArray(String[]::new);
            noClasses()
                    .that()
                    .resideInAPackage("com.wtc." + module + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(foreignInternals)
                    .allowEmptyShould(true)
                    .because(
                            "le module "
                                    + module
                                    + " ne doit passer que par la couche application des autres"
                                    + " modules")
                    .check(classes);
        }
    }

    @Test
    void modulesAreFreeOfCycles() {
        slices().matching("com.wtc.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void sharedKernelDoesNotDependOnBusinessModules() {
        String[] business = MODULES.stream().map(m -> "com.wtc." + m + "..").toArray(String[]::new);
        noClasses()
                .that()
                .resideInAPackage("com.wtc.shared..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(business)
                .check(classes);
    }

    @Test
    void controllersLiveInApiPackagesOrSharedWeb() {
        classes()
                .that()
                .areAnnotatedWith(RestController.class)
                .should()
                .resideInAnyPackage("..api..", "com.wtc.shared.web..")
                .check(classes);
    }

    @Test
    void noFieldInjection() {
        noFields()
                .should()
                .beAnnotatedWith(Autowired.class)
                .because("l'injection se fait par constructeur (testable, immuable)")
                .check(classes);
    }

    @Test
    void noFloatingPointMoneyInDomainOrApplication() {
        fields().that()
                .areDeclaredInClassesThat()
                .resideInAnyPackage("..domain..", "..application..")
                .should()
                .notHaveRawType(double.class)
                .andShould()
                .notHaveRawType(float.class)
                .because("les montants sont des BigDecimal (ADR 0004)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void generalCodingRules() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
        GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(classes);
    }

    @Test
    void everyModulePackageIsDocumented() {
        String undocumented =
                MODULES.stream()
                        .filter(
                                m ->
                                        classes.stream()
                                                .noneMatch(
                                                        c ->
                                                                c.getName()
                                                                        .equals(
                                                                                "com.wtc."
                                                                                        + m
                                                                                        + ".package-info")))
                        .collect(Collectors.joining(", "));
        org.assertj.core.api.Assertions.assertThat(undocumented).isEmpty();
    }
}
