package photos.sluice.application.port.out;

import java.nio.file.Path;

public interface Sha256Port {

    String hash(Path file);
}
