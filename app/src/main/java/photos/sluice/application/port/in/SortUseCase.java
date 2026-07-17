package photos.sluice.application.port.in;

import photos.sluice.domain.model.SortScope;
import photos.sluice.domain.model.SortSummary;

public interface SortUseCase {

    SortSummary sort(SortScope scope);
}
