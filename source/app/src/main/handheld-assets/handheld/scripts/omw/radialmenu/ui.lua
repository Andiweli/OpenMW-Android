local ui = require('openmw.ui')
local util = require('openmw.util')
local constants = require('scripts.omw.mwui.constants')

local v2 = util.vector2
local white = constants.whiteTexture

local sand = constants.normalColor
local header = constants.headerColor
local dim = util.color.rgb(0, 0, 0)
local gold = util.color.rgb(0.92, 0.78, 0.36)
local slotBg = util.color.rgb(0.08, 0.07, 0.05)
local emptyBg = util.color.rgb(0.05, 0.05, 0.05)

local element = nil

local function ensureLayer()
    if not ui.layers.indexOf('RadialMenu') then
        ui.layers.insertAfter('HUD', 'RadialMenu', { interactive = false })
    end
end

-- ui.screenSize() is in pixels; layouts use GUI units, which differ when GUI scaling is on.
local function uiSize()
    ensureLayer()
    local size = ui.layers[ui.layers.indexOf('RadialMenu')].size
    if size.x > 0 and size.y > 0 then
        return size
    end
    return ui.screenSize()
end

local function metrics()
    local screen = uiSize()
    local wheel = math.floor(math.min(screen.x, screen.y) * 0.94)
    return {
        wheel = wheel,
        radius = wheel * 0.36,
        slot = 52,
        -- Morrowind icons are 32x32; drawing them larger makes them blurry.
        icon = 32,
        text = math.max(16, constants.textNormalSize),
        header = math.max(18, constants.textHeaderSize),
    }
end

local function slotPosition(index, count, m)
    local angle = (index - 1) / count * math.pi * 2 - math.pi / 2
    local cx = m.wheel * 0.5
    local cy = m.wheel * 0.5
    return v2(cx + math.cos(angle) * m.radius, cy + math.sin(angle) * m.radius)
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

local function buildSlot(item, index, count, selected, m)
    local empty = not item or item.kind == 'empty' or item.kind == 'missing'
    local size = selected and (m.slot + 6) or m.slot
    local children = {
        {
            type = ui.TYPE.Image,
            props = {
                resource = white,
                color = selected and gold or (empty and emptyBg or slotBg),
                alpha = selected and 0.92 or (empty and 0.55 or 0.8),
                relativeSize = v2(1, 1),
            },
        },
        {
            type = ui.TYPE.Text,
            props = {
                text = tostring(index),
                textSize = selected and (m.text + 4) or m.text,
                textColor = selected and header or sand,
                textAlignH = ui.ALIGNMENT.Center,
                textShadow = true,
                autoSize = true,
                relativePosition = v2(0.5, 0),
                anchor = v2(0.5, 0),
                position = v2(0, 4),
            },
        },
    }
    local resource = item and iconResource(item.icon)
    if resource then
        children[#children + 1] = {
            type = ui.TYPE.Image,
            props = {
                resource = resource,
                size = v2(m.icon, m.icon),
                relativePosition = v2(0.5, 0.5),
                anchor = v2(0.5, 0.5),
                position = v2(0, 6),
            },
        }
    end
    return {
        type = ui.TYPE.Widget,
        props = {
            size = v2(size, size),
            position = slotPosition(index, count, m),
            anchor = v2(0.5, 0.5),
        },
        content = ui.content(children),
    }
end

local function buildLayout(state)
    local m = metrics()
    local pageItems = state.pageItems
    local selected = state.selected
    local count = math.max(#pageItems, 1)
    local slots = {}
    for i = 1, #pageItems do
        slots[#slots + 1] = buildSlot(pageItems[i], i, count, i == selected, m)
    end

    local title = state.title or ''
    local subtitle = ''
    if selected and pageItems[selected] then
        title = pageItems[selected].name or title
        subtitle = pageItems[selected].subtitle or ''
    end

    local pageText = ''
    if state.pageCount and state.pageCount > 1 then
        pageText = string.format('%d / %d', state.page, state.pageCount)
    end

    return {
        layer = 'RadialMenu',
        type = ui.TYPE.Widget,
        props = {
            relativeSize = v2(1, 1),
            visible = true,
        },
        content = ui.content {
            {
                type = ui.TYPE.Image,
                props = {
                    resource = white,
                    color = dim,
                    alpha = 0.58,
                    relativeSize = v2(1, 1),
                },
            },
            {
                type = ui.TYPE.Widget,
                props = {
                    size = v2(m.wheel, m.wheel),
                    relativePosition = v2(0.5, 0.5),
                    anchor = v2(0.5, 0.5),
                },
                content = ui.content(slots),
            },
            {
                type = ui.TYPE.Text,
                props = {
                    text = state.modeTitle or '',
                    textSize = m.header,
                    textColor = header,
                    textAlignH = ui.ALIGNMENT.Center,
                    textShadow = true,
                    autoSize = true,
                    relativePosition = v2(0.5, 0.5),
                    anchor = v2(0.5, 0.5),
                    position = v2(0, -44),
                },
            },
            {
                type = ui.TYPE.Text,
                props = {
                    text = title,
                    textSize = m.text + 2,
                    textColor = sand,
                    textAlignH = ui.ALIGNMENT.Center,
                    textShadow = true,
                    autoSize = true,
                    relativePosition = v2(0.5, 0.5),
                    anchor = v2(0.5, 0.5),
                    position = v2(0, -10),
                },
            },
            {
                type = ui.TYPE.Text,
                props = {
                    text = subtitle,
                    textSize = m.text,
                    textColor = gold,
                    textAlignH = ui.ALIGNMENT.Center,
                    textShadow = true,
                    autoSize = true,
                    relativePosition = v2(0.5, 0.5),
                    anchor = v2(0.5, 0.5),
                    position = v2(0, 16),
                },
            },
            {
                type = ui.TYPE.Text,
                props = {
                    text = pageText,
                    textSize = m.text,
                    textColor = sand,
                    textAlignH = ui.ALIGNMENT.Center,
                    textShadow = true,
                    autoSize = true,
                    relativePosition = v2(0.5, 0.5),
                    anchor = v2(0.5, 0.5),
                    position = v2(0, 40),
                },
            },
            {
                type = ui.TYPE.Text,
                props = {
                    text = state.hint or '',
                    textSize = m.text,
                    textColor = sand,
                    textAlignH = ui.ALIGNMENT.Center,
                    textShadow = true,
                    autoSize = true,
                    relativePosition = v2(0.5, 1),
                    anchor = v2(0.5, 1),
                    position = v2(0, -22),
                },
            },
        },
    }
end

local function hide()
    if element then
        element:destroy()
        element = nil
    end
end

local function show(state)
    ensureLayer()
    hide()
    element = ui.create(buildLayout(state))
end

local function refresh(state)
    if not element then
        show(state)
        return
    end
    element.layout = buildLayout(state)
    element:update()
end

return {
    show = show,
    hide = hide,
    refresh = refresh,
    visible = function()
        return element ~= nil
    end,
}
