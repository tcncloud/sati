package com.example.app;

import com.tcn.sati.SatiApp;
import com.tcn.sati.config.BackendType;
import com.tcn.sati.config.SatiConfig;
import com.tcn.sati.core.tenant.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

// Multi-tenant demo — api-style deployment with org-scoped routes.
//
// Quick start:
//   ./gradlew :demo:runMultiTenant
//   Dashboard: http://localhost:8080/
//   Swagger:   http://localhost:8080/swagger
//   Tenants:   http://localhost:8080/api/orgs
//   Agents:    http://localhost:8080/api/orgs/acme-corp/agents
//
// No Gate config = gRPC-dependent routes return empty responses.
// Pool and backend routes work via the REST stub.
public class MultiTenantMain {
        private static final Logger log = LoggerFactory.getLogger(MultiTenantMain.class);

        public static void main(String[] args) {
                log.info("Starting Multi-Tenant Demo...");

                // Dummy configs for two tenants (no Gate certs = gRPC disabled).
                // In production, these would come from an external service or config file.
                SatiConfig acmeConfig = SatiConfig.builder()
                                .org("acme-corp")
                                .tenant("acme-corp")
                                .backendUrl("http://localhost:9001")
                                .backendUser("acme-api")
                                .backendPassword("acme-secret")
                                .build();

                SatiConfig globexConfig = SatiConfig.builder()
                                .org("globex-inc")
                                .tenant("globex-inc")
                                .backendUrl("http://localhost:9002")
                                .backendUser("globex-api")
                                .backendPassword("globex-secret")
                                .build();

                // Tenant discovery returns the list of known tenants.
                // TenantManager polls this periodically and creates/destroys tenants as needed.
                List<TenantManager.TenantConfig> tenants = List.of(
                                new TenantManager.TenantConfig("acme-corp", acmeConfig, BackendType.REST),
                                new TenantManager.TenantConfig("globex-inc", globexConfig, BackendType.REST));

                SatiApp.builder()
                                .backendType(BackendType.REST)
                                .multiTenant(true)
                                .tenantDiscovery(() -> tenants)
                                .discoveryIntervalSeconds(60)
                                .appName("Multi-Tenant Demo")
                                .appVersion("demo")
                                .start(8080);
        }
}
