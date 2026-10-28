local ambient = require('openmw.ambient')
local core = require('openmw.core')
local input = require('openmw.input')
local self = require('openmw.self')
local storage = require('openmw.storage')
local types = require('openmw.types')
local ui = require('openmw.ui')
local I = require('openmw.interfaces')

local Actor = types.Actor

local items = require('scripts.omw.radialmenu.items')
local picker = require('scripts.omw.radialmenu.picker')
local wheel = require('scripts.omw.radialmenu.ui')

local l10n = core.l10n('OMWRadialMenu')
local settings = storage.playerSection('SettingsOMWRadialMenu')
-- Slots live in the save (per character). The old shared section is only read
-- once to migrate existing assignments.
local legacySlots = storage.playerSection('OMWRadialMenuSlots')
local slots = {}

local SLOT_COUNT = 8
local PAGE_COUNT = 4
local STICK_DEADZONE = 0.45
local MIN_HOLD = 0.12

-- 8 slots clockwise from the top: 1 up, 3 right, 5 down, 7 left.
local CARDINAL = {
    [input.CONTROLLER_BUTTON.DPadUp] = 1,
    [input.CONTROLLER_BUTTON.DPadRight] = 3,
    [input.CONTROLLER_BUTTON.DPadDown] = 5,
    [input.CONTROLLER_BUTTON.DPadLeft] = 7,
}

local open = false
local openedAt = 0
local stickMoved = false
local paused = false
local selected = 1
local pendingAssign = nil
local reopenWheel = false
local prevHeld = false
local prevY = false
local prevX = false
local prevA = false
local prevB = false
local prevDPad = {
    [input.CONTROLLER_BUTTON.DPadUp] = false,
    [input.CONTROLLER_BUTTON.DPadRight] = false,
    [input.CONTROLLER_BUTTON.DPadDown] = false,
    [input.CONTROLLER_BUTTON.DPadLeft] = false,
}

local function enabled()
    return settings:get('enabled') ~= false
end

local function heldOpen()
    return input.getBooleanActionValue('RadialMenu')
end

local function currentPage()
    local page = tonumber(slots.page) or 1
    if page < 1 then
        page = 1
    elseif page > PAGE_COUNT then
        page = PAGE_COUNT
    end
    return page
end

local function setPage(page)
    slots.page = page
end

local function slotKey(page, index)
    return 'slot' .. tostring((page - 1) * SLOT_COUNT + index)
end

local function savedSlot(index, page)
    return slots[slotKey(page or currentPage(), index)]
end

local function setSavedSlot(index, saved, page)
    slots[slotKey(page or currentPage(), index)] = saved
end

local function loadSlotState(data)
    if data and type(data.slots) == 'table' then
        slots = data.slots
    else
        slots = legacySlots:asTable() or {}
        legacySlots:reset()
    end
    if data and data.enabled ~= nil then
        settings:set('enabled', data.enabled)
    end
end

local function decorate(item)
    if item.kind == 'unarmed' then
        item.name = l10n('handToHand')
        item.subtitle = l10n('unarmed')
    elseif item.kind == 'empty' then
        item.name = l10n('empty')
    elseif item.kind == 'missing' then
        item.name = item.name or l10n('missing')
        item.subtitle = l10n('missing')
    elseif item.kind == 'potion' and (not item.subtitle or item.subtitle == '') then
        item.subtitle = l10n('potion')
    elseif item.kind == 'ingredient' and (not item.subtitle or item.subtitle == '') then
        item.subtitle = l10n('ingredient')
    elseif item.isPower and (not item.subtitle or item.subtitle == '') then
        item.subtitle = l10n('power')
    end
    return item
end

local function resolvedSlots(page)
    local list = {}
    for i = 1, SLOT_COUNT do
        list[i] = decorate(items.resolveSaved(savedSlot(i, page)))
    end
    return list
end

local function uiState()
    local page = currentPage()
    return {
        pageItems = resolvedSlots(page),
        selected = selected,
        page = page,
        pageCount = PAGE_COUNT,
        modeTitle = l10n('pageLabel', { page = tostring(page), count = tostring(PAGE_COUNT) }),
        hint = l10n('hint'),
    }
end

local function freezeMove()
    self.controls.yawChange = 0
    self.controls.pitchChange = 0
    self.controls.movement = 0
    self.controls.sideMovement = 0
    self.controls.jump = false
end

local function setPaused(active)
    if active and not paused then
        core.sendGlobalEvent('Pause', 'radialmenu')
        paused = true
    elseif not active and paused then
        core.sendGlobalEvent('Unpause', 'radialmenu')
        paused = false
    end
end

local function setOverrides(active)
    I.Controls.overrideMovementControls(active)
    I.Controls.overrideCombatControls(active)
    I.Controls.overrideUiControls(active)
    -- Do not toggle CONTROL_SWITCH.Looking: disabling it calls rotateObject(0,0,0)
    -- and snaps the camera to a fixed heading.
    freezeMove()
    setPaused(active)
end

