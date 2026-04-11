-- 场景 D3: 深层 ancestor 继承 (9 层祖先链中最远的那一层)
--
-- 数据规律:
--   doc-N 的 folder = folder-(N / 8)
--   folder-F 的 depth = F % 10
--   depth=9 的 folder 有祖先 folder-(F-9..F-1)
--
-- 挑选 depth=9 的文件夹（F 的个位 = 9）：folder-9, 19, 29, ..., 1249
-- 对应 doc-72..79, 152..159, 232..239, ...
--
-- 查询时用 folder-(F-9)（最远祖先, depth=0）的 editor
-- folder-(F-9) editor = user-((F-9)*2 % 100000)
--
-- 权限解析:
--   doc#view → folder->view → folder#view_local? false
--            → ancestor->view_local (并行 9 个 dispatch)
--            → folder-(F-9)#view_local → folder-(F-9)#editor ✓
math.randomseed(456)

-- 预生成 "depth=9 的 doc 列表"
local candidates = {}
for base = 0, 99 do  -- folder-9, 19, ..., 999
    local folder_id = base * 10 + 9
    for local_doc = 0, 7 do
        table.insert(candidates, folder_id * 8 + local_doc)
    end
end
-- 800 个 candidate docs

request = function()
    local idx = math.random(1, #candidates)
    local doc_id = candidates[idx]
    local folder_id = math.floor(doc_id / 8)  -- depth=9 folder
    local deepest_ancestor = folder_id - 9    -- depth=0 folder
    local user_id = (deepest_ancestor * 2) % 100000
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=view&user=user-" .. user_id
    return wrk.format("GET", path)
end
