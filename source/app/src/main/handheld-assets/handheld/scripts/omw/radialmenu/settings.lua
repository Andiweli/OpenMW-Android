local input = require('openmw.input')
local storage = require('openmw.storage')
local I = require('openmw.interfaces')

-- MENU and PLAYER share the action registry, so the second context must not register again.
if not input.actions.RadialMenu then
    input.registerAction {
        key = 'RadialMenu',
        type = input.ACTION_TYPE.Boolean,
        l10n = 'OMWRadialMenu',
        name = 'open_name',
        description = 'open_description',
        defaultValue = false,
    }
end

I.Settings.registerPage({
    key = 'OMWRadialMenu',
    l10n = 'OMWRadialMenu',
    name = 'Page',
    description = 'PageDescription',
})

I.Settings.registerGroup({
    key = 'SettingsOMWRadialMenu',
    page = 'OMWRadialMenu',
    l10n = 'OMWRadialMenu',
    name = 'Group',
    permanentStorage = true,
    settings = {
        {
            key = 'enabled',
            renderer = 'checkbox',
            name = 'enabled',
            description = 'enabledDescription',
            default = true,
        },
        {
            key = 'radialOpenBinding',
            renderer = 'inputBinding',
            name = 'openBinding',
            description = 'openBindingDescription',
            default = 'radialOpenBinding',
            argument = {
                key = 'RadialMenu',
                type = 'action',
            },
        },
    },
})

local bindings = storage.playerSection('OMWInputBindings')
local current = bindings:get('radialOpenBinding')
if current == nil
    or (current.device == 'controller' and current.button == input.CONTROLLER_BUTTON.DPadUp)
then
    bindings:set('radialOpenBinding', {
        device = 'controller',
        button = input.CONTROLLER_BUTTON.LeftShoulder,
        type = 'action',
        key = 'RadialMenu',
    })
end
