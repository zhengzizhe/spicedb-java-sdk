-- 场景 D4: 拒绝 (穷尽所有路径)
--
-- user-(99000..99999) 在我们的数据里几乎不参与任何关系
-- (NUM_USERS=100000, 但生成逻辑很少碰到 ID > 99000 的尾部)
--
-- 查询 doc-0..9999 + user-(99000..99999) → 必然 false
-- 这是最"贵"的场景: SpiceDB 必须走完 doc→folder→space→ancestor 所有路径才能确认拒绝
math.randomseed(789)
request = function()
    local doc_id = math.random(0, 9999)
    local user_id = math.random(99000, 99999)
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=view&user=user-" .. user_id
    return wrk.format("GET", path)
end
