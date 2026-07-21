package photos.sluice.application.port.out;

import photos.sluice.domain.cull.CullScope;
import photos.sluice.domain.cull.MontageConfig;
import photos.sluice.domain.cull.PrepDir;

public interface MontageRenderer {

    PrepDir build(CullScope scope, MontageConfig config);
}
