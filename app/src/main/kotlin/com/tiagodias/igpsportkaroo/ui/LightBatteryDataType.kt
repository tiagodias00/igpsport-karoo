package com.tiagodias.igpsportkaroo.ui

import io.hammerhead.karooext.extension.DataTypeImpl

/** Numeric field: values stream from the paired device (IgpsExtension.connectDevice); Karoo renders them. */
class LightBatteryDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    companion object {
        const val TYPE_ID = "light-battery"
    }
}
