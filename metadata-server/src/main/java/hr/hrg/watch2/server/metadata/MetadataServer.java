package hr.hrg.watch2.server.metadata;

import hr.hrg.watch2.server.metadata.transport.HttpTransport;
import hr.hrg.watch2.server.metadata.transport.UnixSocketTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class MetadataServer {
    private static final Logger log = LoggerFactory.getLogger(MetadataServer.class);

    private final MetadataProvider provider;
    private final Integer httpPort;
    private final String unixJsonPath;
    private final String unixForyPath;

    private HttpTransport httpTransport;
    private UnixSocketTransport unixJsonTransport;
    private UnixSocketTransport unixForyTransport;

    private MetadataServer(Builder builder) {
        this.provider = builder.provider;
        this.httpPort = builder.httpPort;
        this.unixJsonPath = builder.unixJsonPath;
        this.unixForyPath = builder.unixForyPath;
    }

    public static Builder builder(MetadataProvider provider) {
        return new Builder(provider);
    }

    public void start() throws IOException {
        if (httpPort != null) {
            httpTransport = new HttpTransport(httpPort, provider);
            httpTransport.start();
        }
        if (unixJsonPath != null && !unixJsonPath.isEmpty()) {
            unixJsonTransport = new UnixSocketTransport(Path.of(unixJsonPath), UnixSocketTransport.Protocol.JSON, provider);
            unixJsonTransport.start();
        }
        if (unixForyPath != null && !unixForyPath.isEmpty()) {
            unixForyTransport = new UnixSocketTransport(Path.of(unixForyPath), UnixSocketTransport.Protocol.FORY, provider);
            unixForyTransport.start();
        }
        log.info("MetadataServer started");
    }

    public void stop() {
        if (httpTransport != null) httpTransport.stop();
        if (unixJsonTransport != null) unixJsonTransport.stop();
        if (unixForyTransport != null) unixForyTransport.stop();
        log.info("MetadataServer stopped");
    }

    public static class Builder {
        private final MetadataProvider provider;
        private Integer httpPort;
        private String unixJsonPath;
        private String unixForyPath;

        public Builder(MetadataProvider provider) {
            this.provider = provider;
        }

        public Builder httpPort(int port) {
            this.httpPort = port;
            return this;
        }

        public Builder unixJsonPath(String path) {
            this.unixJsonPath = path;
            return this;
        }

        public Builder unixForyPath(String path) {
            this.unixForyPath = path;
            return this;
        }

        public MetadataServer build() {
            return new MetadataServer(this);
        }
    }
}
