package hr.hrg.watch2.agent;

import hr.hrg.watch2.builder.api.GenerateBuilder;

@GenerateBuilder
public class TestDiscovery {

    @SuppressWarnings("unused")
    private String name;

    @SuppressWarnings("unused")
    private int age;

    public static TestDiscoveryBuilder builder() {
        return new TestDiscoveryBuilder();
    }

    public static class TestDiscoveryBuilder {

        private String name;

        private int age;

        public TestDiscoveryBuilder name(String name) {
            this.name = name;
            return this;
        }

        public TestDiscoveryBuilder age(int age) {
            this.age = age;
            return this;
        }

        public TestDiscovery build() {
            return new TestDiscovery();
        }
    }
}