local function useWorldItem(item)
    if not item or not item.object then
        return
    end
    core.sendGlobalEvent('UseItem', { object = item.object, actor = self })
    ambient.playSound('menu click', { scale = false })
end

local function applyItem(item)
    if not item or item.kind == 'empty' or item.kind == 'missing' then
        return
    end
    if item.kind == 'unarmed' then
        local eq = {}
        for slot, obj in pairs(Actor.getEquipment(self)) do
            if slot ~= Actor.EQUIPMENT_SLOT.CarriedRight then
                eq[slot] = obj
            end
        end
        Actor.setEquipment(self, eq)
        Actor.setStance(self, Actor.STANCE.Weapon)
        ambient.playSound('item weapon up', { scale = false })
    elseif item.kind == 'weapon' and item.object then
        local eq = {}
        for slot, obj in pairs(Actor.getEquipment(self)) do
            eq[slot] = obj
        end
        eq[Actor.EQUIPMENT_SLOT.CarriedRight] = item.object
        Actor.setEquipment(self, eq)
        Actor.setStance(self, Actor.STANCE.Weapon)
        ambient.playSound('item weapon up', { scale = false })
    elseif item.kind == 'spell' then
        local spell = item.spell
        if not spell and item.spellId then
            spell = core.magic.spells.records[item.spellId]
        end
        if spell then
            Actor.setSelectedSpell(self, spell)
            Actor.setStance(self, Actor.STANCE.Spell)
            ambient.playSound('menu click', { scale = false })
        end
    elseif item.kind == 'enchanted' and item.object then
        Actor.setSelectedEnchantedItem(self, item.object)
        Actor.setStance(self, Actor.STANCE.Spell)
        ambient.playSound('menu click', { scale = false })
    elseif item.kind == 'potion' or item.kind == 'ingredient' or item.kind == 'item' then
        useWorldItem(item)
    end
end

local function useSlot(index)
    applyItem(resolvedSlots(currentPage())[index])
end

local function close(apply, keepPaused)
    if not open then
        return
    end
    local toApply = nil
    if apply then
        toApply = resolvedSlots(currentPage())[selected]
    end
    open = false
    wheel.hide()
    if not keepPaused then
        setOverrides(false)
    end
    if toApply then
        applyItem(toApply)
    end
end

local function changeWheelPage(delta)
    local page = currentPage()
    setPage(((page - 1 + delta) % PAGE_COUNT) + 1)
    if open then
        ambient.playSound('menu click', { scale = false })
        wheel.refresh(uiState())
    end
end

local function openWheel()
    if I.UI.getMode() or Actor.isDead(self) then
        return
    end
    if open then
        return
    end
    open = true
    openedAt = core.getRealTime()
    stickMoved = false
    selected = 1
    -- A, B or Y may still be held from the picker that reopened the wheel.
    prevA = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.A)
    prevB = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.B)
    prevX = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.X)
    prevY = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.Y)
    setOverrides(true)
    ambient.playSound('menu click', { scale = false })
    wheel.show(uiState())
end

local function storeAssignment(page, index, item)
    setSavedSlot(index, items.toSaved(item), page)
    setPage(page)
    selected = index
    ambient.playSound('item weapon up', { scale = false })
    ui.showMessage(l10n('assigned', {
        name = decorate(item).name or '',
        page = tostring(page),
        index = tostring(index),
    }))
end

local function applyAssignResult(pick)
    if not pendingAssign then
        return
    end
    local page = pendingAssign.page
    local index = pendingAssign.index
    pendingAssign = nil
    if pick and pick.action == 'clear' then
        setSavedSlot(index, nil, page)
        setPage(page)
        selected = index
        ambient.playSound('menu click', { scale = false })
    elseif pick and pick.action == 'item' then
        storeAssignment(page, index, pick.item)
    end
    reopenWheel = true
end

local function enterAssign()
    pendingAssign = { page = currentPage(), index = selected }
    close(false, true)
    picker.open(l10n('assignTitle', { index = tostring(selected) }), applyAssignResult)
end

local function clearSlot()
    setSavedSlot(selected, nil)
    ambient.playSound('menu click', { scale = false })
    wheel.refresh(uiState())
end

local function firstEmptySlot()
    for page = 1, PAGE_COUNT do
        local slots = resolvedSlots(page)
        for i = 1, SLOT_COUNT do
            if slots[i].kind == 'empty' then
                return page, i
            end
        end
    end
    return nil, nil
end

local function assignFocusedInInterface()
    local obj = ui._getFocusedInventoryItem and ui._getFocusedInventoryItem()
    local item = obj and items.fromObject(obj) or nil
    if not item then
        ui.showMessage(l10n('assignNothing'))
        return false
    end
    local page, index = firstEmptySlot()
    if not page then
        ui.showMessage(l10n('assignFull'))
        return false
    end
    storeAssignment(page, index, item)
    return true
end

local function indexFromAxes(x, y, count)
    if count < 1 then
        return nil
    end
    if (x * x + y * y) < (STICK_DEADZONE * STICK_DEADZONE) then
        return nil
    end
    local angle = math.atan2(x, -y)
    if angle < 0 then
        angle = angle + math.pi * 2
    end
    local sector = math.pi * 2 / count
    return math.floor(angle / sector + 0.5) % count + 1
