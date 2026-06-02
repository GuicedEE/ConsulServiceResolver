package com.guicedee.consul.resolver;

import io.vertx.core.Vertx;
import io.vertx.core.net.AddressResolver;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ServiceEntryList;
import io.vertx.serviceresolver.ServiceAddress;
import io.vertx.core.spi.endpoint.EndpointResolver;
import lombok.extern.log4j.Log4j2;

/**
 * Vert.x {@link AddressResolver} implementation that resolves service addresses
 * by querying Consul's health API for healthy service instances.
 * <p>
 * Integrates with Vert.x HTTP/Web clients via {@code HttpClientBuilder.withAddressResolver(resolver)}.
 */
@Log4j2
public class ConsulAddressResolver implements AddressResolver<ServiceAddress>
{
    private final ConsulClient consulClient;
    private final boolean passingOnly;

    public ConsulAddressResolver(ConsulClient consulClient, boolean passingOnly)
    {
        this.consulClient = consulClient;
        this.passingOnly = passingOnly;
    }

    @Override
    public EndpointResolver<ServiceAddress, ?, ?, ?> endpointResolver(Vertx vertx)
    {
        return new ConsulEndpointResolver<>(vertx, consulClient, passingOnly);
    }
}
