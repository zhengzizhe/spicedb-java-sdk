package com.authx.sdk.e2e;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Testcontainers-backed SpiceDB server for real SDK end-to-end tests.
 */
final class SpiceDbTestServer implements AutoCloseable {

    static final String PRESHARED_KEY = "testkey";

    private static final String IMAGE = "authzed/spicedb:v1.51.1";
    private static final int GRPC_PORT = 50051;
    private static final String REQUIRED_PROPERTY = "authx.spicedb.e2e.required";

    private final GenericContainer<?> container;

    private SpiceDbTestServer(GenericContainer<?> container) {
        this.container = container;
    }

    static SpiceDbTestServer start() {
        try {
            GenericContainer<?> container = new GenericContainer<>(IMAGE)
                    .withCommand("serve", "--grpc-preshared-key", PRESHARED_KEY)
                    .withExposedPorts(GRPC_PORT)
                    .waitingFor(Wait.forLogMessage(".*grpc server started serving.*", 1));
            container.start();
            SpiceDbTestServer server = new SpiceDbTestServer(container);
            SpiceDbTestSchema.writeSchema(server.target(), PRESHARED_KEY);
            return server;
        } catch (RuntimeException e) {
            if (Boolean.getBoolean(REQUIRED_PROPERTY)) {
                throw e;
            }
            Assumptions.assumeTrue(false, "Docker/Testcontainers unavailable: " + e.getMessage());
            throw e;
        }
    }

    String target() {
        return container.getHost() + ":" + container.getMappedPort(GRPC_PORT);
    }

    @Override
    public void close() {
        if (container.isRunning()) {
            container.stop();
        }
    }
}