end

local function updateStick()
    local current = resolvedSlots(currentPage())
    local lx = input.getAxisValue(input.CONTROLLER_AXIS.LeftX)
    local ly = input.getAxisValue(input.CONTROLLER_AXIS.LeftY)
    local rx = input.getAxisValue(input.CONTROLLER_AXIS.RightX)
    local ry = input.getAxisValue(input.CONTROLLER_AXIS.RightY)
    local x, y
    if (rx * rx + ry * ry) > (lx * lx + ly * ly) then
        x, y = rx, ry
    else
        x, y = lx, ly
    end
    local idx = indexFromAxes(x, y, #current)
    if idx and idx ~= selected then
        selected = idx
        stickMoved = true
        wheel.refresh(uiState())
    end
end

local function edge(cur, prev)
    return cur and not prev
end

local function pollDpad()
    local now = {}
    local pressed = nil
    for button, _ in pairs(CARDINAL) do
        now[button] = input.isControllerButtonPressed(button)
        if edge(now[button], prevDPad[button]) then
            pressed = button
        end
    end
    prevDPad = now
    return pressed
end

local function onFrame()
    local held = enabled() and heldOpen()
    local dpad = pollDpad()
    local y = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.Y)

    if not open then
        if enabled() then
            local mode = I.UI.getMode()
            if picker.isOpen() then
                freezeMove()
                local pageMode = mode == I.UI.MODE.Journal or mode == I.UI.MODE.Rest
                if (mode and not pageMode) or Actor.isDead(self) then
                    picker.cancel()
                    reopenWheel = false
                    setOverrides(false)
                else
                    picker.update()
                end
            elseif mode == I.UI.MODE.Interface and ui._getFocusedInventoryItem then
                if edge(y, prevY) then
                    assignFocusedInInterface()
                end
            elseif not mode and not Actor.isDead(self) then
                if reopenWheel then
                    reopenWheel = false
                    openWheel()
                elseif dpad then
                    useSlot(CARDINAL[dpad])
                elseif held and not prevHeld then
                    openWheel()
                end
            elseif reopenWheel then
                reopenWheel = false
                setOverrides(false)
            end
        end
        prevHeld = held
        prevY = y
        return
    end

    freezeMove()

    local uiMode = I.UI.getMode()
    if Actor.isDead(self) or (uiMode and uiMode ~= I.UI.MODE.Journal and uiMode ~= I.UI.MODE.Rest) then
        close(false)
        prevHeld = held
        prevY = y
        return
    end

    updateStick()

    local x = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.X)
    local a = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.A)
    local b = input.isControllerButtonPressed(input.CONTROLLER_BUTTON.B)

    if dpad == input.CONTROLLER_BUTTON.DPadLeft then
        changeWheelPage(-1)
    elseif dpad == input.CONTROLLER_BUTTON.DPadRight then
        changeWheelPage(1)
    elseif dpad == input.CONTROLLER_BUTTON.DPadUp or dpad == input.CONTROLLER_BUTTON.DPadDown then
        selected = CARDINAL[dpad]
        stickMoved = true
        close(true)
    elseif edge(b, prevB) then
        close(false)
    elseif edge(a, prevA) then
        close(true)
    elseif edge(y, prevY) then
        enterAssign()
    elseif edge(x, prevX) then
        clearSlot()
    elseif not held and prevHeld then
        local heldLong = (core.getRealTime() - openedAt) >= MIN_HOLD
        close(heldLong or stickMoved)
    end

    prevHeld = held
    prevY = y
    prevX = x
    prevA = a
    prevB = b
end

local function onTouchPress(_, event)
    if not open or not event or not event.position then
        return
    end
    local current = resolvedSlots(currentPage())
    local pos = event.position
    local idx = indexFromAxes(pos.x * 2 - 1, pos.y * 2 - 1, #current)
    if idx then
        selected = idx
        stickMoved = true
        wheel.refresh(uiState())
    end
end

return {
    engineHandlers = {
        onFrame = onFrame,
        onTouchPress = onTouchPress,
        onSave = function()
            if open then
                close(false)
            end
            return {
                slots = slots,
                enabled = settings:get('enabled'),
            }
        end,
        onLoad = loadSlotState,
    },
    eventHandlers = {
        UiModeChanged = function(data)
            local mode = data and data.newMode
            if picker.isOpen() and (mode == I.UI.MODE.Journal or mode == I.UI.MODE.Rest) then
                -- R1 pages the picker list and Select is Rest; neither may open its window.
                I.UI.removeMode(mode)
                return
            end
            if not open then
                return
            end
            if mode == I.UI.MODE.Journal or mode == I.UI.MODE.Rest then
                I.UI.removeMode(mode)
                changeWheelPage(mode == I.UI.MODE.Journal and 1 or -1)
                return
            end
            if mode then
                close(false)
            end
        end,
    },
}
