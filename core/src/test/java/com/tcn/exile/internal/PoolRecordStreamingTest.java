package com.tcn.exile.internal;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.tcnapi.exile.gate.v3.*;
import com.tcn.exile.ExileConfig;
import com.tcn.exile.handler.EventHandler;
import com.tcn.exile.handler.JobHandler;
import com.tcn.exile.handler.RecordSink;
import com.tcn.exile.model.DataRecord;
import com.tcn.exile.model.Page;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PoolRecordStreamingTest {

  private static final String SERVER_NAME = "pool-record-streaming-test";

  private Server server;
  private ManagedChannel channel;
  private Gate gate;
  private WorkStreamClient client;

  @BeforeEach
  void setUp() throws Exception {
    gate = new Gate();
    server = InProcessServerBuilder.forName(SERVER_NAME).addService(gate).build().start();
    channel = InProcessChannelBuilder.forName(SERVER_NAME).build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) client.close();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void largePoolArrivesInBoundedBatchesWithOneFinal() throws Exception {
    int n = 3000;
    var filler = "x".repeat(1000);
    run(
        new JobHandler() {
          @Override
          public boolean streamPoolRecords(String orgId, String poolId, RecordSink sink)
              throws Exception {
            for (int i = 0; i < n; i++) {
              sink.send(new DataRecord(poolId, "r" + i, Map.of("filler", filler)));
            }
            return true;
          }
        });

    var results = gate.results;
    assertTrue(results.size() > 1, "expected several batches, got " + results.size());
    for (int i = 0; i < results.size(); i++) {
      var r = results.get(i);
      assertEquals(i == results.size() - 1, r.getFinal(), "only the last result is final");
      // the cap counts record bytes; per-record framing adds a few bytes each
      assertTrue(
          r.getGetPoolRecords().getSerializedSize()
              <= WorkStreamClient.MAX_RESULT_BATCH_BYTES * 11 / 10,
          "batch " + i + " is " + r.getGetPoolRecords().getSerializedSize() + " bytes");
    }
    var ids =
        results.stream()
            .flatMap(r -> r.getGetPoolRecords().getRecordsList().stream())
            .map(build.buf.gen.tcnapi.exile.gate.v3.Record::getRecordId)
            .toList();
    assertEquals(n, ids.size());
    for (int i = 0; i < n; i++) {
      assertEquals("r" + i, ids.get(i));
    }
  }

  @Test
  void emptyPoolSendsOneEmptyFinal() throws Exception {
    run(
        new JobHandler() {
          @Override
          public boolean streamPoolRecords(String orgId, String poolId, RecordSink sink) {
            return true;
          }
        });

    assertEquals(1, gate.results.size());
    assertTrue(gate.results.get(0).getFinal());
    assertEquals(0, gate.results.get(0).getGetPoolRecords().getRecordsCount());
  }

  @Test
  void pageHandlerStillSendsOneResultWithToken() throws Exception {
    run(
        new JobHandler() {
          @Override
          public Page<DataRecord> getPoolRecords(
              String orgId, String poolId, String pageToken, int pageSize) {
            return new Page<>(
                List.of(
                    new DataRecord(poolId, "a", Map.of()), new DataRecord(poolId, "b", Map.of())),
                "next");
          }
        });

    assertEquals(1, gate.results.size());
    var r = gate.results.get(0);
    assertTrue(r.getFinal());
    assertEquals(2, r.getGetPoolRecords().getRecordsCount());
    assertEquals("next", r.getGetPoolRecords().getNextPageToken());
  }

  @Test
  void failureMidStreamEndsWithErrorFinal() throws Exception {
    var filler = "x".repeat(1000);
    run(
        new JobHandler() {
          @Override
          public boolean streamPoolRecords(String orgId, String poolId, RecordSink sink)
              throws Exception {
            for (int i = 0; i < 2000; i++) {
              sink.send(new DataRecord(poolId, "r" + i, Map.of("filler", filler)));
            }
            throw new IllegalStateException("db went away");
          }
        });

    var results = gate.results;
    assertTrue(results.size() > 1, "batches before the failure should have been sent");
    var last = results.get(results.size() - 1);
    assertTrue(last.getFinal());
    assertTrue(last.hasError());
    assertEquals("db went away", last.getError().getMessage());
    for (int i = 0; i < results.size() - 1; i++) {
      assertFalse(results.get(i).getFinal());
    }
  }

  @Test
  void streamLostMidPoolNacksJobOnNextStream() throws Exception {
    gate.dropStreamOnFirstBatch = true;
    var filler = "x".repeat(1000);
    client =
        new WorkStreamClient(
            dummyConfig(),
            new JobHandler() {
              @Override
              public boolean streamPoolRecords(String orgId, String poolId, RecordSink sink)
                  throws Exception {
                for (int i = 0; i < 3000; i++) {
                  sink.send(new DataRecord(poolId, "r" + i, Map.of("filler", filler)));
                }
                return true;
              }
            },
            new EventHandler() {},
            "test",
            "1.0",
            10,
            List.of());
    client.start(channel);
    assertTrue(gate.registered.await(5, TimeUnit.SECONDS), "should register");
    gate.sendPoolRecordsJob("job-1", "POOL_1");

    assertTrue(
        gate.nackReceived.await(20, TimeUnit.SECONDS), "job should be nacked after reconnect");
    assertEquals("job-1", gate.nacks.get(0).getWorkId());
    assertTrue(gate.streams.get() >= 2, "nack should arrive on a new stream");
    assertTrue(
        gate.results.stream().noneMatch(Result::getFinal),
        "no final result, error or otherwise, after the stream was lost");
  }

  private void run(JobHandler handler) throws Exception {
    client =
        new WorkStreamClient(
            dummyConfig(), handler, new EventHandler() {}, "test", "1.0", 10, List.of());
    client.start(channel);
    assertTrue(gate.registered.await(5, TimeUnit.SECONDS), "should register");
    gate.sendPoolRecordsJob("job-1", "POOL_1");
    assertTrue(gate.finalReceived.await(10, TimeUnit.SECONDS), "should receive a final result");
  }

  private static ExileConfig dummyConfig() {
    return ExileConfig.builder()
        .rootCert("unused")
        .publicCert("unused")
        .privateKey("unused")
        .apiHostname("in-process")
        .apiPort(0)
        .build();
  }

  static class Gate extends WorkerServiceGrpc.WorkerServiceImplBase {
    final CopyOnWriteArrayList<Result> results = new CopyOnWriteArrayList<>();
    final CountDownLatch registered = new CountDownLatch(1);
    final CountDownLatch finalReceived = new CountDownLatch(1);
    final CopyOnWriteArrayList<Nack> nacks = new CopyOnWriteArrayList<>();
    final CountDownLatch nackReceived = new CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicInteger streams =
        new java.util.concurrent.atomic.AtomicInteger();
    volatile boolean dropStreamOnFirstBatch = false;
    private volatile StreamObserver<WorkResponse> out;

    void sendPoolRecordsJob(String workId, String poolId) {
      synchronized (this) {
        out.onNext(
            WorkResponse.newBuilder()
                .setWorkItem(
                    WorkItem.newBuilder()
                        .setWorkId(workId)
                        .setCategory(WorkCategory.WORK_CATEGORY_JOB)
                        .setAttempt(1)
                        .setGetPoolRecords(GetPoolRecordsTask.newBuilder().setPoolId(poolId)))
                .build());
      }
    }

    @Override
    public StreamObserver<WorkRequest> workStream(StreamObserver<WorkResponse> responseObserver) {
      out = responseObserver;
      int stream = streams.incrementAndGet();
      return new StreamObserver<>() {
        @Override
        public void onNext(WorkRequest request) {
          if (request.hasRegister()) {
            synchronized (Gate.this) {
              responseObserver.onNext(
                  WorkResponse.newBuilder()
                      .setRegistered(
                          Registered.newBuilder()
                              .setClientId("test-client")
                              .setHeartbeatInterval(
                                  com.google.protobuf.Duration.newBuilder().setSeconds(300))
                              .setDefaultLease(
                                  com.google.protobuf.Duration.newBuilder().setSeconds(300))
                              .setMaxInflight(100))
                      .build());
            }
            registered.countDown();
          } else if (request.hasNack()) {
            nacks.add(request.getNack());
            nackReceived.countDown();
          } else if (request.hasResult()) {
            if (dropStreamOnFirstBatch && stream == 1) {
              synchronized (Gate.this) {
                responseObserver.onError(io.grpc.Status.UNAVAILABLE.asRuntimeException());
              }
              return;
            }
            results.add(request.getResult());
            if (request.getResult().getFinal()) {
              finalReceived.countDown();
            }
          }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }
  }
}
