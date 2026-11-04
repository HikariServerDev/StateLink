# 2.1.11: Strict Safety / Fail-Closed Recovery

Historical strict-mode contract. The current default is risk-acknowledged last_checkpoint, not this fail-closed default. See README.md and docs/DEVELOPMENT.md for current behavior and verification limits.

## Contract and failure model

The JVM can pause at any instruction and later resume; it can also crash. Minecraft
World/chunk/container/entity persistence and MySQL PlayerData are independent
resources. A local authority check is not atomic with a World mutation. PDC does
not promise a distributed exactly-once World transaction.

PDC instead promises that **an unclean ownership termination cannot automatically
restore the last database checkpoint on another backend**. Unknown World/PlayerData
ordering is retained as RECOVERY_REQUIRED, not guessed. Availability is sacrificed
only for unclean termination; clean handoff remains automatic.

## Coordination state

The existing owner/session/fence/lease/revision fields remain. Additional fields
record recovery_required, recovery_reason, recovery_required_at and
last_checkpoint_at. State is derived as follows:

* OWNED: recovery_required=false and a complete live owner/session/lease tuple.
* CLEAN_RELEASED: recovery_required=false and all owner/session/lease fields null.
* RECOVERY_REQUIRED: recovery_required=true, malformed ownership, or an expired
  owner. Expiry is materialized under the coordination-row lock. Owner, fence,
  revision and expired lease remain available for investigation.

Legacy null-owner/null-session/null-lease rows are interpreted as clean/free.
Legacy expired owners are never interpreted as free. No migration resets recovery.
Only all-2.1.11-or-later cooperating writers are supported; any older writer can
bypass this contract.

## Transitions and proof

Normal acquire locks sync_state, obtains fresh DB time, rejects RECOVERY_REQUIRED,
returns BUSY for a live owner, and marks expired/inconsistent ownership as recovery
required **without reading player_data or increasing the fence**. Only a clean row
can acquire a new fence and LOAD. Same-session retries still require a live lease.

Normal SAVE locks sync_state before player_data and requires live ownership and no
recovery flag. Final SAVE updates PlayerData and revision and clears ownership in
one InnoDB transaction. Bare ownership abandonment never means clean release.

Induction over automatic transitions: a fresh/clean row may become OWNED; only a
successful final save/release transaction returns OWNED to clean. Expired/abandoned
rows become RECOVERY_REQUIRED, which no automatic transition clears. Therefore a
new backend cannot obtain an ACQUIRED result or apply checkpoint data after unclean
loss, unless an operator explicitly resolves recovery. This proof does not rely on
same-host locks, scheduling margins, or the old JVM remaining dead.

For check -> pause -> expiry -> resume -> World mutation, the destination is denied
before LOAD. For spawn -> crash -> old DB checkpoint, the destination is likewise
denied. PDC does not produce an additional spendable checkpoint copy. It does not
roll back World state or claim that Minecraft itself cannot lose unsaved state.

## Recovery

Preferred: stop/fence the old process, inspect its World and source-side Player
state, and reconstruct a fresh complete source snapshot. A repository API accepts
an explicitly verified source snapshot with expected fence/revision; it writes the
snapshot and clean release atomically. Restarting a source or loading a vanilla
player file alone is not evidence of an authoritative current snapshot.

Fallback: console-only force-last-checkpoint, with explicit acknowledgement that
the source is fenced and World state reviewed. This may duplicate or discard
externalized state and is a dangerous operator decision, never automatic recovery.
Status is read-only. Resolution is CAS-guarded by expected fence/revision and
recovery state, advances the fence, preserves audit history, and cannot execute
twice using the same expectations. No recovery timer exists.

Both recovery variants use sync_state -> player_data (when writing source data) ->
recovery audit lock/write ordering. Resolution must not run while a possibly live
old process can resume; the explicit acknowledgement is an operational precondition,
not an automatic proof of process fencing.

## Deployment and rollback

Stop all old writers first, back up PlayerData/coordination/Worlds, then update all
backends to the same 2.1.11 artifact. Added columns/tables are non-destructive and
InnoDB-validated. Schema failures stop startup. Extra columns can be ignored by the
old 2.0.0 writer, but that writer bypasses recovery/fencing: rollback must be an
offline recovery decision with Worlds and PlayerData reconciled; never resume old
writers concurrently or drop audit columns to bypass recovery.
