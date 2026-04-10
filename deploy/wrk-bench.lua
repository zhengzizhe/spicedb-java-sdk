-- wrk benchmark: Zipfian 分布模拟真实访问模式
--
-- 真实场景：20% 的文档承受 80% 的流量（热门文档）
-- 用户访问也集中在活跃用户群体
--
-- doc_id:  Zipfian 分布，集中在前 10K 热门文档（总 800K）
-- user_id: Zipfian 分布，集中在前 5K 活跃用户（总 100K）

local permissions = {"view", "edit", "comment", "manage"}
local perm_weights = {60, 20, 15, 5}  -- view 占 60%

-- 简化的 Zipfian: 用指数衰减模拟热点
-- ~50% 请求命中前 1% 的 doc，~80% 命中前 10%
local function zipf(max)
    local u = math.random()
    -- 指数分布: 小 id 概率高
    return math.floor(max * (u * u))
end

-- 加权随机选择 permission
local function weighted_perm()
    local r = math.random(100)
    if r <= 60 then return "view"
    elseif r <= 80 then return "edit"
    elseif r <= 95 then return "comment"
    else return "manage"
    end
end

request = function()
    local doc_id = zipf(800000)
    local user_id = zipf(100000)
    local perm = weighted_perm()
    local path = "/doc/check?id=doc-" .. doc_id .. "&permission=" .. perm .. "&user=user-" .. user_id
    return wrk.format("GET", path)
end
