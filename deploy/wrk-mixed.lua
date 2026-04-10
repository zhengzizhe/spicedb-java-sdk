-- 99% 读 + 1% 写 混合负载
-- 读: doc check (Zipfian 分布)
-- 写: doc grant/revoke (随机)

local permissions = {"view", "edit", "comment", "manage"}
local relations = {"viewer", "editor", "commenter"}

local function zipf(max)
    local u = math.random()
    return math.floor(max * (u * u))
end

local counter = 0

request = function()
    counter = counter + 1

    if counter % 100 == 0 then
        -- 1% 写: grant 或 revoke
        local doc_id = math.random(0, 799999)
        local user_id = math.random(0, 99999)
        local rel = relations[math.random(#relations)]
        if counter % 200 == 0 then
            return wrk.format("POST", "/doc/revoke?id=doc-" .. doc_id .. "&relation=" .. rel .. "&user=user-" .. user_id)
        else
            return wrk.format("POST", "/doc/grant?id=doc-" .. doc_id .. "&relation=" .. rel .. "&user=user-" .. user_id)
        end
    else
        -- 99% 读: check
        local doc_id = zipf(800000)
        local user_id = zipf(100000)
        local perm = permissions[math.random(#permissions)]
        return wrk.format("GET", "/doc/check?id=doc-" .. doc_id .. "&permission=" .. perm .. "&user=user-" .. user_id)
    end
end
