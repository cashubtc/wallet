package com.cashu.me.Core.Services

import com.cashu.me.Core.WalletManager

/**
 * Compatibility anchor for Swift `LightningService.swift`.
 *
 * Android routes mint quotes, melt quotes, BOLT12/on-chain subscriptions, and
 * payment execution through `WalletManager` plus `CdkWalletGateway`.
 */
typealias LightningService = WalletManager
