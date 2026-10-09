package com.mcmodworkspace.moreballs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 【性能诊断】全局计时器。
 *
 * <p>写这个是因为「球一多就卡、但给球各阶段加的计时显示总共不到 1ms，
 * 服务器却落后 40 刻」—— 说明瓶颈不在球上。那就得测所有全局监听。</p>
 *
 * <p>用法：{@code long t = System.nanoTime(); ...; ModProfiler.hit("名字", t);}</p>
 *
 * <p>每 {@link #REPORT_EVERY} 次采样打一行，然后清零。**性能问题解决后应删掉本类
 * 与所有调用点** —— 它本身也有开销（ConcurrentHashMap 查找）。</p>
 */
public final class ModProfiler {

    /** 每多少次采样打一次报告 */
    private static final long REPORT_EVERY = 2000L;

    private static final Map<String, long[]> DATA = new ConcurrentHashMap<>();
    private static final AtomicLong SAMPLES = new AtomicLong();

    private ModProfiler() {
    }

    /** 记一笔。传调用前拿到的 nanoTime。 */
    public static void hit(String stage, long startNanos) {
        long cost = System.nanoTime() - startNanos;
        DATA.computeIfAbsent(stage, k -> new long[1])[0] += cost;
        if (SAMPLES.incrementAndGet() % REPORT_EVERY == 0L) {
            StringBuilder sb = new StringBuilder("[ball][总性能] 各监听累计耗时：");
            DATA.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .forEach(e -> sb.append(String.format(" %s=%.1fms", e.getKey(), e.getValue()[0] / 1_000_000.0)));
            MoreBalls.LOGGER.info(sb.toString());
            DATA.values().forEach(v -> v[0] = 0L);
        }
    }
}