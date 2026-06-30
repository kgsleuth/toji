package io.toji.cli

case class GlobalOpts(
  json: Boolean = false,
  force: Boolean = false,
  verbose: Boolean = false,
  dryRun: Boolean = false,
  update: Boolean = false,
)
