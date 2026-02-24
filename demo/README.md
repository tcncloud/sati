# Sati Demo Application

This `demo` folder is a standalone, barebones application demonstrating how to integrate the Sati Java SDK with a PostgreSQL database.

**This is a template project.** It is completely decoupled from the internal build system of `sati_rewrite`. You may copy this folder to any location and begin building your own custom application.

## Key Features Demonstrated
- **JDBC Backend Integration**: Shows how to extend `JdbcBackendClient` to connect to a PostgreSQL database using connection properties derived from Gate configuration.
- **Service Extension**: Demonstrates overriding default Sati core services (e.g. `AgentService`) to inject custom business logic or side effects.
- **Config Watcher**: Implements the standard configuration reloading pattern to seamlessly re-read Gate credentials when they change.

## Prerequisites
- Java 21+
- Access to GitHub Packages (`https://maven.pkg.github.com/tcncloud/sati`) for resolving the Sati dependency. You will need your `GITHUB_USERNAME` and `GITHUB_TOKEN` set in your environment variables or a `gradle.properties` file.

## Quick Start

1. Copy this `demo` folder to a new location to start your project.
2. Create `workdir/config` in the new folder and place your `.cfg` file there to run locally.
3. Edit the dependency versions in `build.gradle` if necessary.
4. Review `src/main/java/com/example/app/Main.java` and adjust package names/configurations to match your application's needs.
5. Run the application:

```bash
./gradlew run
```

*Note: Sati applications expect configuration provided either via a static config file or dynamic provisioning from a Gate deployment.*
