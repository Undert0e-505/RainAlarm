# Individual-lightning feed: clean-room deployment guide

Rain Alarm's experimental Radar layer reads a compact public feed of recent EUMETSAT Meteosat
Third Generation Lightning Imager flash centroids. This guide describes the contract and a safe
way to build an equivalent self-hosted relay. It does not publish the project's private collector
source, credentials or Cloudflare resource identifiers.

The feed is an information aid. A point is a satellite-observed total-lightning flash centroid,
not a guaranteed cloud-to-ground strike, impact location, forecast or safety warning.

## System shape

The reference design has two deliberately separate parts:

```text
registered EUMETSAT Data Store
        │  completed MTG LI-2-LFL products over HTTPS
        ▼
always-on collector/publisher
  validate → ledger → deduplicate → build five regional snapshots → gzip
        │  authenticated PUT
        ▼
one Cloudflare Worker + one KV namespace
        │  public GET/HEAD with ETag and CORS
        ▼
Rain Alarm Radar client
  validate → age/fade points locally
```

The collector needs registered EUMETSAT access; mobile clients do not. Raw product files and
provider credentials never pass through the public API. Cloudflare retains only the latest compact
snapshot for each supported region.

Rain Alarm currently reads:

```text
https://rain-alarm-lfl-feed.aaronjoakley55.workers.dev/v1/regions/{region}
```

where `{region}` is exactly `uk`, `de`, `nl`, `ch` or `fr`. A self-host must use its own origin and
change both the app's feed-base constant and its HTTPS host allowlist while preserving this v1
contract.

## Source product and access

Use EUMETSAT collection `EO:EUM:DAT:0691`, **MTG LI-2-LFL**. LFL records include exact observation
times and geolocated optical total-lightning flash centroids. Do not relabel them as ground strikes
and do not infer cloud-to-ground status.

1. Register an EUMETSAT user account and accept any current collection terms.
2. Create an API key in the EUMETSAT Data Store account portal.
3. Install the official EUMDAC client/library in an isolated local environment.
4. Confirm the account can search and download collection `EO:EUM:DAT:0691` before automating it.
5. Keep the key in the operating system's secret store or a protected service environment. Never
   put it in source, command arguments, logs, a mobile app or Cloudflare's public data.

EUMETSAT publishes completed products covering about ten sensing minutes. The evaluated feed
recorded a typical 33–45 second publication latency after completion; this is operational evidence,
not an availability guarantee. Delay and missing inputs still have to be handled as data-quality
states, not hidden as quiet weather. Polling every minute reduces discovery delay but cannot turn
the upstream source into a one-minute or five-minute publication.

## Fixed regional coverage

The rectangles intentionally match Rain Alarm's `RegionalRadarAreas`; overlaps allow adjacent
radar regions to see the same boundary observations.

| ID | North | West | South | East |
|---|---:|---:|---:|---:|
| `uk` | 59.737642 | -14.677787 | 45.575355 | 5.827836 |
| `de` | 55.0984291 | 1.91780433 | 46.9658661 | 15.900782 |
| `nl` | 55.860496 | 0.0 | 48.781428 | 10.856639 |
| `ch` | 49.38 | 2.69 | 43.62 | 12.46 |
| `fr` | 53.467973 | -9.518992 | 39.792072 | 13.734322 |

Filter a validated point into every rectangle containing its latitude/longitude. Do not force a
point into only one region, and do not choose a region from the user's panned camera.

## Acquisition and strict validation

Search a bounded time overlap on every poll so a late or corrected product can be discovered.
Download through EUMDAC to a temporary file, then validate before it enters the durable history:

- collection and immutable product identity match the requested LFL collection;
- the product is complete, readable netCDF and within configured size/time limits;
- required flash identifier, timestamp, latitude and longitude variables are present;
- timestamps are finite, exact and inside the product's declared sensing interval;
- coordinates are finite and inside physical latitude/longitude bounds;
- array lengths agree and decompression cannot exceed configured resource limits; and
- a repeat immutable product has the same content digest, or is quarantined as a conflict.

