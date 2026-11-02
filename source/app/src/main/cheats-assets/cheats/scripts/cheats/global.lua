local types = require('openmw.types')
local world = require('openmw.world')

local function addGold(data)
    if not data.actor or not data.actor:isValid() then
        error('target actor is not valid')
    end
    local money = world.createObject('gold_001', data.amount)
    money:moveInto(types.Actor.inventory(data.actor))
end

return {
    eventHandlers = {
        OMWCheatsAddGold = function(data)
            local ok, err = pcall(addGold, data)
            if data.actor and data.actor:isValid() then
                data.actor:sendEvent('OMWCheatsGoldResult', { ok = ok, amount = data.amount, error = err })
            else
                print(('OMWCheatsAddGold failed: %s'):format(tostring(err)))
            end
        end,
    },
}
