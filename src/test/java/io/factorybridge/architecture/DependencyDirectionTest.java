package io.factorybridge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "io.factorybridge",
        importOptions = com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests.class)
class DependencyDirectionTest {
    @ArchTest
    static final ArchRule domainRemainsFrameworkFree =
            classes()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("..domain..", "java..");

    @ArchTest
    static final ArchRule applicationOnlyKnowsDomainAndPorts =
            classes()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("..application..", "..domain..", "java..");

    @ArchTest
    static final ArchRule persistenceDoesNotKnowHttp =
            noClasses()
                    .that()
                    .resideInAPackage("..adapter.persistence..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..adapter.http..", "..adapter.web..");

    @ArchTest
    static final ArchRule httpDoesNotKnowDatabase =
            noClasses()
                    .that()
                    .resideInAPackage("..adapter.http..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..adapter.persistence..", "..adapter.web..");
}
