package com.company.tap.driver.engine

import com.company.tap.protocol.ErrorCode

/**
 * Thrown from [CommandContext.checkpoint] or [CommandContext.markMutationStarted] when a command
 * must stop before mutating. The pipeline converts it into the terminal response for [errorCode].
 */
class CommandInterrupted(val errorCode: ErrorCode, val detail: String? = null) : RuntimeException(errorCode.name)
