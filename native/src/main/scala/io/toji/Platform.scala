package io.toji

import scala.sys.process.*

object Platform:
  case class AssetName(value: String)

  def detectAsset(tool: String): AssetName =
    val os = runOut("uname", "-s")
    val arch = runOut("uname", "-m")
    val base = tool.toLowerCase.replaceAll("[^a-z0-9-]", "-")
    val asset = (os, arch) match
      case ("Linux", "x86_64" | "amd64")  => s"$base-linux-x86_64"
      case ("Linux", "aarch64" | "arm64") => s"$base-linux-aarch64"
      case ("Darwin", "x86_64")           => s"$base-macos-x86_64"
      case ("Darwin", "arm64" | "aarch64")=> s"$base-macos-aarch64"
      case _ =>
        throw ValidationError(
          s"""unsupported platform: $os $arch

Supported:
  Linux x86_64 / amd64
  Linux aarch64 / arm64
  macOS Intel (x86_64)
  macOS Apple Silicon (arm64)

See toji --help"""
        )
    AssetName(asset)

  private def runOut(args: String*): String = Process(args.toSeq).!!.trim
