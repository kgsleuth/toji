package io.toji

import java.nio.file.Paths
import scala.sys.process.*
import scala.util.Try

object Main:
  val Version = "0.1.3 (Scala Native)"

  def main(args: Array[String]): Unit =
    val code =
      try parseAndRun(args.toList)
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
        Installer.selfUpdate(force = hasFlag(rest, "--force"))
        Exit.Ok
      case "update" :: rest =>
        val force = hasFlag(rest, "--force")
        val target = rest.find(a => !a.startsWith("-")).getOrElse("toji")
        if target == "toji" || target == "self" then
          Installer.selfUpdate(force)
        else
          Installer.install(target, "latest", force)
        Exit.Ok
      case "install" :: rest =>
        handleInstall(rest)
      case "search" :: q =>
        handleSearch(q.mkString(" "))
      case "list" :: _ | "tools" :: _ =>
        handleList()
      case "auth" :: _ =>
        println(s"GitHub auth: ${Auth.describe}")
        if Auth.current == Auth.None then
          println("To access private repos, run: gh auth login")
          println("Or: export TOJI_GITHUB_TOKEN=ghp_...")
        Exit.Ok
      case "which" :: tool :: _ =>
        handleWhich(tool)
      case _ =>
        printHelp()
        Exit.Usage

  private def hasFlag(args: List[String], f: String): Boolean = args.contains(f)

  private def handleInstall(rest: List[String]): Int =
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
    println("Known tools (add more in ToolRegistry.scala or install by owner/repo directly):")
    ToolRegistry.all.foreach { t =>
      val als = if t.aliases.nonEmpty then s" [${t.aliases.mkString(", ")}]" else ""
      println(s"  ${t.name}$als  →  ${t.repo}")
    }
    println("\nAlso works for any repo you have access to:")
    println("  toji install kgsleuth/some-private-cli")
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
  toji --update [--force]              # self-update this tool
  toji update [tool] [--force]         # update a tool (or 'toji')

  toji install <name|owner/repo> [vX.Y] [--force] [--dir DIR]
  toji search <query>
  toji list
  toji tools

  toji auth                            # show current gh / token status
  toji which <tool>

Examples:
  toji install curtain
  toji install kgsleuth/shikigami v0.2.0
  toji install owner/private-tool --force
  toji search cli
  toji update shikigami

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
""")
