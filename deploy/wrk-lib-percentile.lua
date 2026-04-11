-- 共用: done() 函数打印完整延迟分布
-- 由主脚本在末尾 dofile 或复制粘贴
done = function(summary, latency, requests)
    io.write("=== PERCENTILES (microseconds) ===\n")
    local ps = {0, 10, 25, 50, 75, 90, 95, 99, 99.9, 99.99}
    for _, p in ipairs(ps) do
        io.write(string.format("  p%-6s %10d us  (%.3f ms)\n",
            tostring(p), latency:percentile(p), latency:percentile(p)/1000))
    end
    io.write(string.format("  min    %10d us  (%.3f ms)\n", latency.min, latency.min/1000))
    io.write(string.format("  max    %10d us  (%.3f ms)\n", latency.max, latency.max/1000))
    io.write(string.format("  mean   %10.0f us  (%.3f ms)\n", latency.mean, latency.mean/1000))
    io.write(string.format("  stdev  %10.0f us  (%.3f ms)\n", latency.stdev, latency.stdev/1000))
    io.write(string.format("=== Total: %d req, %d err, %d timeout ===\n",
        summary.requests, summary.errors.status, summary.errors.timeout))
end
