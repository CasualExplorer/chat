package com.casualexplorer.chat.ui

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** Light and dark previews, as Now in Android's ThemePreviews. */
@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ThemePreviews

/** Phone, foldable and tablet previews, as Now in Android's DevicePreviews. */
@Preview(name = "Phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "Foldable", device = "spec:width=673dp,height=841dp")
@Preview(name = "Tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
annotation class DevicePreviews
