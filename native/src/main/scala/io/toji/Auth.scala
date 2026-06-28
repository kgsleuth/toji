package io.toji

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
    Try {
      // Use --silent + capture to avoid printing the pretty table
      val status = Process(Seq("gh", "auth", "status", "-h", "github.com")).!(ProcessLogger(_ => (), _ => ()))
      if status == 0 then true
      else
        // fallback: lightweight authed call, silence output
        Process(Seq("gh", "api", "user", "--silent")).!(ProcessLogger(_ => (), _ => ())) == 0
    }.getOrElse(false)

  def hasGh: Boolean =
    Try(Process(Seq("sh", "-c", "command -v gh >/dev/null 2>&1")).! == 0).getOrElse(false)

  private def tokenFromEnv: Option[String] =
    // Prefer tool-specific, then common GH names
    Seq("TOJI_GITHUB_TOKEN", "TOJI_TOKEN", "GITHUB_TOKEN", "GH_TOKEN")
      .view
      .flatMap(k => Option(System.getenv(k)).filter(_.trim.nonEmpty))
      .headOption

  def token: Option[String] = tokenFromEnv

  /** Returns curl -H args for Authorization if we have a token (gh cli does not need it) */
  def curlAuthArgs: Seq[String] =
    current match
      case Token(t) => Seq("-H", s"Authorization: Bearer $t")
      case _        => Nil

  /** For gh commands: if GhCli, we can just call gh. If token, gh can take --with-token but we prefer env. */
  def describe: String =
    current match
      case GhCli      => "gh (local session)"
      case Token(_)   => "PAT from env"
      case None       => "none (public only)"

  /** Run a gh command if gh auth available, else None. */
  def runGh(args: Seq[String]): Option[String] =
    if current == GhCli then
      Try(Process(Seq("gh") ++ args).!!).toOption.map(_.trim)
    else scala.None

  /** Convenience: fetch json via gh api or curl+token. */
  def apiGet(path: String): String =
    current match
      case GhCli =>
        Process(Seq("gh", "api", path)).!!
      case Token(t) =>
        val url = if path.startsWith("http") then path else s"https://api.github.com$path"
        val cmd = Seq("curl", "-fsSL") ++ curlAuthArgs ++ Seq(url)
        Process(cmd).!!
      case None =>
        val url = if path.startsWith("http") then path else s"https://api.github.com$path"
        Process(Seq("curl", "-fsSL", url)).!!
