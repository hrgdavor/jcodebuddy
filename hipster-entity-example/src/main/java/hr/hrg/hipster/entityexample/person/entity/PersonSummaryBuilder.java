package hr.hrg.hipster.entityexample.person.entity;

import java.util.List;
import java.util.Map;

// generated when @View(tracking=false) is used on the PersonSummary interface
public final class PersonSummaryBuilder implements PersonSummary.Write {
    Long id;
    String firstName;
    String lastName;
    Integer age;
    String departmentName;
    Map<String, List<Long>> metadata;

    public PersonSummaryBuilder(PersonSummary source) {
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

    public PersonSummaryBuilder id(Long value){ id = value; return this;}
    public PersonSummaryBuilder firstName(String value){ firstName = value; return this;}
    public PersonSummaryBuilder lastName(String value){ lastName = value; return this;}
    public PersonSummaryBuilder age(Integer value){ age = value; return this;}
    public PersonSummaryBuilder departmentName(String value){ departmentName = value; return this;}
    public PersonSummaryBuilder metadata(Map<String, List<Long>> value){ metadata = value; return this;}


    public int set(String field, Object value){
        switch (field) {
            case "id":  id = (Long) value; return 0;
            case "firstName": firstName = (String) value; return 1;
            case "lastName": lastName = (String) value; return 2;
            case "age": age = (Integer) value; return 3;
            case "departmentName": departmentName = (String) value; return 4;
            case "metadata": metadata = (Map<String, List<Long>>) value; return 5;
        }
        // unknown field, could throw or ignore, here we choose to ignore and return false to indicate no field was set
        return -1;
    }

    public void set(int field, Object value){
        switch (field) {
            case 0 -> id = (Long) value;
            case 1 -> firstName = (String) value;
            case 2 -> lastName = (String) value;
            case 3 -> age = (Integer) value;
            case 4 -> departmentName = (String) value;
            case 5 -> metadata = (Map<String, List<Long>>) value;
        }
    }

}