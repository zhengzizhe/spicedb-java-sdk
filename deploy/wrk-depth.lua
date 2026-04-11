-- 参数化深度测试: 通过 DEPTH 环境变量控制祖先链深度
--   DEPTH=0: doc 在 depth=0 folder, 查询 folder#editor (本地权限, 无 ancestor 解析)
--   DEPTH=1..9: doc 在 depth=D folder, 查询 folder-(F-D)#editor (最远祖先)
--     → 解析路径: doc→folder→ancestor (并行 D 个 dispatch)→folder-base#editor
--
-- 数据规律 (bulk-import-10m.sh):
--   folder-F depth = F % 10
--   folder-F editor = user-((F*2)%100000), user-((F*2+1)%100000)
--   doc-N folder = folder-(N/8)
--
-- 候选 doc 范围: 所有 depth=DEPTH 的 folder (总 10,000 个, ~80K docs)
-- 足够大以让 cache 命中率远低于 100%, 暴露真实的 depth 成本
local DEPTH = tonumber(os.getenv("DEPTH") or "0")
math.randomseed(1000 + DEPTH)

-- 预生成候选 doc 列表: 10000 个 folder × 8 docs/folder = 80000 docs
-- 注意: 只有前 ~130000 个 doc 真正有 relation (导入时 target=3M 截断)
-- 所以限制 folder_id < 16000 → doc_id < 128000
local candidates = {}
for base = 0, 1599 do  -- folder-DEPTH, folder-(DEPTH+10), ..., folder-(DEPTH+15990)
    local folder_id = base * 10 + DEPTH
    if folder_id < 16000 then
        for local_doc = 0, 7 do
            table.insert(candidates, folder_id * 8 + local_doc)
        end
    end
end
-- #candidates ≈ 1600 * 8 = 12800 unique docs per depth

request = function()
    local idx = math.random(1, #candidates)
    local doc_id = candidates[idx]
    local folder_id = math.floor(doc_id / 8)
    local base_folder = folder_id - DEPTH   -- depth=0 folder at start of chain
    local user_id = (base_folder * 2) % 100000
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=view&user=user-" .. user_id
    return wrk.format("GET", path)
end

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
    io.write(string.format("=== Total: %d req, %d status_err, %d timeout ===\n",
        summary.requests, summary.errors.status, summary.errors.timeout))
end
