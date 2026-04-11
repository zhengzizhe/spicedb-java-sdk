-- 场景 D2: folder editor 继承 (1 层网络跳: doc→folder)
--
-- 数据规律:
--   doc-N 的 folder = folder-(N / 8)
--   folder-F 的 editor = user-((F*2) % NUM_USERS), user-((F*2+1) % NUM_USERS)
--
-- 这里用 doc 范围 0..9999 → folder-0..1249
-- 查询时用 folder-F 的第一个 editor user-((F*2) % NUM_USERS)
-- 权限解析: doc#view → folder->view → folder#view_local → folder#editor ✓
math.randomseed(123)
request = function()
    local doc_id = math.random(0, 9999)
    local folder_id = math.floor(doc_id / 8)
    local user_id = (folder_id * 2) % 100000
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=view&user=user-" .. user_id
    return wrk.format("GET", path)
end
