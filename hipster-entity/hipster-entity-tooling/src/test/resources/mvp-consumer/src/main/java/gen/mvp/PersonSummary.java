package gen.mvp;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.api.View;

@View(gen = GenLevel.BUILDER_ALL)
public interface PersonSummary extends PersonEntity {
    Long id();
    String firstName();
    String lastName();
}
