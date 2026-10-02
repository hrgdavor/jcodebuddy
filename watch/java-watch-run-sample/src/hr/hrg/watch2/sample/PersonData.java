package hr.hrg.watch2.sample;

import java.util.List;

/**
 * The sample's data type, and the class to edit to see a hot reload.
 *
 * <p>It used to be a Jackson-annotated POJO. The demo it served was "Jackson picks up your changes after a hot
 * reload", but that made a `watch/*` module know Jackson — which the boundary forbids (`java-watch*` must not
 * know about Jackson, OpenRewrite or anything else from this workspace; DEC-038's amendment, plan step 3.0s).
 * The demo does not need Jackson to make its point: what it demonstrates is that <strong>editing this file is
 * visible in the output without a restart</strong>, and hand-written formatting shows that just as well.</p>
 */
public class PersonData {

    private final String name;
    private final int age;
    private final List<String> skills;

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
