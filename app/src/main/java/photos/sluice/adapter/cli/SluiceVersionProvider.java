package photos.sluice.adapter.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * What {@code --version} prints, read from the packaged build rather than hand-maintained here.
 *
 * <p>The manifest's {@code Implementation-Version} is set only in a packaged jar, so a run from
 * source, tests included, always reads null.
 */
final class SluiceVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        final String version = this.getClass().getPackage().getImplementationVersion();
        return new String[] {version == null ? "Sluice (no version - running from source)" : "Sluice " + version};
    }
}
