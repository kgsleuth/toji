package io.toji

class TojiError(val message: String, val exitCode: Int = 6) extends Exception(message)

class ValidationError(message: String) extends TojiError(message, 6)

object Exit:
  val Ok = 0
  val Usage = 2
  val Validation = 6
  val NotFound = 4
  val Fail = 1
