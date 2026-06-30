package io.toji.cli
import io.toji.infrastructure._

import java.nio.file.Paths
import scala.sys.process.*
import scala.util.Try

object Main:
  val Version = "0.1.3 (Scala Native)"

  def main(args: Array[String]): Unit =
    val code =
      try
        if !isMutatingCommand(args.toList) then Installer.maybeAutoUpdate()
        parseAndRun(args.toList)
      catch
        case e: ValidationError =>
          System.err.println(s"error: ${e.message}")
          e.exitCode
        case e: TojiError =>
          System.err.println(s"error: ${e.message}")
          e.exitCode
        case e: Exception =>
          System.err.println(s"error: ${e.getMessage}")
          if GlobalOpts().verbose then e.printStackTrace()
          Exit.Validation
    sys.exit(code)

  private def parseAndRun(args: List[String]): Int =
    args match
      case Nil | "--help" :: Nil | "-h" :: Nil =>
        printHelp()
        Exit.Ok
      case "--version" :: Nil | "-V" :: Nil =>
        println(s"toji $Version")
        Exit.Ok
      case "--update" :: rest =>
        Installer.selfUpdate(force = hasFlag(rest, "--force"), ignoreInterval = true)
        Exit.Ok
      case "update" :: rest =>
        val force = hasFlag(rest, "--force")
        if hasFlag(rest, "--all") then
          Installer.updateAll(force, dueOnly = hasFlag(rest, "--due-only"))
        else
          val target = rest.find(a => !a.startsWith("-")).getOrElse("toji")
          if target == "toji" || target == "self" then
            Installer.selfUpdate(force, ignoreInterval = true)
          else
            Installer.install(target, "latest", force, ignoreInterval = true)
        Exit.Ok
      case "install" :: rest =>
        handleInstall(rest)
      case "available" :: _ =>
        handleAvailable()
      case "search" :: q =>
        handleSearch(q.mkString(" "))
      case "list" :: rest =>
        if hasFlag(rest, "--available") then handleAvailable() else handleList()
      case "tools" :: rest =>
        if hasFlag(rest, "--available") then handleAvailable() else handleList()
      case "auth" :: _ =>
        println(s"GitHub auth: ${Auth.describe}")
        if Auth.current == Auth.None then
          println("To access private repos, run: gh auth login")
          println("Or: export TOJI_GITHUB_TOKEN=ghp_...")
        Exit.Ok
      case "which" :: tool :: _ =>
        handleWhich(tool)
      case "registry" :: "refresh" :: _ =>
        ToolRegistry.refresh()
        println(s"Registry refreshed (${ToolRegistry.all.size} tools).")
        Exit.Ok
      case "registry" :: "scan" :: rest =>
        val org = rest.find(!_.startsWith("-")).getOrElse(Catalog.defaultOrg)
        val merged = Catalog.scanAndMergeRegistry(org)
        println(s"Registry scan ($org): ${merged.size} tools in cache.")
        println("Run: toji available")
        Exit.Ok
      case "registry" :: "show" :: _ =>
        println(ToolRegistry.registryJson())
        Exit.Ok
      case "registry" :: _ =>
        println("Usage: toji registry refresh|scan [org]|show")
        Exit.Usage
      case _ =>
        printHelp()
        Exit.Usage

  private def hasFlag(args: List[String], f: String): Boolean = args.contains(f)

  private def isMutatingCommand(args: List[String]): Boolean =
    args match
      case Nil => false
      case ("install" | "update" | "--update") :: _ => true
      case _ => false

  private def handleInstall(rest: List[String]): Int =
    if hasFlag(rest, "--all") then
      return Installer.installAll(hasFlag(rest, "--force"))
    // install <name|owner/repo> [version] [--force] [--dir /path]
    var target: Option[String] = None
    var ver = "latest"
    var force = false
    var dir: Option[String] = None
    val it = rest.iterator
    while it.hasNext do
      it.next() match
        case "--force" | "-f" => force = true
        case "--dir" | "-d" if it.hasNext => dir = Some(it.next())
        case v if v.startsWith("v") || v.matches("""\d.*""") => ver = v
        case other if target.isEmpty => target = Some(other)
        case _ => ()
    target match
      case None =>
        println("toji install <name|owner/repo> [version] [--force]")
        Exit.Usage
      case Some(t) =>
        val d = dir.map(Paths.get(_))
        val res = Installer.install(t, ver, force, d)
        val action = if res.wasUpdate then "updated" else "installed"
        println(s"$action ${res.tool} ${res.version}")
        println(s"binary: ${res.dest}")
        Exit.Ok

  private def handleSearch(q: String): Int =
    val results = ToolRegistry.search(q)
    if results.isEmpty then
      println(s"No tools matched \"$q\".")
      println("Try: toji list")
      println("Or install directly: toji install owner/repo")
      Exit.Ok
    else
      println(s"Found ${results.size} tool(s):")
      results.foreach { t =>
        val als = if t.aliases.nonEmpty then s" (aliases: ${t.aliases.mkString(",")})" else ""
        println(s"  ${t.name}${als}  —  ${t.repo}")
        println(s"      ${t.description}")
      }
      println("\nInstall with: toji install <name>")
      Exit.Ok

  private def handleList(): Int =
    println("Registry tools (names only — use 'toji available' to probe GitHub releases):")
    ToolRegistry.all.foreach { t =>
      val als = if t.aliases.nonEmpty then s" [${t.aliases.mkString(", ")}]" else ""
      println(s"  ${t.name}$als  →  ${t.repo}")
    }
    println("\nAlso works for any repo you have access to:")
    println("  toji install kgsleuth/some-private-cli")
    println("\nRegistry: toji registry refresh | scan | show")
    println("Releases: toji available")
    Exit.Ok

  private def handleAvailable(): Int =
    if Auth.current == Auth.None then
      println("GitHub auth: none — some private repos may show as inaccessible.")
      println("Run: gh auth login\n")
    val platformHint = Platform.describeCurrentPlatform()
    val entries = Catalog.listAvailable()
    val installable = entries.count(e => e.status == Catalog.Status.Available)
    println(s"Tools with release assets matching $platformHint ($installable installable):\n")
    println(f"  ${"NAME"}%-14s${"RELEASE"}%-11s${"STATUS"}%-22sREPO")
    entries.foreach(e => println(Catalog.formatEntryLine(e)))
    println("\nInstall:  toji install <name>  |  toji install --all")
    println("Update:   toji update --all")
    Exit.Ok

  private def handleWhich(tool: String): Int =
    val base = ToolRegistry.baseFor(tool)
    Try {
      val out = Process(Seq("sh", "-c", s"command -v $base")).!!.trim
      println(out)
      Exit.Ok
    }.getOrElse {
      println(s"$base not found on PATH")
      Exit.NotFound
    }

  private def printHelp(): Unit =
    println(s"""toji $Version

Cursed tool installer for private & public GitHub Release CLIs.
Stands in the gap: use your local `gh` auth or PAT to install tools
from repos you have access to — no secret sharing required.

Usage:
  toji --version
  toji update [tool|--all] [--force]   # update one tool or all toji-managed installs
  toji --update [--force]              # self-update (always checks GitHub; --force reinstalls same version)

  toji install <name|owner/repo> [vX.Y] [--force] [--dir DIR]
  toji install --all [--force]         # install every available registry tool
  toji available                       # list registry tools + release status
  toji list [--available]              # registry names, or probe GitHub
  toji search <query>
  toji registry refresh                # pull registry.json from toji repo
  toji registry scan [org]             # discover org repos with releases → local cache
  toji registry show                   # print cached registry JSON

  toji auth                            # show current gh / token status
  toji which <tool>

Examples:
  toji install curtain
  toji install kgsleuth/shikigami v0.2.0
  toji install owner/private-tool --force
  toji search cli
  toji update shikigami
  toji update --all                    # refresh every tool toji has installed

Auto-updates:
  Any routine toji command (list, search, auth, …) may background-check installed
  tools older than TOJI_UPDATE_INTERVAL_DAYS (default 7). Or schedule:

  0 9 * * 0 toji update --all

Auth (private repos):
  Preferred: gh auth login
  Fallback:  export TOJI_GITHUB_TOKEN=ghp_...   (repo scope)

  The installer re-uses your gh session or token automatically.

Environment:
  TOJI_INSTALL_DIR          default: ~/.local/bin
  TOJI_STATE_DIR            default: ~/.local/state/toji
  TOJI_UPDATE_INTERVAL_DAYS default: 7
  TOJI_GITHUB_TOKEN         or GITHUB_TOKEN / GH_TOKEN

Install (first time, public releases):
  curl -fsSL https://raw.githubusercontent.com/kgsleuth/toji/main/scripts/install.sh | bash

The registry of friendly tool names lives in registry.json in this repo and is fetched dynamically.
""")
