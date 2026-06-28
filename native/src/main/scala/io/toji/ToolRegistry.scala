package io.toji

/** Registry of known CLI tools published as GitHub Releases.
  *
  * You can install by friendly name (if registered) or directly by owner/repo.
  * Add your private-repo tools here so `toji search` and friendly installs work.
  */
object ToolRegistry:

  case class Tool(
    name: String,
    repo: String,          // "owner/repo"
    description: String,
    aliases: List[String] = Nil,
    // How the binary inside the asset is named (without os/arch). Usually same as name.
    binaryBase: String = ""
  ):
    def base: String = if binaryBase.nonEmpty then binaryBase else name

  // NOTE: Add your tools here for friendly names, search, aliases, etc.
  // Bare names (without /) are automatically prefixed with toji's own owner at runtime
  // (see defaultOwner below). You can always use full owner/repo.
  // Users with access can `toji install owner/repo` for anything not listed.
  val tools: List[Tool] = List(
    Tool("toji", "kgsleuth/toji", "The gap-filling cursed tool installer (this binary)."),
    Tool("curtain", "kgsleuth/curtain", "Security curtains: pre-commit + GitHub Actions for secret scanning & private-by-default."),
    Tool("shikigami", "kgsleuth/shikigami", "Observe industry, spec projects, RAG + suggestions for adapting capabilities."),
    Tool("sleuthctl", "kgsleuth/sleuthctl", "Microsoft Sentinel / Defender XDR incident response knowledge and tooling."),
    Tool("kenjaku", "kgsleuth/kenjaku", "Local planning CLI for to-dos/ queues, plan shards, dependency gating, and live MASTER-TODO.md."),
    // Example of a private tool you might publish:
    // Tool("grok", "kgsleuth/grok", "Internal Grok CLI.", aliases = List("g")),
  )

  def all: List[Tool] = tools

  def findByNameOrAlias(q: String): Option[Tool] =
    val key = q.toLowerCase.trim
    tools.find { t =>
      t.name.equalsIgnoreCase(key) ||
      t.aliases.exists(_.equalsIgnoreCase(key)) ||
      t.base.equalsIgnoreCase(key)
    }

  def findByRepo(ownerRepo: String): Option[Tool] =
    val norm = ownerRepo.stripPrefix("https://github.com/").stripPrefix("git@github.com:").stripSuffix(".git").trim
    tools.find(_.repo.equalsIgnoreCase(norm))

  def search(query: String): List[Tool] =
    if query.trim.isEmpty then tools
    else
      val q = query.toLowerCase
      tools.filter { t =>
        t.name.toLowerCase.contains(q) ||
        t.description.toLowerCase.contains(q) ||
        t.repo.toLowerCase.contains(q) ||
        t.aliases.exists(_.toLowerCase.contains(q))
      }

  private val tojiRepo = findByNameOrAlias("toji").map(_.repo).getOrElse("kgsleuth/toji")
  val defaultOwner: String = tojiRepo.split("/")(0)

  def defaultRepoFor(nameOrRepo: String): String =
    findByNameOrAlias(nameOrRepo).map(_.repo).getOrElse {
      if (nameOrRepo.contains("/")) nameOrRepo else s"$defaultOwner/$nameOrRepo"
    }

  def baseFor(nameOrRepo: String): String =
    findByNameOrAlias(nameOrRepo).map(_.base).getOrElse {
      // if owner/repo given, take last segment as base
      val segs = nameOrRepo.split("/")
      segs.lastOption.getOrElse(nameOrRepo).replaceAll("[^A-Za-z0-9-]", "")
    }
