-- 场景 D1: 直接 owner 授权 (最浅路径)
--
-- 数据规律 (bulk-import-10m.sh):
--   doc-N 的 owner = user-(N % NUM_USERS)
--
-- 这里用 doc-0..9999 + 对应 owner，10000 个唯一 key
-- 权限解析: doc#view → doc#comment → doc#edit → doc#manage → doc#owner ✓
-- 返回: allowed=true
math.randomseed(42)
request = function()
    local doc_id = math.random(0, 9999)
    local user_id = doc_id  -- owner formula
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=view&user=user-" .. user_id
    return wrk.format("GET", path)
end
