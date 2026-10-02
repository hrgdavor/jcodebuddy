package hr.hrg.watch2.sample;

import java.util.List;

/**
 * A class to edit while {@code java-watch-run} is watching: change a value or the formatting below and the
 * next run shows it, with no restart.
 *
 * <p>This used to serialise a Jackson-annotated POJO and read it back. That demo made a `watch/*` module know
 * Jackson, which the boundary forbids — `java-watch*` must not know about Jackson, OpenRewrite or anything else
 * from this workspace (DEC-038's amendment, plan step 3.0s) — and it demonstrated nothing about the watcher: a
 * hand-written rendering proves that <em>editing this file changes the output</em> exactly as well, and the
 * sample's dependency list is then honest about what it needs (the JDK and `java-watch-run`).</p>
 */
public class DataProcessor {

    /**
     * Formats a sample person. Edit the values or the layout and watch the output change.
     */
    public static String process() {
        PersonData person = new PersonData(
                "Alice Dev",
                30,
                List.of("Java", "Hot-Reload", "ECJ", "Fast Feedback")
        );

        return String.format(
                "Rendered    : %s%nSkills      : %s",
                person,
                String.join(", ", person.getSkills())
        );
    }
}
