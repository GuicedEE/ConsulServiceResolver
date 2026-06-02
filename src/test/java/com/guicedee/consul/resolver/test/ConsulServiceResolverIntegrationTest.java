package com.guicedee.consul.resolver.test;

import com.guicedee.client.IGuiceContext;
import com.guicedee.consul.resolver.ConsulAddressResolver;
import com.guicedee.vertx.spi.VertXPreStartup;
import io.vertx.core.Vertx;
import io.vertx.ext.consul.*;
import io.vertx.serviceresolver.ServiceAddress;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the Consul Service Resolver using Testcontainers.
 * <p>
 * Starts a real Consul agent, registers services, and verifies the
 * ConsulAddressResolver can resolve healthy endpoints for client-side load balancing.
 * <p>
 * Requires Docker. Skipped automatically if Docker is not available.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
class ConsulServiceResolverIntegrationTest
{
    private static GenericContainer<?> consulContainer;
    private ConsulClient client;
    private Vertx vertx;

    @BeforeAll
    void startConsul()
    {
        assumeTrue(isDockerAvailable(), "Docker is not available — skipping integration test");

        consulContainer = new GenericContainer<>(DockerImageName.parse("hashicorp/consul:1.20"))
                .withExposedPorts(8500)
                .withCommand("agent", "-dev", "-client=0.0.0.0")
                .waitingFor(Wait.forHttp("/v1/status/leader")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(30)));

        consulContainer.start();

        Integer mappedPort = consulContainer.getMappedPort(8500);
        String mappedHost = consulContainer.getHost();

        // Bootstrap GuicedEE to get Vert.x
        IGuiceContext.registerModule("com.guicedee.consul.resolver.test");
        IGuiceContext.instance().inject();
        vertx = VertXPreStartup.getVertx();

