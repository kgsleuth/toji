# toji — Estate Layout (DDD packages)

Repo root = sbt root.

src/main/scala/io/toji/
  Main.scala
  cli/               # CLI layer (commands, args, output, errors)
  domain/
    model/           # pure domain types
    port/            # ports / interfaces
  application/       # use cases / orchestrators
  infrastructure/    # adapters, stores, clients, wiring

src/test/scala/ mirrors the structure.

This is the single consistent DDD-style folder structure across the estate.
No flat glob of files directly under io/toji/.

See skills/docs/estate-layout.spec.md for the canonical definition.