Preserve each provider timestamp as epoch milliseconds. Do not replace it with the product time,
download time or a five-minute boundary. A zero-flash product can prove a covered interval empty;
an absent, unreadable, late or incomplete product cannot.

### Durable ledger

Keep a local transactional ledger outside temporary download directories. A practical product row
records immutable source identity, sensing start/end, content hash, validation status, ingestion
time and the normalized observations. Required behaviors are:

- **Idempotency:** reprocessing an identical product is a no-op.
- **Deduplication:** derive a stable point identity from immutable product identity plus the source
  flash ID; reject conflicting data for one identity.
- **Quarantine:** retain the reason and identity of malformed/conflicting inputs without using
  their observations.
- **Atomic generations:** build all five snapshot files in a temporary generation directory,
  validate them, then rename/publish the complete set as one local transaction.
- **Catch-up:** after downtime, ingest a bounded overlap and publish the newest reconstructed
  rolling state rather than replaying every missed public generation.

SQLite with full transactions is sufficient; append-only normalized files plus an atomic manifest
also work. On reboot, reconcile unfinished temporary work before starting the next poll.

## Five-minute frames from ten-minute products

For each region, produce exactly 18 contiguous five-minute UTC half-open frames covering 90
minutes:

```text
[observedFrom, observedFrom + 5m)
...
[observedThrough - 5m, observedThrough)
```

`completeThrough` is the latest UTC five-minute boundary for which all required upstream coverage
is known complete. Floor it; never round it into an interval that has not completed. One completed
ten-minute source product normally completes two five-minute frames together. Assign a point at a
boundary only to the frame beginning at that boundary.

Frame status is evidence, not presentation:

| Status | Meaning |
|---|---|
| `valid` | Complete interval with one or more observations |
| `valid-empty` | Complete interval with zero observations |
| `partial` | Incomplete interval with some observations |
| `unknown` | Incomplete interval with no observations; never claim this is clear |

A public top-level snapshot is normally `valid` or `valid-empty`. `partial` and `stale` are explicit
degraded states that a strict client may refuse while retaining its last verified generation.

## Public v1 document

Encode compact JSON, then gzip it deterministically. The top-level document contains exactly these
fields:

```json
{
  "schemaVersion": 1,
  "generationId": "20261002T210000Z-0123456789abcdef",
  "regionId": "fr",
  "bounds": {"north": 53.467973, "west": -9.518992, "south": 39.792072, "east": 13.734322},
  "intervalMinutes": 5,
  "historyMinutes": 90,
  "generatedAt": "2026-10-02T21:00:40Z",
  "observedFrom": "2026-10-02T19:30:00Z",
  "observedThrough": "2026-10-02T21:00:00Z",
  "completeThrough": "2026-10-02T21:00:00Z",
  "staleAfter": "2026-10-02T21:12:00Z",
  "status": "valid",
  "correction": {"applied": false, "mode": "none", "version": "raw-centroids-v1"},
  "provenance": {
    "collectionId": "EO:EUM:DAT:0691",
    "observationType": "satellite-observed total-lightning flash centroids; not guaranteed ground strikes",
    "sourceBatchMinutes": 10,
    "typicalPublicationLatencySeconds": [33, 45],
    "intervalSemantics": "UTC half-open [start,end)",
    "pointFields": ["id", "observedAtEpochMs", "latitude", "longitude"]
  },
  "attribution": "Contains modified EUMETSAT Meteosat Third Generation LI-2-LFL data, 2026",
  "counts": {
    "points": 1, "frames": 18, "completeFrames": 18,
    "partialFrames": 0, "unknownFrames": 0, "validEmptyFrames": 17
  },
  "frames": [
    {
      "start": "2026-10-02T20:55:00Z",
      "end": "2026-10-02T21:00:00Z",
      "complete": true,
      "status": "valid",
      "count": 1,
      "points": [["0123456789abcdef01234567", 1790974623456, 40.4168, -3.7038]]
    }
  ]
}
```

