package io.toji

import java.nio.file.Path
import scala.sys.process.*
import scala.util.Try
import ujson.Value

/** Resolve and download GitHub Release assets.
  *
  * Prefers the local `gh` CLI auth (supports private repos the user has access to).
  * Falls back to PAT from TOJI_GITHUB_TOKEN / GITHUB_TOKEN / GH_TOKEN.
  * Public repos work without auth.
  */
object GitHubRelease:

  case class Asset(name: String, id: Long, size: Long, downloadUrl: Option[String] = None)
  case class Release(tag: String, assets: List[Asset], htmlUrl: Option[String] = None)

  def resolve(repo: String, version: String, assetName: String): (Release, Asset) =
    val rel = fetchRelease(repo, version)
    resolveFromRelease(rel, assetName)

  def resolveFromRelease(release: Release, assetName: String): (Release, Asset) =
    val asset = release.assets.find(_.name == assetName).getOrElse {
      val available = release.assets.map(_.name).sorted.mkString(", ")
      val seeUrl = release.htmlUrl.getOrElse("https://github.com/search?q=toji+releases")
      throw ValidationError(
        s"""Release ${release.tag} has no asset "$assetName".

Available assets: ${if available.nonEmpty then available else "(none)"}
The release may still be publishing — retry in a few minutes or pin an older version.

See $seeUrl"""
      )
    }
    (release, asset)

  def download(repo: String, asset: Asset, dest: Path): Unit =
    // Always use the assets API endpoint with auth header (works for private repos; public works too)
    val url = s"https://api.github.com/repos/$repo/releases/assets/${asset.id}"
    val status = curlToFileAuthed(url, dest)
    if status != 200 then
      downloadFailed(repo, asset.name)

  private def curlToFileAuthed(url: String, dest: Path): Int =
    val args =
      Seq("curl", "-sSL", "-L") ++
        Auth.curlAuthArgs ++
        Seq("-H", "Accept: application/octet-stream", "-w", "%{http_code}", "-o", dest.toString, url)
    val stdout = new StringBuilder
    val stderr = new StringBuilder
    val exit = Process(args).!(ProcessLogger(stdout.append(_), stderr.append(_)))
    stdout.toString.trim.toIntOption.getOrElse(if exit == 0 then 200 else 0)

  def fetchRelease(repo: String, version: String): Release =
    val path =
      version match
        case "latest" => s"/repos/$repo/releases/latest"
        case tag      => s"/repos/$repo/releases/tags/$tag"
    parseRelease(apiGetAuthed(s"https://api.github.com$path"), repo, version)

  private def apiGetAuthed(url: String): Value =
    // Use gh if possible (clean json, uses gh session for private)
    Auth.current match
      case Auth.GhCli =>
        val jsonStr = Auth.runGh(Seq("api", url.stripPrefix("https://api.github.com"))).getOrElse {
          // fallback inside gh case
          curlGet(url)
        }
        ujson.read(jsonStr)
      case _ =>
        val raw = curlGet(url)
        ujson.read(raw)

  private def curlGet(url: String): String =
    val args = Seq("curl", "-sSL") ++ Auth.curlAuthArgs ++ Seq("-w", "\n%{http_code}", url)
    val stdout = new StringBuilder
    val stderr = new StringBuilder
    Process(Seq("curl") ++ args).!(ProcessLogger(stdout.append(_), stderr.append(_)))
    val raw = stdout.toString.trim
    if raw.isEmpty then throw ValidationError("GitHub API returned empty response")
    val nl = raw.lastIndexOf('\n')
    val (body, statusStr) =
      if nl >= 0 then (raw.substring(0, nl), raw.substring(nl + 1).trim) else (raw, "0")
    val status = statusStr.toIntOption.getOrElse(0)
    status match
      case 200 => body
      case 404 =>
        val repo = extractRepo(url)
        val req = extractRequested(url)
        throw notFound(repo, req)
      case other =>
        throw ValidationError(s"GitHub API error (HTTP $other) for $url")

  private def notFound(repo: String, requested: String): ValidationError =
    val hint =
      if Auth.current == Auth.None then
        s"""If https://github.com/$repo is private, authenticate with:

  gh auth login

or export a PAT:

  export TOJI_GITHUB_TOKEN=ghp_xxx   # or GITHUB_TOKEN / GH_TOKEN
"""
      else
        "Check the tag name, or ensure your gh login / token has access to the repo."
    ValidationError(
      s"""Could not find release "$requested" for $repo.

$hint
See https://github.com/$repo/releases"""
    )

  private def downloadFailed(repo: String, assetName: String): Nothing =
    val hint =
      if Auth.current == Auth.None then "If the repo is private, run `gh auth login` or set TOJI_GITHUB_TOKEN."
      else "Ensure your gh session or token has `repo` scope and read access to the repo."
    throw ValidationError(
      s"""Failed to download $assetName from $repo.

$hint

See https://github.com/$repo/releases"""
    )

  private def extractRepo(url: String): String =
    val p = url.stripPrefix("https://api.github.com/repos/")
    val parts = p.split("/")
    if parts.length >= 2 then s"${parts(0)}/${parts(1)}" else "unknown/unknown"

  private def extractRequested(url: String): String =
    if url.endsWith("/releases/latest") then "latest"
    else url.split("/tags/").lastOption.getOrElse("?")

  private val repoPlaceholder = "kgsleuth/???"

  private def parseRelease(json: Value, repo: String, requested: String): Release =
    val tag = json.obj.get("tag_name").map(_.str).getOrElse(requested)
    val html = json.obj.get("html_url").map(_.str)
    val assets =
      json.obj.get("assets").map(_.arr.toList).getOrElse(Nil).flatMap { row =>
        val obj = row.obj
        for
          name <- obj.get("name").map(_.str)
          id <- obj.get("id").map(_.num.toLong)
          size <- obj.get("size").map(_.num.toLong)
        yield Asset(name, id, size, obj.get("browser_download_url").map(_.str))
      }
    if assets.isEmpty && json.obj.get("message").exists(m => m.str == "Not Found" || m.str.contains("Not Found")) then
      throw notFound(repo, requested)
    Release(tag, assets, html)
