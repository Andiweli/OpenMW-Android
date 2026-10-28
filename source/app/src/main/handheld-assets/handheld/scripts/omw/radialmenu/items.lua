local core = require('openmw.core')
local types = require('openmw.types')
local self = require('openmw.self')

local Actor = types.Actor

local ammoTypes = {
    [types.Weapon.TYPE.Arrow] = true,
    [types.Weapon.TYPE.Bolt] = true,
}

local castableEnchant = {
    [core.magic.ENCHANTMENT_TYPE.CastOnUse] = true,
    [core.magic.ENCHANTMENT_TYPE.CastOnce] = true,
}

local function recordOf(item)
    local itemType = item.type
    if itemType and itemType.record then
        return itemType.record(item)
    end
    return nil
end

local function enchantmentOf(item)
    local record = recordOf(item)
    if record and record.enchant and record.enchant ~= '' then
        return core.magic.enchantments.records[record.enchant]
    end
    return nil
end

local function firstEffectIcon(effects)
    if not effects or #effects < 1 then
        return nil
    end
    local effect = effects[1]
    if effect.effect and effect.effect.icon then
        return effect.effect.icon
    end
    if effect.id and core.magic.effects.records[effect.id] then
        return core.magic.effects.records[effect.id].icon
    end
    return nil
end

local function normalizeIcon(path)
    if not path or path == '' then
        return nil
    end
    return path:gsub('\\', '/')
end

local function stackCount(recordId)
    local ok, count = pcall(function()
        return Actor.inventory(self):countOf(recordId)
    end)
    if ok and count and count > 1 then
        return 'x' .. tostring(count)
    end
    return nil
end

local function unarmedItem()
    return {
        kind = 'unarmed',
        name = nil,
        icon = 'icons/k/stealth_handtohand.dds',
        subtitle = nil,
    }
end

local function collectWeapons()
    local equipped = Actor.getEquipment(self, Actor.EQUIPMENT_SLOT.CarriedRight)
    local seen = {}
    local list = {}
    for _, obj in ipairs(Actor.inventory(self):getAll(types.Weapon)) do
        local record = types.Weapon.record(obj)
        if record and not ammoTypes[record.type] and not seen[record.id] then
            seen[record.id] = true
            local pick = obj
            if equipped and equipped.recordId == record.id then
                pick = equipped
            end
            local maxDamage = math.max(record.chopMaxDamage or 0, record.slashMaxDamage or 0, record.thrustMaxDamage or 0)
            local minDamage = math.max(record.chopMinDamage or 0, record.slashMinDamage or 0, record.thrustMinDamage or 0)
            list[#list + 1] = {
                kind = 'weapon',
                object = pick,
                recordId = record.id,
                name = record.name,
                icon = normalizeIcon(record.icon),
                subtitle = string.format('%d-%d', minDamage, maxDamage),
            }
        end
    end
    return list
end

local function collectPotions()
    local seen = {}
    local list = {}
    for _, obj in ipairs(Actor.inventory(self):getAll(types.Potion)) do
        local record = types.Potion.record(obj)
        if record and not seen[record.id] then
            seen[record.id] = true
            list[#list + 1] = {
                kind = 'potion',
                object = obj,
                recordId = record.id,
                name = record.name,
                icon = normalizeIcon(record.icon or firstEffectIcon(record.effects)),
                subtitle = stackCount(record.id),
            }
        end
    end
    return list
end

local function collectIngredients()
    local seen = {}
    local list = {}
    for _, obj in ipairs(Actor.inventory(self):getAll(types.Ingredient)) do
        local record = types.Ingredient.record(obj)
        if record and not seen[record.id] then
            seen[record.id] = true
            list[#list + 1] = {
                kind = 'ingredient',
                object = obj,
                recordId = record.id,
                name = record.name,
                icon = normalizeIcon(record.icon),
                subtitle = stackCount(record.id),
            }
        end
    end
    return list
end

local function collectSpells()
    local list = {}
    local selectedSpell = Actor.getSelectedSpell(self)
    local spells = Actor.spells(self)
    for i = 1, #spells do
        local spell = spells[i]
        if spell.type == core.magic.SPELL_TYPE.Spell or spell.type == core.magic.SPELL_TYPE.Power then
            list[#list + 1] = {
                kind = 'spell',
                spell = spell,
                spellId = spell.id,
                name = spell.name,
                icon = normalizeIcon(firstEffectIcon(spell.effects)),
                subtitle = (spell.type == core.magic.SPELL_TYPE.Power) and nil or tostring(spell.cost or 0),
                isPower = spell.type == core.magic.SPELL_TYPE.Power,
                selected = selectedSpell and selectedSpell.id == spell.id,
            }
        end
    end

    local selectedEnchanted = Actor.getSelectedEnchantedItem(self)
    local seen = {}
    for _, obj in ipairs(Actor.inventory(self):getAll()) do
        local ench = enchantmentOf(obj)
        if ench and castableEnchant[ench.type] and not seen[obj.recordId] then
            seen[obj.recordId] = true
            local pick = obj
            if selectedEnchanted and selectedEnchanted.recordId == obj.recordId then
                pick = selectedEnchanted
            end
            local record = recordOf(obj)
            list[#list + 1] = {
                kind = 'enchanted',
                object = pick,
                recordId = obj.recordId,
                name = record and record.name or obj.recordId,
                icon = normalizeIcon(record and record.icon),
                subtitle = tostring(ench.cost or 0),
                selected = selectedEnchanted and selectedEnchanted.id == obj.id,
            }
        end
    end
    return list
