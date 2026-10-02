package hr.hrg.hipster.entity.person;

import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldSource;

import java.util.List;
import java.util.Map;

public interface PersonSummary extends PersonEntity {
    String firstName();
    String lastName();

    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
    Integer age();

    @FieldSource(kind = FieldKind.JOINED, relation = "department.name")
    String departmentName();

    Map<String, List<Long>> metadata();
}
