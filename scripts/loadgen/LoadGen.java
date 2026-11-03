import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * 固定速率的受理接口压测器（T4.2），只用 JDK 自带能力，不引入 k6/wrk。
 *
 * <p>为什么不用 ab：ab 单连接串行、且无法给每个请求不同的 idempotency_key——
 * 那样 9000 次请求会全部命中同一行的幂等分支，测出来的是"幂等命中路径"的吞吐，
 * 而真正要测的"每单三写"（notification 插入 + 认领更新 + 明细插入 + 状态更新）一次都没发生。
 *
 * <p>为什么不用 k6：这台机器上没有；而这个压测只做一件事（按 150 QPS 打 60 秒、记录分位数），
 * 引入一个需要安装的运行时，换来的是"别人复现时先卡在装工具上"。
 *
 * <p>用法：
 * <pre>
 *   java scripts/loadgen/LoadGen.java --rate 150 --duration 60 --target http://127.0.0.1:9101/cb
 * </pre>
 * 输出末尾是一行 {@code SUMMARY k=v ...}，供 shell 解析。
 */
public class LoadGen {

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        String url = a.getOrDefault("url", "http://127.0.0.1:8080/v1/notifications");
        String apiKey = a.getOrDefault("key", "k_shop");
        // 多个下游端点用逗号分隔：真实部署里 150 QPS 是摊在 3~12 个供应商回调上的，
        // 全部打一个端点会让"每端点并发上限"（T2.5）成为吞吐天花板，测出来的不是系统能力
        List<String> targets = List.of(a.getOrDefault("target", "http://127.0.0.1:9101/cb").split(","));
        int rate = Integer.parseInt(a.getOrDefault("rate", "150"));
        int seconds = Integer.parseInt(a.getOrDefault("duration", "60"));
        int warmupSeconds = Integer.parseInt(a.getOrDefault("warmup", "10"));
        int concurrency = Integer.parseInt(a.getOrDefault("concurrency", "64"));
        String prefix = a.getOrDefault("prefix", "lt" + System.currentTimeMillis());

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        // 并发上限：压测器自己不能被"队列无限长"骗了——限住并发，超出的请求记成 dropped，
        // 这样"服务端扛不住"与"压测器发不出去"在结果里是两件分开的事
        Semaphore inFlight = new Semaphore(concurrency);
        Map<Integer, LongAdder> status = new ConcurrentHashMap<>();
        LongAdder dropped = new LongAdder();
        LongAdder failed = new LongAdder();
        AtomicLong seq = new AtomicLong();

        long capacity = (long) rate * (seconds + warmupSeconds) + 1_000;
        long[] latencies = new long[(int) capacity];
        int[] seconds_ = new int[(int) capacity];
        AtomicLong recorded = new AtomicLong();

        System.out.printf("loadgen: rate=%d/s duration=%ds warmup=%ds concurrency=%d url=%s endpoints=%d%n",
                rate, seconds, warmupSeconds, concurrency, url, targets.size());

        long periodNanos = 1_000_000_000L / rate;
        long start = System.nanoTime();
        long warmupNanos = TimeUnit.SECONDS.toNanos(warmupSeconds);
        long endNanos = start + TimeUnit.SECONDS.toNanos(warmupSeconds + seconds);

