package com.cashu.me.Core

import org.cashudevkit.CurrencyUnit as CdkCurrencyUnit

internal fun CdkCurrencyUnit.toDomainUnit(): String = when (this) {
    CdkCurrencyUnit.Sat -> "sat"
    CdkCurrencyUnit.Msat -> "msat"
    CdkCurrencyUnit.Usd -> "usd"
    CdkCurrencyUnit.Eur -> "eur"
    CdkCurrencyUnit.Auth -> "auth"
    is CdkCurrencyUnit.Custom -> unit
}
