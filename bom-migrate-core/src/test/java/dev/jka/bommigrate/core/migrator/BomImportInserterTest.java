package dev.jka.bommigrate.core.migrator;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BomImportInserterTest {

    private final BomImportInserter inserter = new BomImportInserter();

    @Test
    void insertNewDepMgmtBlockBeforeDependencies() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>

                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "my-bom", "1.0.0")
        ));

        assertThat(result).contains("<dependencyManagement>");
        assertThat(result).contains("<artifactId>my-bom</artifactId>");
        assertThat(result).contains("<type>pom</type>");
        assertThat(result).contains("<scope>import</scope>");
        // The new block must appear BEFORE the existing <dependencies>
        int depMgmtIdx = result.indexOf("<dependencyManagement>");
        int depsIdx = result.indexOf("<dependencies>", depMgmtIdx + "<dependencyManagement>".length());
        // That second <dependencies> is inside depMgmt
        // The top-level one comes after the closing </dependencyManagement>
        int topDepsIdx = result.indexOf("<dependencies>",
                result.indexOf("</dependencyManagement>"));
        assertThat(topDepsIdx).isGreaterThan(depMgmtIdx);
    }

    @Test
    void insertIntoExistingDepMgmt() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>

                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.existing</groupId>
                                <artifactId>existing-dep</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>

                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "my-bom", "1.0.0")
        ));

        // The existing dep should still be present
        assertThat(result).contains("<artifactId>existing-dep</artifactId>");
        // The new import should have been added (exactly one depMgmt block)
        assertThat(result.split("<dependencyManagement>").length - 1).isEqualTo(1);
        assertThat(result).contains("<artifactId>my-bom</artifactId>");
        assertThat(result).contains("<scope>import</scope>");
    }

    @Test
    void multipleImportsAllAppear() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "backend-core", "1.0.0"),
                new BomImportInserter.BomImport("com.example", "misc", "1.0.0")
        ));

        assertThat(result).contains("<artifactId>backend-core</artifactId>");
        assertThat(result).contains("<artifactId>misc</artifactId>");
        // Both should be inside the single new dependencyManagement block
        assertThat(result.split("<dependencyManagement>").length - 1).isEqualTo(1);
    }

    @Test
    void noImportsIsNoop() {
        String pom = "<project></project>";
        assertThat(inserter.insertImports(pom, List.of())).isEqualTo(pom);
    }

    @Test
    void insertBeforeProjectCloseWhenNoDependencies() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "my-bom", "1.0.0")
        ));

        assertThat(result).contains("<dependencyManagement>");
        // depMgmt must appear before </project>
        int depMgmtIdx = result.indexOf("<dependencyManagement>");
        int projectCloseIdx = result.indexOf("</project>");
        assertThat(depMgmtIdx).isLessThan(projectCloseIdx);
    }

    @Test
    void insertsNewPropertiesBlockWhenNoneExists() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>

                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("my-bom.version", "1.0.0");

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "my-bom", "${my-bom.version}")
        ), props);

        assertThat(result).contains("<properties>");
        assertThat(result).contains("<my-bom.version>1.0.0</my-bom.version>");
        assertThat(result).contains("</properties>");
        // <properties> must appear before <dependencyManagement>
        int propsIdx = result.indexOf("<properties>");
        int depMgmtIdx = result.indexOf("<dependencyManagement>");
        assertThat(propsIdx).isGreaterThanOrEqualTo(0);
        assertThat(propsIdx).isLessThan(depMgmtIdx);
    }

    @Test
    void mergesIntoExistingPropertiesBlock() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>

                    <properties>
                        <java.version>17</java.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("backend-core.version", "1.0.0");
        props.put("misc.version", "1.0.0");

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "backend-core", "${backend-core.version}"),
                new BomImportInserter.BomImport("com.example", "misc", "${misc.version}")
        ), props);

        // Exactly one <properties> block (no duplicate)
        assertThat(result.split("<properties>").length - 1).isEqualTo(1);
        // Existing key is preserved and new keys are added
        assertThat(result).contains("<java.version>17</java.version>");
        assertThat(result).contains("<backend-core.version>1.0.0</backend-core.version>");
        assertThat(result).contains("<misc.version>1.0.0</misc.version>");
    }

    @Test
    void existingPropertyKeysAreNotOverwritten() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>

                    <properties>
                        <my-bom.version>9.9.9</my-bom.version>
                    </properties>

                    <dependencies/>
                </project>
                """;

        Map<String, String> props = new LinkedHashMap<>();
        props.put("my-bom.version", "1.0.0");

        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "my-bom", "${my-bom.version}")
        ), props);

        // The pre-existing value wins; our proposed 1.0.0 must NOT be inserted
        assertThat(result).contains("<my-bom.version>9.9.9</my-bom.version>");
        assertThat(result).doesNotContain("<my-bom.version>1.0.0</my-bom.version>");
        // Still exactly one <properties> block
        assertThat(result.split("<properties>").length - 1).isEqualTo(1);
    }

    @Test
    void propertyReferenceVersionPassesThroughVerbatim() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>service-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies/>
                </project>
                """;

        // Empty properties map — just make sure the version string flows through as-is
        String result = inserter.insertImports(pom, List.of(
                new BomImportInserter.BomImport("com.example", "foo", "${foo.version}")
        ), Map.of());

        assertThat(result).contains("<version>${foo.version}</version>");
        // No <properties> block should be added when the caller passes an empty map
        assertThat(result).doesNotContain("<properties>");
    }
}
