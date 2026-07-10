package com.cashu.me.Core.Services

import com.cashu.me.Core.WalletManager

/**
 * Compatibility anchor for Swift `TokenService.swift`.
 *
 * Android keeps ecash send/receive, fee preview, spendability checks, P2PK
 * signing-key lookup, and key-usage marking in `WalletManager`/`SettingsManager`.
 */
typealias TokenService = WalletManager
