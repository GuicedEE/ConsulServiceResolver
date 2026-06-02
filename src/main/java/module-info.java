import com.guicedee.consul.resolver.ConsulServiceResolverProvider;
import com.guicedee.vertx.servicediscovery.IServiceResolverProvider;

module com.guicedee.consul.resolver {

    exports com.guicedee.consul.resolver;

    requires transitive com.guicedee.consul;
    requires transitive com.guicedee.vertx.servicediscovery;
    requires transitive io.vertx.core;
    requires transitive io.vertx.consul.client;
    requires io.vertx.serviceresolver;
    requires static lombok;

    provides IServiceResolverProvider with ConsulServiceResolverProvider;

    opens com.guicedee.consul.resolver to com.google.guice;
}

