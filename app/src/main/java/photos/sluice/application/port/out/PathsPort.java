package photos.sluice.application.port.out;

import java.nio.file.Path;

public interface PathsPort {

    Path inbox();

    Path sorted();

    Path review();

    Path duplicates();

    Path unreviewable();

    Path library();

    Path logs();
}