        long next = start;
        long sent = 0;
        while (true) {
            next += periodNanos;
            if (next >= endNanos) {
                break;
            }
            LockSupport.parkNanos(next - System.nanoTime());

            if (!inFlight.tryAcquire()) {
                dropped.increment();
                continue;
            }
            long n = seq.incrementAndGet();
            String key = prefix + ":" + n;
            String target = targets.get((int) (n % targets.size()));
            boolean measured = System.nanoTime() - start >= warmupNanos;
            sent++;

            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    String payload = "{\"idempotency_key\":\"" + key + "\",\"target_url\":\"" + target
                            + "\",\"body\":\"{\\\"seq\\\":" + n + "}\"}";
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/json")
                            .header("X-Api-Key", apiKey)
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
                    HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                    if (measured) {
                        status.computeIfAbsent(resp.statusCode(), k -> new LongAdder()).increment();
                        int idx = (int) recorded.getAndIncrement();
                        latencies[idx] = System.nanoTime() - t0;
                        seconds_[idx] = (int) ((t0 - start - warmupNanos) / 1_000_000_000L);
                    }
                } catch (Exception e) {
                    if (measured) {
                        failed.increment();
                    }
                } finally {
                    inFlight.release();
                }
            });
        }

        // 等所有在途请求结束，否则最后一批的延迟与状态码会漏统计
        pool.shutdown();
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            System.out.println("WARN 仍有请求未结束（压测器侧超时）");
        }

        int n = (int) recorded.get();
        long[] sample = Arrays.copyOf(latencies, n);
        Arrays.sort(sample);
        long totalSent = sent;
        double achievedQps = n / (double) seconds;

        System.out.println();
        System.out.println("================ 压测结果（仅统计预热之后的 " + seconds + " 秒） ================");
        System.out.printf("发出请求        : %d（预热 %ds 内的不计入统计）%n", totalSent, warmupSeconds);
        System.out.printf("完成并统计      : %d，失败 %d，压测器侧丢弃 %d%n", n, failed.sum(), dropped.sum());
        System.out.printf("达成速率        : %.1f 请求/秒（目标 %d）%n", achievedQps, rate);
        System.out.printf("状态码分布      : %s%n", statusText(status));
        if (n > 0) {
            System.out.printf("受理延迟        : p50 %s | p90 %s | p99 %s | p999 %s | max %s%n",
                    ms(p(sample, 50)), ms(p(sample, 90)), ms(p(sample, 99)), ms(p(sample, 99.9)),
                    ms(sample[sample.length - 1]));
            System.out.printf("最慢的 5 条     : %s%n", slowest(latencies, seconds_, n, 5));
        }
        System.out.println("SUMMARY sent=" + totalSent + " ok=" + n + " failed=" + failed.sum()
                + " dropped=" + dropped.sum() + " qps=" + String.format("%.1f", achievedQps)
                + " p50_ms=" + ms(p(sample, 50)) + " p90_ms=" + ms(p(sample, 90))
                + " p99_ms=" + ms(p(sample, 99)) + " p999_ms=" + ms(p(sample, 99.9))
                + " max_ms=" + ms(sample.length == 0 ? 0 : sample[sample.length - 1])
                + " status=\"" + statusText(status) + "\"");
        pool.close();
    }

    /**
     * 最慢的 k 条，带上"发生在第几秒"。
     *
     * <p>为什么值得单独打这一行：p99 超标时，最慢的几条如果散落在不同秒，说明是单请求慢
     * （锁竞争/慢查询）；如果挤在同一秒，说明整机停了一下（GC、redo log 刷盘、容器磁盘抖动）——
     * 两者的处置完全不同，只看分位数分不出来。
     */
    private static String slowest(long[] latencies, int[] seconds, int n, int k) {
        int[] idx = new int[k];
        Arrays.fill(idx, -1);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                if (idx[j] == -1 || latencies[i] > latencies[idx[j]]) {
                    System.arraycopy(idx, j, idx, j + 1, k - j - 1);
                    idx[j] = i;
                    break;
                }
            }
        }
        List<String> parts = new ArrayList<>();
        for (int i : idx) {
            if (i >= 0) {
                parts.add(ms(latencies[i]) + "ms@t=" + seconds[i] + "s");
            }
        }
        return String.join("  ", parts);
    }

    private static String statusText(Map<Integer, LongAdder> status) {
        List<String> parts = new ArrayList<>();
        status.forEach((code, count) -> parts.add(code + ":" + count.sum()));
        parts.sort(String::compareTo);
        return String.join(" ", parts);
    }

    private static long p(long[] sorted, double pct) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                continue;
            }
            String k = args[i].substring(2);
            int eq = k.indexOf('=');
            if (eq > 0) {
                map.put(k.substring(0, eq), k.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                map.put(k, args[++i]);
            } else {
                map.put(k, "true");
            }
        }
        return map;
    }
}