The shortened example shows one frame; a real document must have all 18 ordered frames. Each point
tuple is exactly:

```text
[stableIdentity, observedAtEpochMs, latitude, longitude]
```

Use a deterministic 96-bit lowercase hexadecimal identity. Order points by timestamp and then ID.
Reject duplicate identities with conflicting time or coordinates. `generationId` should combine
the UTC complete-through time and a short digest of the exact source/history inputs, so unchanged
inputs produce unchanged bytes and identity.

Set `staleAfter` to `completeThrough + 12 minutes`. A poll that finds nothing new must not rewrite
the object simply to change its age; consumers compare their clock with `staleAfter`. The client
may continue visually ageing already known points, but freshness remains a separate acquisition
decision.

## Worker and KV deployment

The minimal deployment uses one Cloudflare Worker and one KV namespace:

- one KV key per region, for example `region:uk`;
- the value is the already-compressed gzip document;
- small KV metadata stores generation, complete-through, status and compressed SHA-256;
- public `GET` and `HEAD` read only the five exact keys; and
- one private authenticated `PUT` replaces them.

This is designed for the Workers Free tier, but an operator must inspect current Cloudflare limits
before deployment and after material traffic changes. Make the deployment fail closed or alert
before exceeding the chosen zero-cost request, CPU, KV read/write, storage or payload budgets. Do
not silently enable a paid plan.

### HTTP behavior

Public endpoints:

```text
GET  /v1/regions/{uk|de|nl|ch|fr}
HEAD /v1/regions/{uk|de|nl|ch|fr}
```

Successful responses include:

- `Content-Type: application/json`
- `Content-Encoding: gzip`
- `ETag: "<sha256-of-compressed-bytes>"`
- a short public `Cache-Control` policy
- read-only CORS (`Access-Control-Allow-Origin: *`, methods `GET, HEAD`)
- `X-Rain-Generation`, `X-Rain-Complete-Through` and `X-Rain-Status`
- `X-Content-Type-Options: nosniff` and restrictive referrer/content-security headers

Honor `If-None-Match` with `304` and no body. Return a clear `404 feed-unavailable` for a missing
current key; never manufacture a successful empty document.

Private publication uses only:

```text
PUT /internal/v1/regions/{region}
Authorization: Bearer <publisher secret>
Content-Type: application/json
Content-Encoding: gzip
```

Also send the compressed-byte SHA-256 and generation/status metadata. Authenticate before doing
expensive body work; enforce an absolute compressed size ceiling; decompress with a strict output
ceiling; validate every document field and region bound; then compare with current KV metadata:

```text
incoming generation older than current  -> reject conflict
same generation + same compressed hash  -> successful no-op, no KV write
same generation + different hash        -> reject conflict
newer valid generation                   -> replace value and metadata together
```

If validation or KV storage fails, leave the last current object untouched. Rate-limit private
writes and never expose list, debug, arbitrary-key or public mutation routes.

## Credential handling and rotation

Use three separate secret domains:

- EUMETSAT API credentials live only on the collector host.
- The Cloudflare deployment credential is needed only by deployment tooling and can be removed
  from the runtime host after deployment.
- A high-entropy publisher bearer secret is stored as a Worker secret and in the collector host's
  protected credential store.

Do not keep plaintext secrets in repository files, `.env` files, task XML, logs or shell history.
Restrict the collector account and working directories, prevent secret-bearing command-line
arguments, and redact HTTP headers from diagnostics. To rotate the publisher secret, install the
new Worker secret, update the protected local credential, perform an authenticated dry-run, then
revoke the old value. Rotate EUMETSAT and Cloudflare credentials through their provider portals and
verify collection/deployment access independently.

## Collector schedule and recovery

Run a one-minute scheduled loop on the always-on host, with a single-instance lock and bounded
network deadlines:

```text
acquire lock
search recent completed LFL products with overlap
download unseen candidates to temporary files
validate, normalize and transactionally update ledger
derive newest complete 90-minute window
atomically build and validate five regional gzip documents
publish only changed generations
record coordinate-free outcome/latency metrics
release lock
```

