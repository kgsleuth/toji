package io.toji

object Output:
  def emitText(s: String): Unit = println(s)

  def emitJson(value: ujson.Value): Unit =
    println(upickle.default.write(value, indent = 2))

  def emitForOpts(opts: GlobalOpts)(json: => ujson.Value, text: => String): Unit =
    if opts.json then emitJson(json) else emitText(text)

  def emitError(msg: String): Unit = System.err.println(s"error: $msg")

  def emitSuccess(msg: String): Unit = println(s"ok: $msg")
