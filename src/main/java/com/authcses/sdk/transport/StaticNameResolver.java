package com.authcses.sdk.transport;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

/**
 * gRPC NameResolver that resolves a static list of addresses.
 * Used when business code provides multiple SpiceDB endpoints via targets().
 *
 * <pre>
 * .targets("10.0.1.100:50051", "10.0.1.101:50051", "10.0.1.102:50051")
 * → StaticNameResolver registers all 3 as EquivalentAddressGroups
 * → gRPC round_robin picks one per request
 * </pre>
 */
public class StaticNameResolver extends NameResolver {

    private final List<String> addresses;

    public StaticNameResolver(List<String> addresses) {
        this.addresses = addresses;
    }

    @Override
    public String getServiceAuthority() {
        return addresses.getFirst();
    }

    @Override
    public void start(Listener2 listener) {
        List<EquivalentAddressGroup> groups = addresses.stream()
                .map(addr -> {
                    String[] parts = addr.split(":");
                    String host = parts[0];
                    int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 50051;
                    return new EquivalentAddressGroup(new InetSocketAddress(host, port));
                })
                .toList();

        listener.onResult(ResolutionResult.newBuilder()
                .setAddresses(groups)
                .build());
    }

    @Override
    public void shutdown() {}

    /**
     * Provider that registers "static" scheme for gRPC channel builder.
     */
    public static class Provider extends NameResolverProvider {

        private final List<String> addresses;

        public Provider(List<String> addresses) {
            this.addresses = addresses;
        }

        @Override
        public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
            if ("static".equals(targetUri.getScheme())) {
                return new StaticNameResolver(addresses);
            }
            return null;
        }

        @Override
        protected boolean isAvailable() { return true; }

        @Override
        protected int priority() { return 10; }

        @Override
        public String getDefaultScheme() { return "static"; }
    }
}
