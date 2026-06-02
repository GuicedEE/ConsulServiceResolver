open module com.guicedee.consul.resolver.test {
    requires transitive com.guicedee.consul.resolver;
    requires com.guicedee.consul;
    requires com.guicedee.guicedinjection;
    requires com.google.guice;
    requires io.vertx.core;
    requires io.vertx.consul.client;
    requires io.vertx.serviceresolver;
    requires org.testcontainers;

    requires org.junit.jupiter;
}

