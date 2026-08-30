# ADR 0001 — Governed agent/bot number allocation over Telnyx

## Decision

Build the missing `cloud-itonami-numbering` actor as the operator-owned layer
between `cloud-itonami-app` consent and Telnyx. Use `kotoba-lang/phone` for number
state, allocation and quarantine. Keep the human/organization owner separate
from the agent/bot receiving inbound events.

Mutations cross two listeners: owner Passkey consent enters the consent surface;
a named operator decides on the operator surface. The provider call happens only
after both. Provider success is recorded separately from approval.

## Why a new actor

`denwaban` owns a voice session after a carrier has delivered a call. It does
not buy or assign numbers. `app-telecom` is a generic extracted provisioning
worker without this workspace's actor ledger or Telnyx connection. The number
authority in `cloud-itonami-app` is a consent/read-model adapter and explicitly
does not own the licensed-operator decision. This actor fills that one missing
layer rather than moving provider credentials into any of those components.

## Consequences

- A bot can receive a line without gaining owner powers.
- Quote, route and assignee substitution after Passkey consent changes the
  digest and is refused.
- Real Telnyx activation still requires account credentials, regulatory
  coordinates and an explicit deployment opt-in.
- The local durable plane is a single atomic EDN file at R1. Shared Kotobase
  persistence and automatic reconciliation of an interrupted provider order
  remain separate maturity steps.

