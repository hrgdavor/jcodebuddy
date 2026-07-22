package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.BooleanOption;
import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldSource;
import hr.hrg.hipster.entity.api.View;
import hr.hrg.hipster.entity.api.ViewWriter;
import hr.hrg.hipster.entity.api.GenLevel;

import java.util.List;
import java.util.Map;

@View(gen = GenLevel.BUILDER_ALL)
public interface PersonSummary extends Person {

    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
    Integer age();

    @FieldSource(kind = FieldKind.JOINED, relation = "department.name")
    String departmentName();

    Map<String, List<Long>> metadata();

    // generated boilerplate, for better dev experience

    public record Record(
        Long id, 
        String firstName, 
        String lastName, 
        Integer age, 
        String departmentName, 
        Map<String, List<Long>> metadata
    ) implements PersonSummary {}

    public interface Write extends PersonSummary, ViewWriter{
        Write id(Long value);
        Write firstName(String value);
        Write lastName(String value);
        Write age(Integer value);
        Write departmentName(String value);
        Write metadata(Map<String, List<Long>> value);        
    }

    public default PersonSummaryBuilder toBuilder(){ return new PersonSummaryBuilder(this); }
    public default PersonSummaryBuilderTracking toBuilderTracking(){ return new PersonSummaryBuilderTracking(this); }
}
