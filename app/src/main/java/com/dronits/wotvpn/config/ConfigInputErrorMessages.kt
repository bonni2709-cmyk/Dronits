package com.dronits.wotvpn.config

import androidx.annotation.StringRes
import com.dronits.wotvpn.R

@get:StringRes
internal val ConfigInputError.messageRes: Int
    get() = when (this) {
        ConfigInputError.Empty -> R.string.error_config_empty
        ConfigInputError.TooLong -> R.string.error_config_too_long
        ConfigInputError.MultipleLinesOrControls -> R.string.error_config_single_link
        ConfigInputError.UnsupportedScheme -> R.string.error_config_scheme
        ConfigInputError.MissingPayload -> R.string.error_config_payload
    }
