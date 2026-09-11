package photos.sluice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// A design doc is read by nobody this build checks, so a broken link fails silently as a 404 for
// whoever follows it. Two link shapes are walked: a markdown link between design docs, and a
// Javadoc pointer from a Java class into a design doc. Both are resolved against the filesystem.
// A rename that misses one side is exactly the failure this guards against.
class DesignDocLinksTest {

    private static final Path DESIGN_ROOT = Paths.get("docs/design");
    private static final Path REPO_ROOT = Paths.get("..");

    private static final Pattern MARKDOWN_LINK = Pattern.compile("]\\(([^)#]+\\.md)(#[^)]*)?\\)");
    private static final Pattern JAVADOC_POINTER = Pattern.compile("app/docs/design/[^\\s}]+\\.md");

    @Test
    void everyMarkdownLinkBetweenDesignDocsResolves() throws IOException {
        final List<String> broken = new ArrayList<>();
        for (final Path doc : designDocs()) {
            final String text = Files.readString(doc);
            final Matcher matcher = MARKDOWN_LINK.matcher(text);
            while (matcher.find()) {
                final Path target = doc.getParent().resolve(matcher.group(1)).normalize();
                if (!Files.exists(target)) {
                    broken.add(doc + " -> " + matcher.group(1));
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    void everyJavadocPointerIntoADesignDocResolves() throws IOException {
        final List<String> broken = new ArrayList<>();
        for (final Path java : javaSources()) {
            final String text = Files.readString(java);
            final Matcher matcher = JAVADOC_POINTER.matcher(text);
            while (matcher.find()) {
                final Path target = REPO_ROOT.resolve(matcher.group()).normalize();
                if (!Files.exists(target)) {
                    broken.add(java + " -> " + matcher.group());
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    private static List<Path> designDocs() throws IOException {
        try (final Stream<Path> walk = Files.walk(DESIGN_ROOT)) {
            return walk.filter(p -> p.toString().endsWith(".md")).toList();
        }
    }

    private static List<Path> javaSources() throws IOException {
        try (final Stream<Path> walk = Files.walk(Paths.get("src/main/java"))) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
