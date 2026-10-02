package hr.hrg.watch2.agent;

import hr.hrg.watch2.builder.api.GenerateBuilder;

// @watch: src/main/java/hr/hrg/watch2/agent/TestDiscovery.java
@GenerateBuilder
public class TestStateSync {

    @SuppressWarnings("unused")
    private String data;

    public static TestStateSyncBuilder builder() {
        return new TestStateSyncBuilder();
    }

    public static class TestStateSyncBuilder {

        private String data;

        public TestStateSyncBuilder data(String data) {
            this.data = data;
            return this;
        }

        public TestStateSync build() {
            return new TestStateSync();
        }
    }
}
