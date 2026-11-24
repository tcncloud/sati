package com.tcn.exile;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.handler.PluginBase;
import com.tcn.exile.service.ConfigService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A plugin that can't start (database down) still answers admin jobs, then recovers. */
class ExileClientAdminOnlyTest {

  private static final String SERVER_NAME = "admin-only-test";

  private Server server;
  private ManagedChannel channel;
  private FakeGate gate;
  private ExileClient client;

  @BeforeEach
  void setUp() throws Exception {
    gate = new FakeGate();
    server =
        InProcessServerBuilder.forName(SERVER_NAME)
            .directExecutor()
            .addService(gate.worker)
            .addService(gate.config)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) client.close();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void startsAdminOnlyAndRecoversWhenThePluginAcceptsTheSameConfig() throws Exception {
    var plugin = new DbPlugin();
    client = client(plugin);

    client.pollConfig();
    waitFor(() -> gate.registers.size() == 1, "admin-only register");
    assertEquals(
        List.of(
            WorkType.WORK_TYPE_INFO,
            WorkType.WORK_TYPE_SHUTDOWN,
            WorkType.WORK_TYPE_LOGGING,
            WorkType.WORK_TYPE_DIAGNOSTICS,
            WorkType.WORK_TYPE_LIST_TENANT_LOGS,
            WorkType.WORK_TYPE_SET_LOG_LEVEL),
        gate.registers.get(0).getCapabilitiesList());
    assertFalse(client.pluginEverReady());

    // A non-admin job gets an error result, not a Nack.
    gate.send(job("j1").setListPools(ListPoolsTask.getDefaultInstance()));
    var listPools = gate.awaitResult("j1");
    assertTrue(listPools.hasError());
    assertEquals("Plugin not ready; only admin jobs can run", listPools.getError().getMessage());
    assertTrue(gate.nacks.isEmpty());

    // Admin jobs run.
    gate.send(job("j2").setDiagnostics(DiagnosticsTask.getDefaultInstance()));
    assertTrue(gate.awaitResult("j2").hasDiagnostics());
    gate.send(job("j3").setListTenantLogs(ListTenantLogsTask.getDefaultInstance()));
    assertTrue(gate.awaitResult("j3").hasListTenantLogs());

    // An event that arrives anyway is nacked.
    gate.send(
        WorkItem.newBuilder()
            .setWorkId("e1")
            .setCategory(WorkCategory.WORK_CATEGORY_EVENT)
            .setAgentCall(AgentCall.newBuilder().setCallSid(1)));
    waitFor(() -> gate.nacks.contains("e1"), "event nack");
    assertEquals(0, client.streamStatus().failedTotal(), "not-ready rejections aren't failures");

    // Same config on the next poll; the database is back now.
    var streamLogger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(com.tcn.exile.internal.WorkStreamClient.class);
    var streamLogs =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    streamLogs.start();
    streamLogger.addAppender(streamLogs);
    plugin.dbUp.set(true);
    client.pollConfig();
    waitFor(() -> gate.registers.size() == 2, "re-register");
    streamLogger.detachAppender(streamLogs);
    assertTrue(
        streamLogs.list.stream()
            .noneMatch(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)),
        "the planned re-register is not logged as a stream error: " + streamLogs.list);
    assertEquals(List.of(), gate.registers.get(1).getCapabilitiesList());
    assertTrue(client.pluginEverReady());
    assertEquals(2, plugin.onConfigCalls.get());