end

local function collectAssignable()
    local list = { unarmedItem() }
    local groups = { collectWeapons(), collectPotions(), collectIngredients(), collectSpells() }
    for g = 1, #groups do
        for i = 1, #groups[g] do
            list[#list + 1] = groups[g][i]
        end
    end
    return list
end

local function fromObject(obj)
    if not obj then
        return nil
    end
    if types.Potion.objectIsInstance(obj) then
        local record = types.Potion.record(obj)
        return {
            kind = 'potion',
            object = obj,
            recordId = obj.recordId,
            name = record and record.name or obj.recordId,
            icon = normalizeIcon(record and (record.icon or firstEffectIcon(record.effects))),
            subtitle = stackCount(obj.recordId),
        }
    end
    if types.Ingredient.objectIsInstance(obj) then
        local record = types.Ingredient.record(obj)
        return {
            kind = 'ingredient',
            object = obj,
            recordId = obj.recordId,
            name = record and record.name or obj.recordId,
            icon = normalizeIcon(record and record.icon),
            subtitle = stackCount(obj.recordId),
        }
    end
    if types.Weapon.objectIsInstance(obj) then
        local record = types.Weapon.record(obj)
        if record and ammoTypes[record.type] then
            return nil
        end
        local maxDamage = math.max(record.chopMaxDamage or 0, record.slashMaxDamage or 0, record.thrustMaxDamage or 0)
        local minDamage = math.max(record.chopMinDamage or 0, record.slashMinDamage or 0, record.thrustMinDamage or 0)
        return {
            kind = 'weapon',
            object = obj,
            recordId = obj.recordId,
            name = record and record.name or obj.recordId,
            icon = normalizeIcon(record and record.icon),
            subtitle = string.format('%d-%d', minDamage, maxDamage),
        }
    end
    local ench = enchantmentOf(obj)
    if ench and castableEnchant[ench.type] then
        local record = recordOf(obj)
        return {
            kind = 'enchanted',
            object = obj,
            recordId = obj.recordId,
            name = record and record.name or obj.recordId,
            icon = normalizeIcon(record and record.icon),
            subtitle = tostring(ench.cost or 0),
        }
    end
    if types.Armor.objectIsInstance(obj) or types.Clothing.objectIsInstance(obj)
        or types.Lockpick.objectIsInstance(obj) or types.Probe.objectIsInstance(obj)
        or types.Repair.objectIsInstance(obj)
    then
        local record = recordOf(obj)
        return {
            kind = 'item',
            object = obj,
            recordId = obj.recordId,
            name = record and record.name or obj.recordId,
            icon = normalizeIcon(record and record.icon),
            subtitle = stackCount(obj.recordId),
        }
    end
    return nil
end

local function toSaved(item)
    if not item or item.kind == 'empty' then
        return nil
    end
    if item.kind == 'unarmed' then
        return { kind = 'unarmed' }
    end
    if item.kind == 'spell' then
        return { kind = 'spell', spellId = item.spellId or (item.spell and item.spell.id), name = item.name }
    end
    return { kind = item.kind, recordId = item.recordId, name = item.name }
end

local function findInList(list, kind, recordId)
    for i = 1, #list do
        if list[i].kind == kind and list[i].recordId == recordId then
            return list[i]
        end
    end
    return nil
end

local function resolveSaved(saved)
    if not saved or not saved.kind then
        return { kind = 'empty' }
    end
    if saved.kind == 'unarmed' then
        return unarmedItem()
    end
    if saved.kind == 'weapon' then
        return findInList(collectWeapons(), 'weapon', saved.recordId)
            or { kind = 'missing', name = saved.name, icon = nil }
    end
    if saved.kind == 'enchanted' then
        return findInList(collectSpells(), 'enchanted', saved.recordId)
            or { kind = 'missing', name = saved.name, icon = nil }
    end
    if saved.kind == 'potion' then
        return findInList(collectPotions(), 'potion', saved.recordId)
            or { kind = 'missing', name = saved.name, icon = nil }
    end
    if saved.kind == 'ingredient' then
        return findInList(collectIngredients(), 'ingredient', saved.recordId)
            or { kind = 'missing', name = saved.name, icon = nil }
    end
    if saved.kind == 'item' then
        local obj = Actor.inventory(self):find(saved.recordId)
        return fromObject(obj) or { kind = 'missing', name = saved.name, icon = nil }
    end
    if saved.kind == 'spell' then
        local spells = collectSpells()
        for i = 1, #spells do
            if spells[i].kind == 'spell' and spells[i].spellId == saved.spellId then
                return spells[i]
            end
        end
        return { kind = 'missing', name = saved.name, icon = nil }
    end
    return { kind = 'empty' }
end

return {
    collectWeapons = collectWeapons,
    collectSpells = collectSpells,
    collectPotions = collectPotions,
    collectIngredients = collectIngredients,
    collectAssignable = collectAssignable,
    unarmedItem = unarmedItem,
    fromObject = fromObject,
    toSaved = toSaved,
    resolveSaved = resolveSaved,
}
