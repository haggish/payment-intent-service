package com.example.payments.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.example.payments",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domain_does_not_depend_on_spring =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("the domain layer must be framework-agnostic");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_jdbc =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("java.sql..")
                    .because("the domain layer must not know about persistence");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_aws =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("software.amazon..")
                    .because("the domain layer must not know about messaging infrastructure");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_adapters =
            noClasses()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..adapter..")
                    .because("dependencies point inward");

    @ArchTest
    static final ArchRule application_does_not_depend_on_adapters =
            noClasses()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..adapter..")
                    .because("the application layer talks to ports, not adapters");
}
