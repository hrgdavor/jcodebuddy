package demo.project;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The console app the runner invokes after every reload: one Jackson JSON line describing a calculation.
 *
 * <p>The {@code ObjectMapper} comes from the parent classloader (it is a runner dependency), while
 * {@link Calculation} comes from the project classloader — the reload's whole point. Jackson serializes it by
 * introspecting the {@code Class} object it is handed, so it does not matter which loader defined it.</p>
 */
public final class App {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        Calculation calculation = new Calculation(6, 7);

        // LinkedHashMap, not Map.of: the printed key order is part of what a person reads in the console.
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("calculation", calculation);
        document.put("total", calculation.total());

        System.out.println(JSON.writeValueAsString(document));
    }
}
