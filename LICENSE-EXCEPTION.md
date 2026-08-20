# Plugin exception to the GNU Affero General Public License

Sluice is licensed under the GNU Affero General Public License, version 3 only
(AGPL-3.0-only), as stated in `LICENSE`. This document grants one additional
permission on top of that license, under AGPLv3 section 7.

## What this covers

"The SPI" means the published vision-provider service-provider interface. That
is the `photos.sluice.application.port.out.VisionCuller` interface, together
with every Sluice type reachable from that interface's own method signatures
and their documented contract.

A type is reachable if a method of `VisionCuller` takes it as a parameter,
returns it, or declares it as thrown. A type required in order to satisfy such
a method's documented contract is reachable too. The contract is what that
release's own documentation for the interface states. That includes the types
naming and populating the decision shards `cull` is defined to produce. A
permitted implementation of a sealed type so reached is also reachable, as is a
type needed in order to construct or read a type so reached. Reachability
applies repeatedly, until it arrives at types that are not part of Sluice. The
SPI is whatever that resolves to in the release of Sluice a given module is
built against.

The SPI is defined by reachability rather than by a list of type names. That
keeps the grant accurate as the interface changes from one release to the next.

This exception covers only a module that does both of the following:

1. It implements `VisionCuller` and registers itself as a Spring bean so Sluice
   can load it.
2. It uses no part of Sluice outside the SPI. It does not reproduce any of
   Sluice's own `VisionCuller` implementations either (for example
   `AnthropicCuller` or `ExternalAgentCuller`).

A module meeting both conditions is called a "Vision Plugin" below.

## The permission

You may combine a Vision Plugin with Sluice and convey the combined work, in
object code or source code form. The Vision Plugin itself is not subject to
the terms of the AGPL, provided that:

- The Vision Plugin's only point of contact with Sluice is the SPI described
  above.
- You still comply with the AGPL in full for Sluice itself and for every part
  of the combined work other than the Vision Plugin. That includes AGPLv3
  section 13's network-source-offer obligation for the Sluice side of the
  combination.

## What this does not cover

This permission does not extend to a module that implements `VisionCuller`
and also depends on, extends, or reaches into any part of Sluice outside the
SPI. A module like that is an ordinary modification of Sluice. It is governed
by the AGPL on the same terms as the rest of the codebase, with no exception.

This permission does not change the license of Sluice itself, and it
grants no rights beyond the one stated above. It is not an offer to
relicense any other part of Sluice. Nor is it a waiver of the copyright
holder's rights against a module that falls outside the two conditions
above.

## Why this exists

Whether a closed, in-process plugin loaded behind a narrow interface is a
"work based on the Program" under the AGPL is a genuinely unsettled question.
This document settles it in one direction, in writing, for exactly the shape
of plugin Sluice is built to accept. A vision-provider integration can then
be written and distributed under whatever license its author chooses,
without first litigating that question.
