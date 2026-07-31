## Proposed changes

Describe the big picture of your changes here to communicate to the maintainers why we should accept this pull request.
If it fixes a bug or resolves a feature request, be sure to link to that issue.

## Types of changes

What types of changes does your code introduce to this project?
_Put an `x` in the boxes that apply_

- [ ] Bugfix (non-breaking change which fixes an issue)
- [ ] New feature (non-breaking change which adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to not work as expected)
- [ ] Performance change (behaviour unchanged, cost changed)
- [ ] Documentation Update (if none of the other choices apply)

## Checklist

_Put an `x` in the boxes that apply. You can also fill these out after creating the PR. If you're unsure about any of
them, don't hesitate to ask. We're here to help! This is simply a reminder of what we are going to look for before
merging your code._

- [ ] I have read the [CONTRIBUTING.md](https://github.com/OneLiteFeatherNET/.github/blob/main/CONTRIBUTING.md)
- [ ] The **title of this pull request is a Conventional Commit** — it becomes the squash-commit message and Release
      Please derives the next version and the changelog from it. Scopes in use: `(anvil)`, `(light)`, `(instance)`,
      `(map)`. A breaking change needs `!` or a `BREAKING CHANGE:` footer.
- [ ] I have added tests that prove my fix is effective or that my feature works, and I wrote them **before** the
      implementation and watched them fail for the right reason
- [ ] Tests are package-private, named `test<What><Expectation>`, and use plain JUnit assertions
- [ ] Every new or changed class and method carries Javadoc with `@param` / `@return` / `@throws` — `./gradlew build`
      fails on an incomplete comment
- [ ] I have not written `@NotNull`; the package `@NotNullByDefault` covers it
- [ ] `./gradlew build` is green locally
- [ ] I have added necessary documentation (if appropriate)

## Concurrency

_Delete this section if the change cannot be reached from more than one thread._

Both modules are used concurrently: Minestom starts a virtual thread per chunk, and one
`ChunkLightService` serves any number of callers. Every defect this project has found in its own code
so far was a race that failed **silently** — corrupted light that was never recomputed, a reader
handed sectors that had already been recycled.

- [ ] I have stated below which state the change adds or shares, and what guards it
- [ ] Any new mutable state is either confined to one call or explicitly documented as thread-safe
- [ ] I have added or extended a `*ConcurrencyTest` where the change touches shared state

What is shared, and how is it guarded?

## Measurements

_Delete this section if the change makes no claim about performance._

Numbers without their methodology are not accepted; see [`docs/benchmarks.md`](../docs/benchmarks.md).

- [ ] The figures below come from JMH with the configured forks and iterations, not a smoke run
- [ ] I have quoted the error margins alongside the means, and named the machine, the JVM and the
      thread count (`-t`)

Before / after:

## Further comments

If this is a relatively large or complex change, kick off the discussion by explaining why you chose the solution you
did and what alternatives you considered, etc...
