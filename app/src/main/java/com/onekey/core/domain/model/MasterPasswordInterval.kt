package com.onekey.core.domain.model

enum class MasterPasswordInterval(val hours: Int, val label: String) {
    HOURS_48(48, "48 hours"),
    HOURS_72(72, "72 hours");

    val millis: Long get() = hours * 3_600_000L
}
