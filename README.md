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

## Install

```bash
# Latest release (recommended)
curl -fsSL https://raw.githubusercontent.com/kgsleuth/toji/main/scripts/install.sh | bash

# Pin a specific version
curl -fsSL https://raw.githubusercontent.com/kgsleuth/toji/main/scripts/install.sh | TOJI_VERSION=v0.1.2 bash
```

After install:

```bash
toji --version
toji auth          # verify it sees your gh session
toji list
```

## Quick Start

```bash
toji available                 # which registry tools have releases for this machine
toji install curtain
toji install --all             # install every available registry tool
toji update --all
toji registry scan             # discover kgsleuth repos with releases → local cache
toji search cli
```

## Commands

```bash
toji install <name|owner/repo> [v1.2.3] [--force] [--dir /custom/bin]
toji install --all [--force]
toji update [tool|--all] [--force]
toji --update [--force]                 # self-update

toji available                        # probe GitHub releases for registry tools
toji list [--available]
toji search <term>

toji registry refresh                 # pull registry.json from toji repo
toji registry scan [org]              # discover org repos with release binaries
toji registry show                    # print cached registry JSON

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

Edit `registry.json` at the root of this repo and add an entry (no code change required):

```json
{
  "name": "mytool",
  "repo": "kgsleuth/my-private-cli",
  "description": "Does the thing.",
  "aliases": ["mt"],
  "binaryBase": ""
}
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

## Auto-updates

toji tracks every tool it installs under `~/.local/state/toji/<tool>.json`.

- **`toji available`** — registry tools + latest release / install status for your platform
- **`toji registry scan`** — find org repos that publish release binaries and merge into local cache
- **`toji install --all`** — install everything `available` shows as installable
- **`toji update --all`** — refresh every toji-managed install
- **Background** — routine toji commands may spawn silent `toji update --all --due-only` after 7 days
- **Cron** (optional): `0 9 * * 0 toji update --all`

Individual CLIs do not self-update; use toji.

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

## Releasing a new version

1. Make changes on `main`.
2. (Recommended) Bump `version` in `build.sbt` and `src/main/scala/io/toji/Main.scala` (e.g. 0.1.3), commit.
3. `git tag -a v0.1.3 -m "v0.1.3"`
4. `git push origin main && git push origin v0.1.3`

The Release workflow will:
- Force the binary + embedded `--version` to match the tag (0.1.3).
- Build all 4 platform binaries.
- Create the GitHub Release with assets + `install.sh`.
- Verify the assets.

Always use a **new** unique version tag (never force-push an old tag). This forces a fresh versioned release every time.
