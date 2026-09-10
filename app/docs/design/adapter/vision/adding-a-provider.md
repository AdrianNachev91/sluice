# Adding a vision provider

What a new `VisionCuller` implementation has to satisfy, and the two rules that are not obvious from
reading an existing one. Written for somebody forking Sluice or building their own provider, which
the license already permits without asking.

The mechanics are small. `CullDispatcher` discovers providers by list injection, so a new one is a
single `@Component` class implementing `VisionCuller` and nothing else to wire. The rules below are
the part that costs money to learn the hard way.

## A provider must constrain its output, or be able to iterate for free

`ProviderType` splits providers by how they arrive at a judgement, and the split decides what a
refusal costs.

Under `API` the app calls a model and writes shards from the answer. A `CullException` is a genuine
failure, and the call that produced it has already been paid for. Under `MANUAL` something outside
the app writes the shards. The same exception is an ordinary pause, it costs nothing, and whatever
is doing the work can try again.

`ShardValidator` is the single authority on a well-formed cull, and it was written for the second
kind. Its own documentation says it reports every problem at once "so the vision agent gets its
whole to-fix list in one pass instead of one error per re-run". That is a different mechanism from
a schema rather than a weaker one: a schema constrains generation, a validator explains a
rejection to something that can act on the explanation.

**So a provider gets one of two things: the ability to constrain its output, or the ability to
iterate against the validator's findings for free.** A candidate with neither is a `MANUAL`
provider, or it is not a provider. Nothing enforces this. It is a rule for whoever reviews the
code, including you reviewing your own.

## A schema looser than the validator is paid for one gap at a time

If your provider constrains its output with a schema, **the schema has to be at least as strict as
`ShardValidator` on every clause a per-verdict schema can express.** Any gap between them is a shape
the model can legally produce and the validator will refuse, and each occurrence costs a retry that
bills.

This is not hypothetical. Sluice's own Anthropic provider had this defect: its schema required
`index`, `name` and `action`, while the validator demands a non-blank `reason` on every
classification and a category drawn from the run's own set. A real-data probe in August 2026 put
four production-shaped sheets through it and three were refused after their corrective retry.

Four of the validator's clauses are closeable in a schema. The other four are cross-verdict or
cross-shard rules that no per-item schema reaches, whatever keywords it supports:

| Clause                                                          | Schema-closeable               |
|-----------------------------------------------------------------|--------------------------------|
| A classification carries a non-blank `reason`                   | Yes                            |
| A near-dup keeper carries `chosen_reason`                       | Yes                            |
| A classification's category is one the run declared             | Yes                            |
| A group id is a lowercase slug, at most 24 characters           | Yes, and deliberately not used |
| A near-dup group has exactly one keeper and one or more rejects | No                             |
| A group id is not reused across shards                          | No                             |
| A decision's file is one the montages actually showed           | No                             |
| No file is acted on twice                                       | No                             |

So some refusals reach any provider however strict its schema. **An `API` provider therefore also
needs a genuinely corrective retry**, one that tells the model what was wrong rather than simply
asking again. That is a separate quality from schema support, and no vendor advertises it.

Because the category clause depends on the run's own configured categories, a schema that closes it
cannot be a static constant. Build it per run from the categories the prep directory declares, which
is the same set the validator judges against, or the two drift apart the first time a user edits a
category.

## String constraints shape the answer, they do not refuse it

Worth knowing before you reach for them, and worth not taking Anthropic's keyword list at face
value. Its structured-outputs documentation lists `minLength`, `maxLength` and `pattern` as
unsupported. **All three are accepted and enforced**, tested on `claude-sonnet-5` on 2026-08-22:
`maxLength: 8` cut a long phrase to exactly eight characters, and `pattern: ^[a-z-]+$` reshaped one
into a slug. Sluice's own design carried a limitation from that documented list that never existed.

Test rather than assume in either direction. The same list is right about some things: `minItems`
above 1 really is refused on an array, checked the same free way on 2026-08-22, and the API says so
in the 400 it returns. One proven error does not make a list worthless, it makes it a thing to
verify.

The catch is the mechanism. This is **constrained decoding rather than validation**, so a constraint
bends the answer to fit instead of rejecting an answer that does not. That makes the direction of a
constraint decide whether it is safe.

A floor is safe. `minLength: 1` guards `reason`, `chosen_reason` and `group` here, and real values
land near 85 characters against a floor of 1, so an ordinary answer never touches it. It only bites
the blank that the validator would have refused anyway.

A ceiling is not. `maxLength` on the group slug was available and declined: a slug cut to fit is not
reported as wrong, and two groups on one sheet truncating to the same slug would merge into a single
`Duplicates/` folder with nothing to say it happened. A refusal costs one retry. That costs somebody
photos in a folder they never chose.

## Prove your schema for free before you pay for it

`messages/count_tokens` refuses an illegal schema with the same 400 that `messages` gives, and
generates nothing. So a schema is provable at no cost, and a run is priceable before anything is
spent. The schema counts toward that price like any other input: for Sluice's own request it adds
about 625 tokens a montage.

Use it. Every schema mistake this page describes was cheaper to find that way than by paying for a
retry to discover it.

## Do not paper over a refusal

The cheap fix for a missing `reason` is to fill one in and never fail validation again. Do not. It
is silent coercion at an input boundary, and it converts a loud failure you have paid for into a
quiet wrong answer in somebody's photo library. A refusal that reaches a person is the design
working.

## Two mechanical traps

**`SecretId.name` is a second id namespace and nothing polices it.** It becomes the credential's
filename and its entry name in the OS keyring. The dispatcher's duplicate-id check covers only the
id a culler describes itself with. Two providers whose culler ids differ but whose `SecretId.name`
both read `anthropic` would silently share one credential, where saving either key overwrites the
other. Keep a provider's `SecretId.name` equal to the id in its own description.

**A provider describes itself.** `describe()` answers with the provider's name, its settings, which
of them are required, its credential and the models it offers, so a provider added later arrives
complete and nothing outside it needs editing for it to appear. Anything that would require a
central registry to be edited belongs in the provider instead.

## Copying the Anthropic culler

`AnthropicCuller` is the structural template: its wiring, its credential lookup through
`SecretStore`, its per-montage loop and its one-retry cap. Copy that shape.

Its response schema is strict as of 2026-08-22: a discriminated union on `action`, one branch per
action kind, each requiring what that kind needs. Copy that shape too.

Still check a copy against `ShardValidator` rather than against the template. The moment your
provider's rules differ from Sluice's, the template stops being the authority and the validator
starts. The defect that prompted this page was exactly that gap going unnoticed.

## Related

- The template itself, in full: [`anthropic-culler.md`](anthropic-culler.md), same folder. Its
  request loop, credential check and response validation are the shape this page tells you to copy.
