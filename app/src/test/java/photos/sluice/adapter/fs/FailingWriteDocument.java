package photos.sluice.adapter.fs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

// Fails the write after the temporary file exists, so the cleanup runs against a real leftover
// rather than against nothing. Thrown as IOException, the type the cleanup is attached to. On a
// real filesystem the same state would mean breaking the volume mid-write.
final class FailingWriteDocument extends YamlConfigFile {

    FailingWriteDocument(final Path configFile) {
        super(configFile);
    }

    @Override
    void dump(final Path target, final Map<String, Object> document) throws IOException {
        super.dump(target, document);
        throw new IOException("the volume stopped responding part-way through the write");
    }
}
