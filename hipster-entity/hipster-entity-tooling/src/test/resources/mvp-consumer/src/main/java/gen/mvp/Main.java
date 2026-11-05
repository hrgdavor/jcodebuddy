package gen.mvp;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        PersonSummary view = PersonSummary_.META.create(new Object[]{1L, "Ada", "Lovelace"});
        if (!"Ada".equals(view.firstName())) {
            throw new AssertionError("META.create returned " + view.firstName());
        }
        System.out.println("mvp consumer: " + view.firstName() + " " + view.lastName());
    }
}