    gate.send(job("j4").setListPools(ListPoolsTask.getDefaultInstance()));
    assertTrue(gate.awaitResult("j4").hasListPools());
  }

  @Test
  void aRejectedConfigChangeAfterReadyKeepsTheClientReady() throws Exception {
    var plugin = new DbPlugin();
    plugin.dbUp.set(true);
    client = client(plugin);

    client.pollConfig();
    waitFor(() -> gate.registers.size() == 1, "register");
    assertEquals(List.of(), gate.registers.get(0).getCapabilitiesList());
    assertTrue(client.pluginEverReady());

    plugin.dbUp.set(false);
    gate.configName.set("changed");
    client.pollConfig();
    client.pollConfig();

    assertTrue(client.pluginEverReady(), "gauge must stay 1");
    assertEquals(3, plugin.onConfigCalls.get(), "the rejected change is retried every poll");
    gate.send(job("j1").setListPools(ListPoolsTask.getDefaultInstance()));
    assertTrue(gate.awaitResult("j1").hasListPools());
    assertEquals(1, gate.registers.size(), "no reconnect");
  }

  @Test
  void diagnosticsCustomHasConfigKeysEvenWhenThePluginOverridesDiagnostics() throws Exception {
    var plugin =
        new DbPlugin() {
          @Override
          public JobHandler.DiagnosticsInfo diagnostics() {
            return new JobHandler.DiagnosticsInfo(
                Map.of(), Map.of(), Map.of(), Map.of("plugin.key", "x"));
          }
        };
    plugin.dbUp.set(true);
    client = client(plugin);

    client.pollConfig();
    waitFor(() -> gate.registers.size() == 1, "register");
    gate.send(job("d1").setDiagnostics(DiagnosticsTask.getDefaultInstance()));
    var custom = gate.awaitResult("d1").getDiagnostics().getCustom().getFieldsMap();

    assertEquals("x", custom.get("plugin.key").getStringValue());
    assertEquals("gate.example.com:8443", custom.get("config.api_endpoint").getStringValue());
    assertEquals("prod-cert", custom.get("config.certificate_name").getStringValue());
    assertEquals("Main office", custom.get("config.certificate_description").getStringValue());
  }

  // --- helpers ---

  private ExileClient client(PluginBase plugin) {
    return ExileClient.builder()
        .config(
            ExileConfig.builder()
                .rootCert("unused")
                .publicCert("unused")
                .privateKey("unused")
                .apiHostname("gate.example.com")
                .apiPort(8443)
                .certificateName("prod-cert")
                .certificateDescription("Main office")
                .build())
        .plugin(plugin)
        .testChannel(channel)
        .build();
  }

  private static WorkItem.Builder job(String id) {
    return WorkItem.newBuilder().setWorkId(id).setCategory(WorkCategory.WORK_CATEGORY_JOB);
  }

  private static void waitFor(BooleanSupplier cond, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (!cond.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) fail("timed out waiting for " + what);
      Thread.sleep(10);
    }
  }

  static class DbPlugin extends PluginBase {
    final AtomicBoolean dbUp = new AtomicBoolean(false);
    final AtomicInteger onConfigCalls = new AtomicInteger();

    @Override
    public boolean onConfig(ConfigService.ClientConfiguration config) {
      onConfigCalls.incrementAndGet();
      return dbUp.get();
    }

    @Override
    public List<com.tcn.exile.model.Pool> listPools(String orgId) {
      return List.of();
    }
  }

  static class FakeGate {
    final CopyOnWriteArrayList<Register> registers = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<Result> results = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> nacks = new CopyOnWriteArrayList<>();
    final AtomicReference<String> configName = new AtomicReference<>("main");
    private volatile StreamObserver<WorkResponse> stream;

    void send(WorkItem.Builder item) {
      stream.onNext(WorkResponse.newBuilder().setWorkItem(item).build());
    }

    Result awaitResult(String workId) throws InterruptedException {
      waitFor(() -> results.stream().anyMatch(r -> r.getWorkId().equals(workId)), workId);
      return results.stream().filter(r -> r.getWorkId().equals(workId)).findFirst().orElseThrow();
    }

    final ConfigServiceGrpc.ConfigServiceImplBase config =
        new ConfigServiceGrpc.ConfigServiceImplBase() {
          @Override
          public void getClientConfiguration(
              GetClientConfigurationRequest request,
              StreamObserver<GetClientConfigurationResponse> out) {
            out.onNext(
                GetClientConfigurationResponse.newBuilder()
                    .setOrgId("org-1")
                    .setConfigName(configName.get())
                    .setConfigPayload(
                        Struct.newBuilder()
                            .putFields("db", Value.newBuilder().setStringValue("x").build()))
                    .build());
            out.onCompleted();
          }
        };

    final WorkerServiceGrpc.WorkerServiceImplBase worker =
        new WorkerServiceGrpc.WorkerServiceImplBase() {
          @Override
          public StreamObserver<WorkRequest> workStream(StreamObserver<WorkResponse> out) {
            return new StreamObserver<>() {
              @Override
              public void onNext(WorkRequest req) {
                if (req.hasRegister()) {
                  stream = out;
                  registers.add(req.getRegister());
                  out.onNext(
                      WorkResponse.newBuilder()
                          .setRegistered(
                              Registered.newBuilder()
                                  .setClientId("c-" + registers.size())
                                  .setHeartbeatInterval(
                                      com.google.protobuf.Duration.newBuilder().setSeconds(300))
                                  .setDefaultLease(
                                      com.google.protobuf.Duration.newBuilder().setSeconds(300))
                                  .setMaxInflight(10))
                          .build());
                } else if (req.hasResult()) {
                  results.add(req.getResult());
                } else if (req.hasNack()) {
                  nacks.add(req.getNack().getWorkId());
                }
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                out.onCompleted();
              }
            };
          }
        };
  }
}
