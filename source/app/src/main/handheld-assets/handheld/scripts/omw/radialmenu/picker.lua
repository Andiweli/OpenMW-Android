-- Lua stand-in for the Switch engine's radial assign window, which this
-- engine build does not have. Lists everything a wheel slot can hold.

local ambient = require('openmw.ambient')
local core = require('openmw.core')
local input = require('openmw.input')
local self = require('openmw.self')
local types = require('openmw.types')
local ui = require('openmw.ui')
local util = require('openmw.util')
local constants = require('scripts.omw.mwui.constants')

local items = require('scripts.omw.radialmenu.items')

local l10n = core.l10n('OMWRadialMenu')
local v2 = util.vector2
local white = constants.whiteTexture
local sand = constants.normalColor
local header = constants.headerColor
local gold = util.color.rgb(0.92, 0.78, 0.36)
local panelBg = util.color.rgb(0.04, 0.035, 0.025)
local rowBg = util.color.rgb(0.92, 0.78, 0.36)

local MAX_ROWS = 9
local ROW_HEIGHT = 40
local CHROME_HEIGHT = 96
local ICON = 32
local STICK_DEADZONE = 0.5
local REPEAT_DELAY = 0.35
local REPEAT_INTERVAL = 0.08

local B = input.CONTROLLER_BUTTON

local element = nil
local entries = {}
local selected = 1
local title = ''
local onDone = nil
local prev = {}
local heldDir = 0
local nextRepeat = 0
local visibleRows = MAX_ROWS

local function pressed(button)
    return input.isControllerButtonPressed(button)
end

local function edge(button)
    local now = pressed(button)
    local was = prev[button]
    prev[button] = now
    return now and not was
end

local function syncButtons()
    for _, button in ipairs({ B.A, B.B, B.LeftShoulder, B.RightShoulder }) do
        prev[button] = pressed(button)
    end
end

