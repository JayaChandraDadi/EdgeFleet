package io.edgefleet.sim;

import io.edgefleet.config.Configs;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

/**
 * Runs a fleet of simulated edge routers: claims every device in a tokens file
 * (the response of POST /api/inventory), keeps their control sessions up
 * (reconnecting with backoff, as real devices do after a controller restart), and
 * optionally makes some of them drift by editing their config locally.
 */
@Command(name = "device-simulator", mixinStandardHelpOptions = true)
public final class DeviceSimulator implements Callable<Integer> {

    @Option(names = "--bootstrap", defaultValue = "localhost:50051")
    String bootstrap;

    @Option(names = "--ca", required = true, description = "controller CA certificate (the devices' trust anchor)")
    Path caCert;

    @Option(names = "--tokens", required = true, description = "JSON from POST /api/inventory")
    Path tokens;

    @Option(names = "--model", defaultValue = "C8300")
    String model;

    @Option(names = "--duration-sec", defaultValue = "600")
    int durationSec;

    @Option(names = "--claim-concurrency", defaultValue = "64")
    int claimConcurrency;

    @Option(names = "--heartbeat-ms", defaultValue = "5000")
    long heartbeatMs;

    @Option(names = "--apply-delay-ms", defaultValue = "50,250", split = ",")
    int[] applyDelayMs;

    @Option(names = "--random-failure-pct", defaultValue = "0")
    double randomFailurePct;

    @Option(names = "--drift", defaultValue = "0", description = "devices to hand-edit after --drift-after-sec")
    int drift;

    @Option(names = "--drift-after-sec", defaultValue = "60")
    int driftAfterSec;

    @Option(names = "--summary-json")
    Path summaryJson;

    public static void main(String[] args) {
        System.exit(new CommandLine(new DeviceSimulator()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(caCert)));
        JsonNode list = Configs.parse(Files.readString(tokens));
        ScheduledExecutorService timers = Executors.newScheduledThreadPool(4);
        DeviceAgent.Behavior behavior = new DeviceAgent.Behavior(applyDelayMs, heartbeatMs, 1300, randomFailurePct);

        List<DeviceAgent> agents = new ArrayList<>();
        for (JsonNode t : list.values()) {
            agents.add(new DeviceAgent(t.get("serial").asString(), model, t.get("bootstrapToken").asString(), ca,
                    bootstrap, behavior, timers));
        }
        System.out.printf("claiming %d devices via %s%n", agents.size(), bootstrap);

        long t0 = System.nanoTime();
        long[] claimMs = new long[agents.size()];
        AtomicInteger failed = new AtomicInteger();
        Semaphore permits = new Semaphore(claimConcurrency);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < agents.size(); i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        long s = System.nanoTime();
                        claimWithRetry(agents.get(idx));
                        claimMs[idx] = (System.nanoTime() - s) / 1_000_000;
                        agents.get(idx).connect();
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        claimMs[idx] = -1;
                        System.err.println(agents.get(idx).serial() + ": " + e.getMessage());
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        }
        double onboardSec = (System.nanoTime() - t0) / 1e9;
        long[] ok = Arrays.stream(claimMs).filter(v -> v >= 0).sorted().toArray();
        System.out.printf(Locale.ROOT, "onboarded %d devices in %.1fs (%d failed); claim p50 %dms p99 %dms%n",
                ok.length, onboardSec, failed.get(), pct(ok, 50), pct(ok, 99));

        // Supervise: reconnect dropped sessions with exponential backoff and jitter. Without
        // jitter every device retries in lockstep and a recovering controller is hit by the
        // whole fleet at once. Backoff resets only after a session has stayed up 30 s.
        long[] nextAttempt = new long[agents.size()];
        long[] backoff = new long[agents.size()];
        long[] openSince = new long[agents.size()];
        Arrays.fill(backoff, 1_000);
        AtomicInteger reconnects = new AtomicInteger();
        timers.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
            for (int i = 0; i < agents.size(); i++) {
                DeviceAgent a = agents.get(i);
                if (a.identity() == null) {
                    continue;
                }
                if (a.sessionOpen()) {
                    if (openSince[i] == 0) {
                        openSince[i] = now;
                    } else if (now - openSince[i] > 30_000) {
                        backoff[i] = 1_000;
                    }
                    continue;
                }
                openSince[i] = 0;
                if (now < nextAttempt[i]) {
                    continue;
                }
                if (a.sessionError() instanceof io.grpc.StatusRuntimeException sre
                        && sre.getStatus().getCode() == io.grpc.Status.Code.PERMISSION_DENIED) {
                    continue; // decommissioned: stay down
                }
                a.connect();
                reconnects.incrementAndGet();
                nextAttempt[i] = now + backoff[i] / 2 + rnd.nextLong(backoff[i] / 2 + 1);
                backoff[i] = Math.min(30_000, backoff[i] * 2);
            }
        }, 2, 1, TimeUnit.SECONDS);

        List<String> drifted = new ArrayList<>();
        if (drift > 0) {
            timers.schedule(() -> {
                long editStart = System.currentTimeMillis();
                List<DeviceAgent> shuffled = new ArrayList<>(agents);
                Collections.shuffle(shuffled);
                for (DeviceAgent a : shuffled.subList(0, Math.min(drift, shuffled.size()))) {
                    a.editLocally("edited locally at " + java.time.Instant.now());
                    a.sendHeartbeat();
                    drifted.add(a.serial());
                }
                System.out.printf("hand-edited %d devices at %d: %s%n", drifted.size(), editStart, drifted);
            }, driftAfterSec, TimeUnit.SECONDS);
        }

        long deadline = System.currentTimeMillis() + durationSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1_000);
            long open = agents.stream().filter(DeviceAgent::sessionOpen).count();
            System.out.printf("%d sessions open %d/%d, reconnect attempts %d%n", System.currentTimeMillis(), open,
                    agents.size(), reconnects.get());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("devices", agents.size());
        summary.put("claimFailures", failed.get());
        summary.put("onboardSeconds", Math.round(onboardSec * 10) / 10.0);
        summary.put("claimP50Ms", pct(ok, 50));
        summary.put("claimP99Ms", pct(ok, 99));
        summary.put("reconnects", reconnects.get());
        summary.put("drifted", drifted);
        if (summaryJson != null) {
            Files.writeString(summaryJson, Configs.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        }
        agents.forEach(DeviceAgent::close);
        timers.shutdownNow();
        return 0;
    }

    /**
     * Retries transient refusals (controller busy or restarting) with jittered backoff,
     * as a device in zero-touch provisioning would. Permanent refusals are not retried.
     */
    private static void claimWithRetry(DeviceAgent a) {
        long backoff = 500;
        for (int attempt = 1; ; attempt++) {
            try {
                a.claim();
                return;
            } catch (io.grpc.StatusRuntimeException e) {
                io.grpc.Status.Code code = e.getStatus().getCode();
                boolean transientError = code == io.grpc.Status.Code.UNAVAILABLE
                        || code == io.grpc.Status.Code.DEADLINE_EXCEEDED;
                if (!transientError || attempt == 6) {
                    throw e;
                }
                try {
                    Thread.sleep(backoff / 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(backoff / 2 + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff = Math.min(8_000, backoff * 2);
            }
        }
    }

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return -1;
        }
        int i = (int) Math.ceil(p / 100 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }
}
