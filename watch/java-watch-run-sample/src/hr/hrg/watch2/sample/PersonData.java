package hr.hrg.watch2.sample;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Simple Jackson-annotated POJO used by {@link DataProcessor}.
 * Edit fields here to test that Jackson picks up changes after a hot-reload.
 */
public class PersonData {

    @JsonProperty("name")
    private final String name;

    @JsonProperty("age")
    private final int age;

    @JsonProperty("skills")
    private final List<String> skills;

    // Jackson requires a no-arg constructor for deserialization
    public PersonData() {
        this("", 0, List.of());
    }

    public PersonData(String name, int age, List<String> skills) {
        this.name   = name;
        this.age    = age;
        this.skills = skills;
    }

    public String       getName()   { return name;   }
    public int          getAge()    { return age;    }
    public List<String> getSkills() { return skills; }

    @Override
    public String toString() {
        return "PersonData{name='" + name + "', age=" + age + ", skills=" + skills + '}';
    }
}
