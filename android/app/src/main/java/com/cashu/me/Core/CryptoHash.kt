package com.cashu.me.Core

import java.security.MessageDigest

fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
