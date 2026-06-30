package io.toji.infrastructure
import io.toji.cli._

import scala.sys.process.*
import scala.util.Try

/** Detects GitHub authentication for accessing private repos.
  *
  * Preference order:
  * 1. gh CLI (local `gh auth` session) - best for users who ran `gh auth login`
  * 2. <TOOL>_GITHUB_TOKEN / GITHUB_TOKEN / GH_TOKEN env var (PAT)
  */
object Auth:

  sealed trait GitHubAuth
  case object GhCli extends GitHubAuth
  case class Token(value: String) extends GitHubAuth
  case object None extends GitHubAuth

  def current: GitHubAuth =
    if ghAvailableAndAuthed then GhCli
    else tokenFromEnv.map(Token.apply).getOrElse(None)

  def ghAvailableAndAuthed: Boolean =
    ghBin.isDefined && Try {
      val gh = ghBin.get
      val status = Process(Seq(gh, "auth", "status", "-h", "github.com")).!(ProcessLogger(_ => (), _ => ()))
      if status == 0 then true
      else Process(Seq(gh, "api", "user", "--silent")).!(ProcessLogger(_ => (), _ => ())) == 0
    }.getOrElse(false)

  def hasGh: Boolean = ghBin.isDefined

  /** Absolute path to gh when on PATH (Scala Native may not inherit a full shell PATH). */
  def ghBin: Option[String] =
    Try {
      val out = new StringBuilder
      val code = Process(Seq("sh", "-c", "command -v gh")).!(ProcessLogger(out.append(_), _ => ()))
      if code == 0 then Option(out.toString.trim).filter(_.nonEmpty) else scala.None
    }.getOrElse(scala.None)

  /** Run gh with captured stdout when the local gh session is active. */
  def runGh(args: Seq[String]): Option[String] =
    if current != GhCli then scala.None
    else
      ghBin.flatMap { gh =>
        Try {
          val out = new StringBuilder
          val code = Process(Seq(gh) ++ args).!(ProcessLogger(out.append(_), _ => ()))
          if code == 0 then Some(out.toString.trim).filter(_.nonEmpty) else scala.None
        }.getOrElse(scala.None)
      }

  /** Run gh and return whether it exited 0. */
  def runGhQuiet(args: Seq[String]): Boolean =
    if current != GhCli then false
    else
      ghBin.exists { gh =>
        Try(Process(Seq(gh) ++ args).!(ProcessLogger(_ => (), _ => ())) == 0).getOrElse(false)
      }

  private def tokenFromEnv: Option[String] =
    // Prefer tool-specific, then common GH names
    Seq("TOJI_GITHUB_TOKEN", "TOJI_TOKEN", "GITHUB_TOKEN", "GH_TOKEN")
      .view
      .flatMap(k => Option(System.getenv(k)).filter(_.trim.nonEmpty))
      .headOption

  def token: Option[String] = tokenFromEnv

  def getToken: Option[String] =
    current match
      case GhCli =>
        ghBin.flatMap { gh =>
          Try(Process(Seq(gh, "auth", "token")).!!).toOption.map(_.trim).filter(_.nonEmpty)
        }
      case Token(t) => Some(t)
      case Auth.None => scala.None

  /** Returns curl -H args for Authorization if we have a token (gh cli does not need it) */
  def curlAuthArgs: Seq[String] =
    getToken.toSeq.flatMap(t => Seq("-H", s"Authorization: Bearer $t"))

  /** For gh commands: if GhCli, we can just call gh. If token, gh can take --with-token but we prefer env. */
  def describe: String =
    current match
      case GhCli      => "gh (local session)"
      case Token(_)   => "PAT from env"
      case Auth.None => "none (public only)"

  /** Convenience: fetch json via gh api or curl+token. */
  def apiGet(path: String): String =
    current match
      case GhCli =>
        ghBin.fold(throw ValidationError("gh is not on PATH"))(gh => Process(Seq(gh, "api", path)).!!)
      case Token(t) =>
        val url = if path.startsWith("http") then path else s"https://api.github.com$path"
        val cmd = Seq("curl", "-fsSL") ++ curlAuthArgs ++ Seq(url)
        Process(cmd).!!
      case Auth.None =>
        val url = if path.startsWith("http") then path else s"https://api.github.com$path"
        Process(Seq("curl", "-fsSL", url)).!!
