# Sati Demo Application

This `demo` folder is a standalone, barebones application demonstrating how to integrate the Sati Java SDK with a PostgreSQL database.

**This is a template project.** It is completely decoupled from the internal build system of `sati_rewrite`. You may copy this folder to any location and begin building your own custom application.

## Prerequisites

- **Java 21+**
- **Gate Configuration** — A Base64-encoded config file (`com.tcn.exiles.sati.config.cfg`) provided during onboarding.
- **GitHub Packages Access** — `GITHUB_USERNAME` and `GITHUB_TOKEN` set in your environment variables or a `gradle.properties` file for resolving the Sati dependency from `https://maven.pkg.github.com/tcncloud/sati`.

## Key Features Demonstrated
- **JDBC Backend Integration**: Shows how to extend `JdbcBackendClient` to connect to a PostgreSQL database using connection properties derived from Gate configuration.
- **Service Extension**: Demonstrates overriding default Sati core services (e.g. `AgentService`) to inject custom business logic or side effects.
- **Config Watcher**: Implements the standard configuration reloading pattern to seamlessly re-read Gate credentials when they change.

## Quick Start

1. Copy this `demo` folder to a new location to start your project.
2. Create the config directory and place your Gate config file:
   ```bash
   mkdir -p workdir/config
   echo "YOUR_BASE64_GATE_CONFIG_STRING" > workdir/config/com.tcn.exiles.sati.config.cfg
   ```
3. Edit the dependency versions in `build.gradle` if necessary.
4. Review `src/main/java/com/example/app/Main.java` and adjust package names/configurations to match your application's needs.
5. Run the application:
   ```bash
   ./gradlew run
   ```

## Client & Database Connection

Sati uses the SDK to bridge Gate and a PostgreSQL database.

* The SDK manages secure gRPC connections to receive jobs and events.
* Gate dynamically streams database credentials to the app at runtime. The app automatically configures and rotates its connection pool (HikariCP) without requiring manual secret management.

### Backend Integration
You integrate your app by overriding SQL template methods in `AppBackendClient.java`. Sati handles all routing, parameter binding, and response parsing. You simply provide the SQL that calls your local PostgreSQL stored procedures:

```java
@Override
protected String getExecuteLogicSql() {
    // Sati will use this SQL and pass the incoming JSON payload as the '?' parameter
    return "SELECT * FROM execute_logic_procedure(?::jsonb)"; 
}
```

## Interfaces & Documentation

Once the app is running, you can access the following local development tools:

* **App Dashboard**: [http://localhost:8080/](http://localhost:8080/)  
  Displays operational state, streaming connections, and configurations.
* **Swagger UI**: [http://localhost:8080/swagger](http://localhost:8080/swagger)  
  Interactive OpenAPI documentation for all available REST / RPC endpoints.

## Sati Repository Reference

This project is built using the Sati SDK. For more details on the architecture, setup instructions, and available overrides, refer to the main repository:

* **Repository**: [https://github.com/tcncloud/sati](https://github.com/tcncloud/sati/tree/rewrite_poc)