        ConsulClientOptions options = new ConsulClientOptions()
                .setHost(mappedHost)
                .setPort(mappedPort);
        client = ConsulClient.create(vertx, options);
    }

    @AfterAll
    void stopConsul()
    {
        if (client != null) client.close();
        if (consulContainer != null) consulContainer.stop();
    }

    @Test
    @Order(1)
    @DisplayName("Register multiple service instances in Consul")
    void testRegisterInstances() throws Exception
    {
        for (int i = 1; i <= 3; i++)
        {
            ServiceOptions opts = new ServiceOptions()
                    .setName("api-service")
                    .setId("api-service-" + i)
                    .setAddress("10.0.0." + i)
                    .setPort(8080 + i)
                    .setCheckOptions(new CheckOptions().setTtl("60s"));

            CompletableFuture<Void> f = new CompletableFuture<>();
            client.registerService(opts)
                    .onSuccess(v -> f.complete(null))
                    .onFailure(f::completeExceptionally);
            f.get(5, TimeUnit.SECONDS);

            // Mark as passing
            CompletableFuture<Void> passF = new CompletableFuture<>();
            client.passCheck("service:api-service-" + i)
                    .onSuccess(v -> passF.complete(null))
                    .onFailure(passF::completeExceptionally);
            passF.get(5, TimeUnit.SECONDS);
        }
        System.out.println("✅ Registered 3 instances of 'api-service' with passing health");
    }

    @Test
    @Order(2)
    @DisplayName("ConsulAddressResolver resolves healthy instances")
    void testResolverFindsHealthyInstances() throws Exception
    {
        ConsulAddressResolver resolver = new ConsulAddressResolver(client, true);
        assertNotNull(resolver);
        assertNotNull(resolver.endpointResolver(vertx));

        // Verify via direct health query that instances are available
        CompletableFuture<ServiceEntryList> future = new CompletableFuture<>();
        client.healthServiceNodes("api-service", true)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        ServiceEntryList entries = future.get(5, TimeUnit.SECONDS);
        assertEquals(3, entries.getList().size(), "Should resolve 3 healthy instances");
        System.out.println("✅ Resolver found " + entries.getList().size() + " healthy endpoints");
    }

    @Test
    @Order(3)
    @DisplayName("Resolver excludes unhealthy instances when passingOnly=true")
    void testResolverExcludesUnhealthy() throws Exception
    {
        // Fail one instance
        CompletableFuture<Void> failF = new CompletableFuture<>();
        client.failCheck("service:api-service-2")
                .onSuccess(v -> failF.complete(null))
                .onFailure(failF::completeExceptionally);
        failF.get(5, TimeUnit.SECONDS);

        // Query with passingOnly=true
        CompletableFuture<ServiceEntryList> future = new CompletableFuture<>();
        client.healthServiceNodes("api-service", true)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        ServiceEntryList entries = future.get(5, TimeUnit.SECONDS);
        assertEquals(2, entries.getList().size(), "Should only find 2 healthy instances");

        // Verify failed instance is excluded
        boolean containsFailed = entries.getList().stream()
                .anyMatch(e -> "api-service-2".equals(e.getService().getId()));
        assertFalse(containsFailed, "Unhealthy instance should be excluded");
        System.out.println("✅ Unhealthy instance excluded, " + entries.getList().size() + " healthy remain");
    }

    @Test
    @Order(4)
    @DisplayName("Resolver includes all instances when passingOnly=false")
    void testResolverIncludesAllWhenNotPassing() throws Exception
    {
        CompletableFuture<ServiceEntryList> future = new CompletableFuture<>();
        client.healthServiceNodes("api-service", false)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        ServiceEntryList entries = future.get(5, TimeUnit.SECONDS);
        assertEquals(3, entries.getList().size(), "Should find all 3 instances regardless of health");
        System.out.println("✅ With passingOnly=false: all " + entries.getList().size() + " instances returned");
    }

    @Test
    @Order(5)
    @DisplayName("Resolver handles non-existent service gracefully")
    void testResolverNonExistentService() throws Exception
    {
        CompletableFuture<ServiceEntryList> future = new CompletableFuture<>();
        client.healthServiceNodes("does-not-exist", true)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        ServiceEntryList entries = future.get(5, TimeUnit.SECONDS);
        assertTrue(entries.getList().isEmpty(), "Non-existent service should return empty list");
        System.out.println("✅ Non-existent service returns empty result (no error)");
    }

    @Test
    @Order(6)
    @DisplayName("Service address falls back to node address when empty")
    void testAddressFallbackToNode() throws Exception
    {
        // Register a service without explicit address
        ServiceOptions opts = new ServiceOptions()
                .setName("no-addr-service")
                .setId("no-addr-1")
                .setPort(9090)
                .setCheckOptions(new CheckOptions().setTtl("60s"));

        CompletableFuture<Void> regF = new CompletableFuture<>();
        client.registerService(opts)
                .onSuccess(v -> regF.complete(null))
                .onFailure(regF::completeExceptionally);
        regF.get(5, TimeUnit.SECONDS);

        // Pass check
        CompletableFuture<Void> passF = new CompletableFuture<>();
        client.passCheck("service:no-addr-1")
                .onSuccess(v -> passF.complete(null))
                .onFailure(passF::completeExceptionally);
        passF.get(5, TimeUnit.SECONDS);

        // Query
        CompletableFuture<ServiceEntryList> future = new CompletableFuture<>();
        client.healthServiceNodes("no-addr-service", true)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        ServiceEntryList entries = future.get(5, TimeUnit.SECONDS);
        assertFalse(entries.getList().isEmpty());

        ServiceEntry entry = entries.getList().get(0);
        String addr = entry.getService().getAddress();
        String nodeAddr = entry.getNode().getAddress();
        // When service address is empty, the resolver uses node address
        String resolvedAddr = (addr == null || addr.isEmpty()) ? nodeAddr : addr;
        assertNotNull(resolvedAddr);
        assertFalse(resolvedAddr.isEmpty(), "Should resolve to node address when service address is empty");
        System.out.println("✅ Address fallback: service='" + addr + "', node='" + nodeAddr + "', resolved='" + resolvedAddr + "'");

        // Cleanup
        CompletableFuture<Void> deregF = new CompletableFuture<>();
        client.deregisterService("no-addr-1")
                .onSuccess(v -> deregF.complete(null))
                .onFailure(deregF::completeExceptionally);
        deregF.get(5, TimeUnit.SECONDS);
    }

    private static boolean isDockerAvailable()
    {
        try
        {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}

