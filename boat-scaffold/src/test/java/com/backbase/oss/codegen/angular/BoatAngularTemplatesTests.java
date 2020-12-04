package com.backbase.oss.codegen.angular;

import lombok.SneakyThrows;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.openapitools.codegen.ClientOptInput;
import org.openapitools.codegen.CodegenConstants;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import org.openapitools.codegen.config.GlobalSettings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * These tests verifies that the code generation works for various combinations of configuration
 * parameters; the projects that are generated are later compiled in the integration test phase.
 */
@RunWith(Parameterized.class)
public class BoatAngularTemplatesTests {

    private static final String PROP_BASE = BoatAngularTemplatesTests.class.getSimpleName() + ".";
    private static final boolean PROP_FAST = Boolean.getBoolean(PROP_BASE + "fast");

    private static final String[] CASES = {"nom", "reg", "mck", "pir", "amp", "srv"};

    @Parameterized.Parameters(name = "{0}")
    static public Object parameters() {
        final List<Object[]> data = new ArrayList<>();

        if (PROP_FAST) {
            data.add(new Object[] {caseName(0), 0});
        }

        // generate all combinations
        for (int mask = 0; mask < 1 << CASES.length; mask++) {
            if (PROP_FAST && Integer.bitCount(mask) != 1) {
                continue;
            }

            data.add(new Object[] {caseName(mask), mask,});
        }

        if (PROP_FAST) {
            data.add(new Object[] {caseName(-1), -1});
        }

        return data;
    }

    static private String caseName(int mask) {
        return mask == 0
            ? "backbase"
            : IntStream.range(0, CASES.length)
                .filter(n -> (mask & (1 << n)) != 0)
                .mapToObj(n -> CASES[n])
                .collect(joining("-", "backbase-", ""));
    }

    static private final String TEST_OUTPUT = System.getProperty(PROP_BASE + "output", "target/test-outputs");

    @BeforeClass
    static public void setUpClass() throws IOException {
        Files.createDirectories(Paths.get(TEST_OUTPUT));
        FileUtils.deleteDirectory(new File(TEST_OUTPUT));
    }

    private final String caseName;

    private final boolean npmRepository;
    private final boolean npmName;
    private final boolean withMocks;
    private final boolean providedInRoot;
    private final boolean apiModulePrefix;
    private final boolean serviceSuffix;

    static private List<File> files;

    public BoatAngularTemplatesTests(String caseName, int mask) {
        this.caseName = caseName;

        this.npmName = (mask & 1) != 0;
        this.npmRepository = (mask & 1 << 1) != 0;
        this.withMocks = (mask & 1 << 2) != 0;
        this.providedInRoot = (mask & 1 << 3) != 0;
        this.apiModulePrefix = (mask & 1 << 4) != 0;
        this.serviceSuffix = (mask & 1 << 5) != 0;
    }

    @Before
    public void generate() throws IOException {
        final Path marker = Paths.get(TEST_OUTPUT, "." + this.caseName);

        // generate once per case name
        if (!Files.exists(marker)) {
            files = generateFrom(null);
            // used in development
            Files.write(marker, Collections.singletonList(""));
        }

        assertThat(files, not(nullValue()));
        assertThat(files.size(), not(equalTo(0)));
    }

    @Test
    public void npmName() {
        assertThat(
                findPattern(selectFiles("/package\\.json$"), "\"name\": \"@example/angular-http\""),
                equalTo(this.npmName)
        );
    }

    @Test
    public void npmRepository() {
        assertThat(
                findPattern(selectFiles("/package\\.json$"), "\"registry\":"),
                equalTo(this.npmRepository && this.npmName)
        );
    }

    @Test
    public void withMocks() {
        assertThat(
                findPattern(selectFiles("/api/.+\\.service\\.mocks\\.ts$"), "MocksProvider: Provider = createMocks"),
                equalTo(this.withMocks)
        );
    }

    @Test
    public void providedInRoot() {
        assertThat(
                findPattern("/api/.+\\.service.ts$", "providedIn: 'root'"),
                equalTo(this.providedInRoot)
        );
        assertThat(
                findPattern("/api\\.module\\.ts$", "providers: \\[]"),
                equalTo(this.providedInRoot)
        );
    }

