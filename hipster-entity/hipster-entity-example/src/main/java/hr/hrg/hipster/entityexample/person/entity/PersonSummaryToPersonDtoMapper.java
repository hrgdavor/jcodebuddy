// @generated file hr.hrg.hipster.entity.tooling.ViewMapperGenerator — Mapper from the declared source view to PersonDto.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.hipster.entityexample.person.entity;

import java.util.List;
import java.util.Map;
import hr.hrg.hipster.entity.api.ViewMeta;

/**
 * Maps a {@link hr.hrg.hipster.entityexample.person.entity.PersonSummary} to a {@link PersonDto} by direct field access.
 *
 * <p>Generated: the mapping is a real method body, not a reflective bean copy, so a
 * rename of either view is a compile error here instead of a silently dropped field.
 * A field the generator could not map safely is a literal {@code null} and is named
 * in the regeneration report.</p>
 */
public final class PersonSummaryToPersonDtoMapper {

    private PersonSummaryToPersonDtoMapper() {
    }

    /** One-way mapping. A null source is not accepted; null fields map to null. */
    public static PersonDto toPersonDto(PersonSummary src) {
        return PersonDto_.META.create(new Object[] { src.id(), src.firstName(), src.lastName(), src.age(), src.departmentName(), src.metadata() });
    }
}