local function collectEntries()
    local list = { { kind = 'clear', name = l10n('clearSlot') } }
    local assignable = items.collectAssignable()
    for i = 1, #assignable do
        list[#list + 1] = assignable[i]
    end
    local inventory = types.Actor.inventory(self)
    for _, itemType in ipairs({ types.Lockpick, types.Probe, types.Repair }) do
        local seen = {}
        for _, obj in ipairs(inventory:getAll(itemType)) do
            if not seen[obj.recordId] then
                seen[obj.recordId] = true
                local item = items.fromObject(obj)
                if item then
                    list[#list + 1] = item
                end
            end
        end
    end
    return list
end

local function labelOf(entry)
    if entry.kind == 'unarmed' then
        return l10n('handToHand')
    end
    return entry.name or ''
end

local function iconResource(path)
    if not path then
        return nil
    end
    local ok, resource = pcall(ui.texture, { path = path })
    if ok then
        return resource
    end
    return nil
end

local function buildRow(entry, isSelected, y, width)
    local children = {}
    if isSelected then
        children[#children + 1] = {
            type = ui.TYPE.Image,
            props = { resource = white, color = rowBg, alpha = 0.25, relativeSize = v2(1, 1) },
        }
    end
    local icon = iconResource(entry.icon)
    if icon then
        children[#children + 1] = {
            type = ui.TYPE.Image,
            props = {
                resource = icon,
                size = v2(ICON, ICON),
                position = v2(8, (ROW_HEIGHT - ICON) / 2),
            },
        }
    end
    children[#children + 1] = {
        type = ui.TYPE.Text,
        props = {
            text = labelOf(entry),
            textSize = constants.textNormalSize,
            textColor = isSelected and header or sand,
            textShadow = true,
            autoSize = true,
            position = v2(ICON + 18, ROW_HEIGHT / 2),
            anchor = v2(0, 0.5),
        },
    }
    if entry.subtitle and entry.subtitle ~= '' then
        children[#children + 1] = {
            type = ui.TYPE.Text,
            props = {
                text = entry.subtitle,
                textSize = constants.textNormalSize,
                textColor = gold,
                textShadow = true,
                autoSize = true,
                relativePosition = v2(1, 0.5),
                anchor = v2(1, 0.5),
                position = v2(-10, 0),
            },
        }
    end
    return {
        type = ui.TYPE.Widget,
        props = { size = v2(width, ROW_HEIGHT), position = v2(0, y) },
        content = ui.content(children),
    }
end

-- ui.screenSize() is in pixels; layouts use GUI units, which differ when GUI scaling is on.
local function uiSize()
    if not ui.layers.indexOf('RadialMenu') then
        ui.layers.insertAfter('HUD', 'RadialMenu', { interactive = false })
    end
    local size = ui.layers[ui.layers.indexOf('RadialMenu')].size
    if size.x > 0 and size.y > 0 then
        return size
    end
    return ui.screenSize()
end

local function buildLayout()
    local screen = uiSize()
    local width = math.floor(math.min(screen.x * 0.6, 640))
    local fit = math.floor((screen.y * 0.92 - CHROME_HEIGHT) / ROW_HEIGHT)
    visibleRows = math.max(3, math.min(MAX_ROWS, fit))
    local listHeight = visibleRows * ROW_HEIGHT
    local first = math.max(1, math.min(selected - math.floor(visibleRows / 2), #entries - visibleRows + 1))
    local rows = {}
    for i = first, math.min(#entries, first + visibleRows - 1) do
        rows[#rows + 1] = buildRow(entries[i], i == selected, (i - first) * ROW_HEIGHT, width)
    end

    return {
        layer = 'RadialMenu',
        type = ui.TYPE.Widget,
        props = { relativeSize = v2(1, 1) },
        content = ui.content {
            {
                type = ui.TYPE.Image,
                props = { resource = white, color = util.color.rgb(0, 0, 0), alpha = 0.58, relativeSize = v2(1, 1) },
            },
            {
                type = ui.TYPE.Widget,
                props = {
                    size = v2(width, listHeight + CHROME_HEIGHT),
                    relativePosition = v2(0.5, 0.5),
                    anchor = v2(0.5, 0.5),
                },
                content = ui.content {
                    {
                        type = ui.TYPE.Image,
                        props = { resource = white, color = panelBg, alpha = 0.92, relativeSize = v2(1, 1) },
                    },
                    {
                        type = ui.TYPE.Text,
                        props = {
                            text = title,
                            textSize = constants.textHeaderSize,
                            textColor = header,
                            textShadow = true,
                            autoSize = true,
                            relativePosition = v2(0.5, 0),
                            anchor = v2(0.5, 0),
                            position = v2(0, 12),
                        },
                    },
                    {
                        type = ui.TYPE.Widget,
                        props = { size = v2(width, listHeight), position = v2(0, 48) },
                        content = ui.content(rows),
                    },
                    {
                        type = ui.TYPE.Text,
                        props = {
                            text = l10n('pickerHint'),
                            textSize = constants.textNormalSize,
                            textColor = sand,
                            textShadow = true,
                            autoSize = true,
                            relativePosition = v2(0.5, 1),
                            anchor = v2(0.5, 1),
                            position = v2(0, -12),
                        },
                    },
                },
            },
        },
    }
end

local function render()
    if element then
        element.layout = buildLayout()
        element:update()
    else
        element = ui.create(buildLayout())
    end
end

local function finish(result)
    if element then
        element:destroy()
        element = nil
    end
    local callback = onDone
    onDone = nil
    if callback then
        callback(result)
    end
end

local function move(delta)
    local target = math.max(1, math.min(#entries, selected + delta))
    if target ~= selected then
        selected = target
        ambient.playSound('menu click', { scale = false })
        render()
    end
end

local function direction()
    if pressed(B.DPadUp) then
        return -1
    elseif pressed(B.DPadDown) then
        return 1
    end
    local y = input.getAxisValue(input.CONTROLLER_AXIS.LeftY)
    if y < -STICK_DEADZONE then
        return -1
    elseif y > STICK_DEADZONE then
        return 1
    end
    return 0
end

local function update()
    if not onDone then
        return
    end
    local now = core.getRealTime()
    local dir = direction()
    if dir ~= heldDir then
        heldDir = dir
        if dir ~= 0 then
            move(dir)
            nextRepeat = now + REPEAT_DELAY
        end
    elseif dir ~= 0 and now >= nextRepeat then
        move(dir)
        nextRepeat = now + REPEAT_INTERVAL
    end

    if edge(B.LeftShoulder) then
        move(-visibleRows)
    end
    if edge(B.RightShoulder) then
        move(visibleRows)
    end
    if edge(B.B) then
        finish(nil)
    elseif edge(B.A) then
        local entry = entries[selected]
        if entry.kind == 'clear' then
            finish({ action = 'clear' })
        else
            finish({ action = 'item', item = entry })
        end
    end
end

local function open(heading, callback)
    entries = collectEntries()
    selected = 1
    title = heading
    onDone = callback
    heldDir = direction()
    nextRepeat = core.getRealTime() + REPEAT_DELAY
    syncButtons()
    render()
end

return {
    open = open,
    update = update,
    isOpen = function()
        return onDone ~= nil
    end,
    cancel = function()
        if onDone then
            finish(nil)
        end
    end,
}
