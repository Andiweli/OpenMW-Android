local I = require('openmw.interfaces')

I.Settings.registerPage({
    key = 'OMWCheats',
    l10n = 'OMWCheats',
    name = 'Page',
    description = 'PageDescription',
})

I.Settings.registerGroup({
    key = 'SettingsOMWCheats',
    page = 'OMWCheats',
    l10n = 'OMWCheats',
    name = 'Group',
    -- Keeps the chosen amount across saves.
    permanentStorage = true,
    settings = {
        {
            key = 'amount',
            renderer = 'number',
            name = 'amount',
            description = 'amountDescription',
            default = 1000,
        },
        {
            -- Acts as a button: player.lua resets it once global.lua confirms the gold was added.
            key = 'giveGold',
            renderer = 'checkbox',
            name = 'giveGold',
            description = 'giveGoldDescription',
            default = false,
        },
    },
})
