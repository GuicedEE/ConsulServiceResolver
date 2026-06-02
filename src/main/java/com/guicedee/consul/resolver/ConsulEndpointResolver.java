package com.guicedee.consul.resolver;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.Address;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.spi.endpoint.EndpointBuilder;
import io.vertx.core.spi.endpoint.EndpointResolver;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ServiceEntry;
import io.vertx.ext.consul.ServiceEntryList;
import io.vertx.serviceresolver.ServiceAddress;
import lombok.extern.log4j.Log4j2;

/**
 * Vert.x {@link EndpointResolver} that queries Consul health API for healthy service
 * instances and builds endpoint lists for client-side load balancing.
 */
@Log4j2
public class ConsulEndpointResolver<B> implements EndpointResolver<ServiceAddress, SocketAddress, ServiceEntryList, B>
{
    private final Vertx vertx;
    private final ConsulClient client;
    private final boolean passingOnly;

    public ConsulEndpointResolver(Vertx vertx, ConsulClient client, boolean passingOnly)
    {
        this.vertx = vertx;
        this.client = client;
        this.passingOnly = passingOnly;
    }

    @Override
    public ServiceAddress tryCast(Address address)
    {
        if (address instanceof ServiceAddress sa)
        {
            return sa;
        }
        return null;
    }

    @Override
    public SocketAddress addressOf(SocketAddress server)
    {
        return server;
    }

    @Override
    public Future<ServiceEntryList> resolve(ServiceAddress address, EndpointBuilder<B, SocketAddress> builder)
    {
        String serviceName = address.name();
        log.debug("🔍 Resolving service '{}' from Consul (passingOnly={})", serviceName, passingOnly);

        return client.healthServiceNodes(serviceName, passingOnly)
                .map(entries -> {
                    if (entries.getList() != null)
                    {
                        for (ServiceEntry entry : entries.getList())
                        {
                            String addr = entry.getService().getAddress();
                            if (addr == null || addr.isEmpty())
                            {
                                addr = entry.getNode().getAddress();
                            }
                            int port = entry.getService().getPort();
                            SocketAddress socketAddress = SocketAddress.inetSocketAddress(port, addr);
                            builder.addServer(socketAddress);
                        }
                        log.debug("✅ Resolved {} instance(s) for service '{}'", entries.getList().size(), serviceName);
                    }
                    return entries;
                });
    }

    @Override
    public B endpoint(ServiceEntryList state)
    {
        // The builder.build() is called by the framework after resolve completes
        return null;
    }

    @Override
    public boolean isValid(ServiceEntryList state)
    {
        return state != null && state.getList() != null && !state.getList().isEmpty();
    }

    @Override
    public void dispose(ServiceEntryList state)
    {
        // No cleanup needed for state
    }

    @Override
    public void close()
    {
        // ConsulClient lifecycle managed by the Consul module
    }
}
