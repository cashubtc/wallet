package com.cashu.me.Core.CDK

import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.AdvertisedPaymentMethod
import org.cashudevkit.CurrencyUnit as CdkCurrencyUnit
import org.cashudevkit.Nuts as CdkNuts
import org.cashudevkit.PaymentMethod as CdkPaymentMethod

/** Preserve reported-empty directions and method/unit identity. */
internal fun CdkNuts.reportedMintMethods(): List<PaymentMethodKind> =
    PaymentMethodKind.ordered(reportedMintSettings().map { it.method })

internal fun CdkNuts.reportedMintSettings(): List<AdvertisedPaymentMethod> =
    if (nut04.disabled) emptyList() else nut04.methods.mapNotNull { entry ->
        entry.method.toKnownPaymentMethodKind()?.let { method ->
            AdvertisedPaymentMethod(method, entry.unit.toStringUnit(), entry.methodName,
                entry.minAmount?.value?.coerceAtMost(Long.MAX_VALUE.toULong())?.toLong(),
                entry.maxAmount?.value?.coerceAtMost(Long.MAX_VALUE.toULong())?.toLong())
        }
    }.distinctBy { it.method to it.unit }

internal fun CdkNuts.reportedMeltSettings(): List<AdvertisedPaymentMethod> =
    if (nut05.disabled) emptyList() else nut05.methods.mapNotNull { entry ->
        entry.method.toKnownPaymentMethodKind()?.let { method ->
            AdvertisedPaymentMethod(method, entry.unit.toStringUnit(), entry.methodName,
                entry.minAmount?.value?.coerceAtMost(Long.MAX_VALUE.toULong())?.toLong(),
                entry.maxAmount?.value?.coerceAtMost(Long.MAX_VALUE.toULong())?.toLong())
        }
    }.distinctBy { it.method to it.unit }

private fun CdkCurrencyUnit.toStringUnit(): String = when (this) {
    CdkCurrencyUnit.Sat -> "sat"
    CdkCurrencyUnit.Msat -> "msat"
    CdkCurrencyUnit.Usd -> "usd"
    CdkCurrencyUnit.Eur -> "eur"
    CdkCurrencyUnit.Auth -> "auth"
    is CdkCurrencyUnit.Custom -> unit
}

/**
 * True when any NUT-04 bolt12 method advertises `description: true`. Null or
 * false on every bolt12 method (or no bolt12 method at all) fails closed —
 * the Receive Description row must not appear unless the mint said so.
 */
internal fun CdkNuts.reportsBolt12MintDescription(): Boolean =
    nut04.methods.any {
        it.method.toKnownPaymentMethodKind() == PaymentMethodKind.Bolt12 &&
            it.description == true
    }

/** Built-in payments remain sat-only; custom methods carry their advertised unit. */
internal fun CdkNuts.reportedMeltMethods(): List<PaymentMethodKind> =
    PaymentMethodKind.ordered(reportedMeltSettings().filter { it.unit == "sat" || it.method.isCustom }.map { it.method })

private fun CdkPaymentMethod.toKnownPaymentMethodKind(): PaymentMethodKind? = when (this) {
    CdkPaymentMethod.Bolt11 -> PaymentMethodKind.Bolt11
    CdkPaymentMethod.Bolt12 -> PaymentMethodKind.Bolt12
    CdkPaymentMethod.Onchain -> PaymentMethodKind.Onchain
    is CdkPaymentMethod.Custom -> PaymentMethodKind.fromRaw(method)
}