Use the operating system's scheduler or service manager with `StartWhenAvailable`, an
ignore-new-instance overlap rule, a bounded execution time and restart-on-failure. After reboot or
outage, reconcile temporary files, verify the ledger, search a bounded overlap and publish one
current catch-up generation. Back off on provider/network errors without losing the last good
Cloudflare value. Never convert a failed interval into `valid-empty`.

On Windows, choose the logon model deliberately:

- **Interactive current-user task:** add an explicit `AtLogOn` trigger plus the repeating one-minute
  schedule. This can access that user's protected Credential Manager entries, but it resumes only
  after that user signs in. It is suitable when the machine reliably auto-signs-in or an operator
  logs in; it is not pre-login availability.
- **Unattended startup task:** use an `AtStartup` trigger and Task Scheduler's password-backed batch
  logon under the same dedicated account, or provision a dedicated service identity and re-store
  its secrets under that identity. Confirm it retains network access and can read only its own
  protected credentials. Supply the account password through the trusted interactive Task Scheduler
  workflow, never a command line, script or repository file.

Do not use S4U for a collector that needs network credentials. Do not switch a user-scoped task to
`SYSTEM`: it cannot read that user's Credential Manager entries. Whichever mode is selected must
retain the single-instance rule, restart policy, bounded work, overlap/catch-up logic and last-good
public-feed behavior.

Validate unattended operation with a controlled maintenance reboot: verify task result `0`, confirm
a new feed generation advances on schedule in the intended no-login state, and scan the task action,
environment and logs for plaintext secrets. A task that is merely Enabled/Ready after an
interactive logon has not proved pre-login recovery.

## Validation checklist

Automate deterministic tests for:

- netCDF schema, bounds, non-finite values, truncation and decompression limits;
- exact timestamp preservation and five-minute boundary assignment;
- valid-empty versus partial/unknown intervals;
- stable-ID deduplication and conflict quarantine;
- all exact region bounds and intentional overlaps;
- 18 contiguous half-open frames and the 90-minute window;
- generation repeatability, monotonic publication, no-op and conflict paths;
- JSON exact fields, counts, ordering, attribution and gzip round-trip;
- compressed and decoded payload size ceilings;
- authentication, disallowed methods/paths/regions and CORS;
- ETag equality, conditional `304`, `HEAD`, cache and security headers;
- KV failure preserving the old value;
- stale clock behavior, late product catch-up and restart recovery; and
- a clean Android-client fixture for populated, valid-empty, partial, stale and malformed feeds.

Before each deployment, query every public region, save headers, decompress and validate the body,
then repeat with `If-None-Match`. Confirm the current generation is recent enough without assuming
that a quiet frame must contain points.

## Operations, monitoring and rollback

Monitor without collecting user locations:

- collector last-success time and consecutive failures;
- newest upstream sensing end and observed publication lag;
- ledger/quarantine counts and validation reasons;
- generated status, complete-through and compressed size per region;
- publish changed/no-op/conflict/failure counts; and
- Worker/KV usage against the chosen free-tier safety thresholds.

Alert before `staleAfter`, on repeated authentication failures, any generation conflict, malformed
public data or a quota threshold. A rollback should restore the previous verified Worker deployment
and leave the last known-good KV values in place. If data integrity is uncertain, return an explicit
unavailable response rather than serving a fabricated empty generation.

## Privacy, licence and attribution

The regional documents contain weather-observation coordinates and times, not user identities.
Public requests reveal normal network metadata to Cloudflare. The path reveals only one fixed
regional ID; the Rain Alarm client does not need to send an account credential, selected-place
name or selected coordinates to this relay.

Respect EUMETSAT's current registration, data-policy and attribution requirements. Every document
must retain:

> Contains modified EUMETSAT Meteosat Third Generation LI-2-LFL data, 2026

Recheck the collection terms and Cloudflare terms before operating a public mirror. Publish an
accurate privacy disclosure for the relay, minimize logs, set short retention, and never claim
that satellite flash centroids are verified ground strikes or a safety-warning service.