    @Test
    public void apiModulePrefix() {
        assertThat(
                findPattern("/api\\.module\\.ts$", "export class BoatApiModule"),
                equalTo(this.apiModulePrefix)
        );
        assertThat(
                findPattern("/api\\.module\\.ts$", "export class ApiModule"),
                equalTo(!this.apiModulePrefix)
        );
    }

    @Test
    public void serviceSuffix() {
        assertThat(
                findPattern("/api/.+\\.service.ts$$", "export class .*Gateway "),
                equalTo(this.serviceSuffix)
        );
        assertThat(
                findPattern("/api/.+\\.service.ts$", "export class .*Service "),
                equalTo(!this.serviceSuffix)
        );
    }

    private boolean findPattern(String filePattern, String linePattern) {
        List<String> selection = selectFiles(filePattern);
        assertThat(selection, not(hasSize(0)));
        return findPattern(selection, linePattern);
    }

    private List<String> selectFiles(String filePattern) {
        final Predicate<String> fileMatch = Pattern.compile(filePattern).asPredicate();
        final List<String> selection = files.stream()
                .map(File::getPath)
                .map(path -> path.replace(File.separatorChar, '/'))
                .filter(fileMatch)
                .collect(toList());
        return selection;
    }

    private boolean findPattern(List<String> selection, String linePattern) {
        final Predicate<String> lineMatch = Pattern.compile(linePattern).asPredicate();
        return selection.stream()
            .filter(file -> contentMatches(file, lineMatch))
            .findAny()
            .isPresent();
    }

    @SneakyThrows
    private boolean contentMatches(String path, Predicate<String> lineMatch) {
        try (final Stream<String> lines = Files.lines(Paths.get(path))) {
            return lines.anyMatch(lineMatch);
        }
    }

    private List<File> generateFrom(String templates) {
        final File input = new File("src/test/resources/boat-spring/openapi.yaml");
        final CodegenConfigurator cf = new CodegenConfigurator();

        cf.setGeneratorName(BoatAngularGenerator.NAME);
        cf.setInputSpec(input.getAbsolutePath());
        cf.setOutputDir(TEST_OUTPUT);

        GlobalSettings.setProperty(CodegenConstants.APIS, "");
        GlobalSettings.setProperty(CodegenConstants.API_DOCS, "true");
        GlobalSettings.setProperty(CodegenConstants.API_TESTS, "true");
        GlobalSettings.setProperty(CodegenConstants.MODELS, "");
        GlobalSettings.setProperty(CodegenConstants.MODEL_TESTS, "true");
        GlobalSettings.setProperty(CodegenConstants.MODEL_DOCS, "true");
        GlobalSettings.setProperty(CodegenConstants.SUPPORTING_FILES, "");

        cf.setApiNameSuffix("-api");
        cf.setModelNameSuffix(this.caseName);

        cf.addAdditionalProperty(BoatAngularGenerator.WITH_MOCKS, this.withMocks);
        if (this.npmRepository) {
            cf.addAdditionalProperty(BoatAngularGenerator.NPM_REPOSITORY, "https://registry.example.com/npm");
        }
        if (this.npmName) {
            cf.addAdditionalProperty(BoatAngularGenerator.NPM_NAME, "@example/angular-http");
        }
        cf.addAdditionalProperty(BoatAngularGenerator.PROVIDED_IN_ROOT, this.providedInRoot);
        if (this.apiModulePrefix) {
            cf.addAdditionalProperty(BoatAngularGenerator.API_MODULE_PREFIX, "Boat");
        }
        if (this.serviceSuffix) {
            cf.addAdditionalProperty(BoatAngularGenerator.SERVICE_SUFFIX, "Gateway");
        }

        final String destPackage = this.caseName.replace('-', '.') + ".";

        cf.setApiPackage(destPackage + "api");
        cf.setModelPackage(destPackage + "model");
        cf.setInvokerPackage(destPackage + "invoker");

        cf.addAdditionalProperty(CodegenConstants.ARTIFACT_ID, "boat-angular-templates-tests");
        cf.setTemplateDir(templates);

        final ClientOptInput coi = cf.toClientOptInput();

        return new DefaultGenerator().opts(coi).generate();
    }
}
