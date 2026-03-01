package com.kernel.browser.extensions

import com.kernel.browser.extensions.models.KernelExtension

object RecommendedExtensions {

    val list = listOf(
        KernelExtension(
            id = "uBlock0@raymondhill.net",
            name = "uBlock Origin",
            description = "An efficient wide-spectrum content blocker. Blocks ads, trackers, and malware sites.",
            amoUrl = "https://addons.mozilla.org/android/addon/ublock-origin/",
            iconUrl = "https://addons.mozilla.org/user-media/addon_icons/607/607454-64.png",
        ),
        KernelExtension(
            id = "addon@darkreader.org",
            name = "Dark Reader",
            description = "Dark mode for every website. Protects your eyes with a dark theme across the web.",
            amoUrl = "https://addons.mozilla.org/android/addon/darkreader/",
            iconUrl = "https://addons.mozilla.org/user-media/addon_icons/879/879551-64.png",
        ),
        KernelExtension(
            id = "firefox@tampermonkey.net",
            name = "Tampermonkey",
            description = "Userscript manager. Run custom scripts on any website.",
            amoUrl = "https://addons.mozilla.org/android/addon/tampermonkey/",
            iconUrl = "https://addons.mozilla.org/user-media/addon_icons/683/683490-64.png",
        ),
        KernelExtension(
            id = "jid1-MnnxcxisBPnSXQ@jetpack",
            name = "Privacy Badger",
            description = "Automatically learns to block invisible trackers.",
            amoUrl = "https://addons.mozilla.org/android/addon/privacy-badger17/",
            iconUrl = "https://addons.mozilla.org/user-media/addon_icons/506/506646-64.png",
        ),
        KernelExtension(
            id = "{446900e4-71c2-419f-a6a7-df9c091e268b}",
            name = "Bitwarden",
            description = "A secure and free password manager for all of your devices.",
            amoUrl = "https://addons.mozilla.org/android/addon/bitwarden-password-manager/",
            iconUrl = "https://addons.mozilla.org/user-media/addon_icons/993/993188-64.png",
        ),
        KernelExtension(
            id = "https-everywhere@eff.org",
            name = "HTTPS Everywhere",
            description = "Automatically use HTTPS security on many sites.",
            amoUrl = "https://addons.mozilla.org/android/addon/https-everywhere/",
            iconUrl = "https://addons.mozilla.org/user-media/addon_icons/229/229918-64.png",
        ),
    )
}
