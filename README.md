# toji (Scala Native)

> **東司** — Toji  
> The man who stands in the gap. No cursed technique required.  
> He simply reaches into the shadows and pulls out the exact tool needed.

**toji** is the public cursed-tool installer for all your (mostly private) CLI tools.

It solves the distribution problem:
- Your real tools live in private GitHub repos (as they should).
- You don't want to hand out PATs or secrets to teammates / CI.
- `gh auth login` (or a `TOJI_GITHUB_TOKEN`) on the target machine is enough.
- If the user has access to the repo, they can install the binary. That's it.

One binary to rule them all: search, install, and update.

## Why "toji"?

In Jujutsu Kaisen, Toji Fushiguro operates outside the normal rules of jujutsu sorcery.  
He wields an arsenal of **cursed tools** pulled from hidden inventory. He doesn't need to be a sorcerer — he just needs the right weapon for the job.

This project is the same: a mundane (public) binary that lets you summon powerful private tools using only the permissions you already have on GitHub.

## Quick Start

```bash
# One-liner install (latest)
curl -fsSL https://raw.githubusercontent.com/kgsleuth/toji/main/scripts/install.sh | bash

# Then
toji --version
toji auth
toji list
toji search cli
toji install curtain
toji update shikigami
```

## Commands

```bash
toji install <name|owner/repo> [v1.2.3] [--force] [--dir /custom/bin]
toji update [tool] [--force]
toji --update [--force]                 # self-update

toji search <term>
toji list
toji tools

toji auth                               # show gh / token status
toji which <tool>

toji --version
toji --help
```

## Auth (the whole point)

toji prefers the local GitHub CLI session:

```bash
gh auth login
toji install some-private-tool
```

Fallback (if no `gh` or on CI without gh):

```bash
export TOJI_GITHUB_TOKEN=ghp_yourpatwithreposcope
# or GITHUB_TOKEN / GH_TOKEN
toji install owner/repo
```

As long as the token / gh user has read access to the releases of that repo, it will download the matching asset (e.g. `sometool-macos-aarch64`).

## Adding your own tools

Edit [native/src/main/scala/io/toji/ToolRegistry.scala](native/src/main/scala/io/toji/ToolRegistry.scala) and add entries:

```scala
Tool(
  name = "mytool",
  repo = "kgsleuth/my-private-cli",
  description = "Does the thing.",
  aliases = List("mt")
)
```

Then publish releases with correctly named assets:

- `mytool-linux-x86_64`
- `mytool-linux-aarch64`
- `mytool-macos-x86_64`
- `mytool-macos-aarch64`

`toji install mytool` will just work (for anyone with repo access).

You can also always bypass the registry:

```bash
toji install kgsleuth/secret-sauce v0.9.0
```

## Building from source

```bash
cd toji/native
sbt nativeLink
# binary at target/scala-3.4.0/toji
cp target/scala-3.4.0/toji ~/.local/bin/
toji --help
```

## Releases

Pre-built native binaries are published to GitHub Releases for all four platforms on every version tag.

The public `toji` repo only contains the installer + registry.  
All the actual power lives in your private repos.

## Philosophy

- Private by default for source.
- Ergonomic distribution without leaking tokens.
- One public "gap tool" that respects the permissions you already granted GitHub.
- Written in Scala 3 Native so it's a single fast static binary with no runtime.

**Raise the tool. Cut the problem.**
