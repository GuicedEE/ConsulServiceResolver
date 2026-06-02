package com.guicedee.consul.resolver;

import com.guicedee.consul.ConsulPreStartup;
import com.guicedee.consul.ConsulOptions;
import com.guicedee.vertx.servicediscovery.IServiceResolverProvider;
import com.guicedee.vertx.servicediscovery.ServiceResolverOptions;
import com.guicedee.vertx.spi.VertXPreStartup;
import io.vertx.core.net.AddressResolver;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.serviceresolver.ServiceAddress;
import lombok.extern.log4j.Log4j2;

/**
 * SPI provider that creates Consul-based {@link AddressResolver} instances.
 * <p>
 * When {@code @ServiceResolverOptions(type = "consul")} is declared, this provider
 * is invoked to create a resolver that queries Consul's health/catalog API for
 * healthy service instances.
 */
@Log4j2
public class ConsulServiceResolverProvider implements IServiceResolverProvider<ConsulServiceResolverProvider>
{
    @Override
    public String type()
    {
        return "consul";
    }

    @Override
    public AddressResolver<ServiceAddress> create(String name, ServiceResolverOptions options)
    {
        log.info("🚀 Creating Consul address resolver '{}'", name);

        String host = options.consulHost();
        int port = options.consulPort();
        String token = options.consulToken();
        String datacenter = options.consulDatacenter();
        boolean passingOnly = options.consulPassingOnly();

        // If consul options are empty, try to find a matching named ConsulOptions
        if (host.equals("localhost") && token.isEmpty())
        {
            ConsulOptions namedOpts = ConsulPreStartup.getNamedConsulOptions().get(name);
            if (namedOpts == null)
            {
                namedOpts = ConsulPreStartup.getNamedConsulOptions().get("default");
            }
            if (namedOpts != null)
            {
                host = namedOpts.host();
                port = namedOpts.port();
                token = namedOpts.token();
                datacenter = namedOpts.datacenter();
            }
        }

        ConsulClientOptions clientOptions = new ConsulClientOptions()
                .setHost(host)
                .setPort(port);
        if (!token.isEmpty()) clientOptions.setAclToken(token);
        if (!datacenter.isEmpty()) clientOptions.setDc(datacenter);

        ConsulClient client = ConsulClient.create(VertXPreStartup.getVertx(), clientOptions);

        ConsulAddressResolver resolver = new ConsulAddressResolver(client, passingOnly);
        log.info("✅ Consul address resolver '{}' created (host={}:{}, passingOnly={})", name, host, port, passingOnly);
        return resolver;
    }

    @Override
    public Integer sortOrder()
    {
        return 0;
    }
}

