# Plugin exception to the GNU Affero General Public License

Sluice is licensed under the GNU Affero General Public License, version 3 only
(AGPL-3.0-only), as stated in `LICENSE`. This document grants one additional
permission on top of that license, under AGPLv3 section 7.

## What this covers

This exception covers only a module that does both of the following:

1. It implements the `photos.sluice.application.port.out.VisionCuller`
   interface (the published vision-provider service-provider interface, "the
   SPI") and registers itself as a Spring bean so Sluice can load it.
2. It does not otherwise copy, adapt, or link against any other part of
   Sluice's source code. It does not reproduce any of Sluice's own
   `VisionCuller` implementations either (for example `AnthropicCuller` or
   `ExternalAgentCuller`).

A module meeting both conditions is called a "Vision Plugin" below.

## The permission

You may combine a Vision Plugin with Sluice and convey the combined work, in
object code or source code form. The Vision Plugin itself is not subject to
the terms of the AGPL, provided that:

- The Vision Plugin's only point of contact with Sluice is the `VisionCuller`
  interface described above.
- You still comply with the AGPL in full for Sluice itself and for every part
  of the combined work other than the Vision Plugin. That includes AGPLv3
  section 13's network-source-offer obligation for the Sluice side of the
  combination.

## What this does not cover

This permission does not extend to a module that implements `VisionCuller`
and also depends on, extends, or reaches into any other Sluice interface,
class, or internal package. A module like that is an ordinary modification
of Sluice. It is governed by the AGPL on the same terms as the rest of the
codebase, with no exception.

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
