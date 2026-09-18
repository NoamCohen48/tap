package com.company.tap.driver.engine

/**
 * Thrown from [CommandContext.checkpoint] or [CommandContext.markMutationStarted] when a command
 * must stop before mutating. The pipeline converts it into the terminal response for [errorCode].
 */
class CommandInterrupted(val errorCode: String) : RuntimeException(errorCode)
