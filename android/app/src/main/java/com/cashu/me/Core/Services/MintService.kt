package com.cashu.me.Core.Services

import com.cashu.me.Core.WalletManager

/**
 * Compatibility anchor for Swift `MintService.swift`.
 *
 * Android keeps mint orchestration in `WalletManager` and CDK-specific calls in
 * `CdkWalletGateway` so wallet state updates stay transactional.
 */
typealias MintService = WalletManager
