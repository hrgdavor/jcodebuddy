package hr.hrg.hipster.entityexample.person.entity;

import java.util.List;
import java.util.Map;

import hr.hrg.hipster.entity.core.EEnumSetBuilder64;
import hr.hrg.hipster.entity.core.ViewChangeTracking;

// generated when @View(tracking=true) is used on the PersonSummary interface
public class PersonSummaryBuilderTracking implements PersonSummary.Write, ViewChangeTracking<PersonSummary_, EEnumSetBuilder64<PersonSummary_>> {
    // tracking variant
    final EEnumSetBuilder64<PersonSummary_> mf;

    Long id;
    String firstName;
    String lastName;
    Integer age;
    String departmentName;
    Map<String, List<Long>> metadata;

    public PersonSummaryBuilderTracking(PersonSummary source) {
        this.mf = new EEnumSetBuilder64<>(PersonSummary_.class);

        this.id = source.id();
        this.firstName = source.firstName();
        this.lastName = source.lastName();
        this.age = source.age();
        this.departmentName = source.departmentName();
        this.metadata = source.metadata();
    }

    PersonSummary build(){
        return new PersonSummary.Record(id, firstName, lastName, age, departmentName, metadata);
    }
    
    public Long id(){return id;}
    public String firstName(){return firstName;}
    public String lastName(){return lastName;}
    public Integer age(){return age;}
    public String departmentName(){return departmentName;}
    public Map<String, List<Long>> metadata(){return metadata;}

        
    // tracking variant
    public PersonSummaryBuilderTracking() {
        this.mf = new EEnumSetBuilder64<>(PersonSummary_.class);
    }

    // tracking variant
    PersonSummaryBuilderTracking(EEnumSetBuilder64<PersonSummary_> modifiedFields) {
        this.mf = modifiedFields;
    }

    // tracking variant
    public static class TrackingStrict extends PersonSummaryBuilderTracking {
        public TrackingStrict() {
            super(new EEnumSetBuilder64<>(PersonSummary_.class));
        }
    }

    public Object get(int field){
        return switch (field) {
            case 0 -> id;
            case 1 -> firstName;
            case 2 -> lastName;
            case 3 -> age;
            case 4 -> departmentName;
            case 5 -> metadata;
            default -> null;
        };
    }

    @Override
    public boolean isChanged() {return mf.size() > 0;}

    @Override
    public EEnumSetBuilder64<PersonSummary_> changes() {return mf;}

    public PersonSummaryBuilderTracking id(Long value){ mf.addOrdinalChange(0, id, value); id = value; return this;}
    public PersonSummaryBuilderTracking firstName(String value){ mf.addOrdinalChange(1, firstName, value); firstName = value; return this;}
    public PersonSummaryBuilderTracking lastName(String value){ mf.addOrdinalChange(2, lastName, value); lastName = value; return this;}
    public PersonSummaryBuilderTracking age(Integer value){ mf.addOrdinalChange(3, age, value); age = value; return this;}
    public PersonSummaryBuilderTracking departmentName(String value){ mf.addOrdinalChange(4, departmentName, value); departmentName = value; return this;}
    public PersonSummaryBuilderTracking metadata(Map<String, List<Long>> value){ mf.addOrdinalChange(5, metadata, value); metadata = value; return this;}        


    @Override
    public void set(int field, Object value) {
        switch (field) {
            case 0 -> { mf.addOrdinalChange(0, id, (Long)value); id = (Long) value;  }
            case 1 -> { mf.addOrdinalChange(1, firstName, (String)value); firstName = (String) value; }
            case 2 -> { mf.addOrdinalChange(2, lastName, (String)value); lastName = (String) value; }
            case 3 -> { mf.addOrdinalChange(3, age, (Integer)value); age = (Integer) value; }
            case 4 -> { mf.addOrdinalChange(4, departmentName, (String)value); departmentName = (String) value; }
            case 5 -> { mf.addOrdinalChange(5, metadata, (Map<String, List<Long>>)value); metadata = (Map<String, List<Long>>) value; }
        }
    }

    @Override
    public int set(String field, Object value) {
        switch (field) {
            case "id":             mf.addOrdinalChange(0, id, (Long)value); id = (Long) value; return 0;
            case "firstName":      mf.addOrdinalChange(1, firstName, (String)value); firstName = (String) value; return 1;
            case "lastName":       mf.addOrdinalChange(2, lastName, (String)value); lastName = (String) value; return 2;
            case "age":            mf.addOrdinalChange(3, age, (Integer)value); age = (Integer) value; return 3;
            case "departmentName": mf.addOrdinalChange(4, departmentName, (String)value); departmentName = (String) value; return 4;
            case "metadata":       mf.addOrdinalChange(5, metadata, (Map<String, List<Long>>)value); metadata = (Map<String, List<Long>>) value; return 5;
        }
        // unknown field, could throw or ignore, here we choose to ignore and return -1 to indicate no field was set
        return -1;
    }

}