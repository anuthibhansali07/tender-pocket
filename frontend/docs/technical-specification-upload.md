# Technical specification upload contract

## Ownership and history

The browser POSTs one multipart `file` to Next.js. Next.js retains the local input
copy, forwards it to Spring Boot, waits for the result, and then registers the
input and generated documents in SQLite. Java owns extraction, PDF/DOCX rendering,
PostgreSQL document metadata, and usage metrics.

The upload route was added by `dd2f846` as a local upload with optional Java
forwarding. Its 3-second abort, ignored response, and unconditional success were
consistent with that upload-only path, but not the conversion implemented in
`4cc0196` and `c72c67b`.

Java's `TenderController.uploadTechSpec` is synchronous. It returns only after
processing and persistence. `ComplianceProgressService` is an in-memory,
read-only snapshot of that same request; it is not a queue or asynchronous job
submission API.

## Outcomes

| Java outcome | Next.js / approvals behavior |
| --- | --- |
| HTTP success, `success: true`, `generated: true` | Preserve the response, metrics and product manifest; register every PDF/DOCX pair; set local specification status to `Generated`. |
| HTTP success, `success: true`, `generated: false` | Preserve the message, empty products and null links; register the input only; do not change approval status. |
| Java HTTP error | Preserve its HTTP status, useful error information and metrics; do not mark the specification generated. |
| HTTP 200 with a failure flag or invalid result | Return failure, not an upload-success acknowledgement. |
| Java unreachable | Return HTTP 502. |
| Upstream transport timeout | Return HTTP 504, warning that Java may still be processing. |
| Browser request cancelled | Abort forwarding and return cancellation status where a response is still possible. This does not assert that Java's work was cancelled. |
| Java succeeded but SQLite registration failed | Return HTTP 500 with Java's result/metrics/downloads preserved and an explicit local-persistence error. Do not automatically repeat conversion. |

Only a real generated result advances specification status. Clearance submission,
approval, pricing and bid-pack actions remain separate and are not triggered by
upload. The pages refresh persisted state rather than issuing a second workflow
PATCH.

## Timeouts

There is no replacement arbitrary application timer. The premature 3-second
abort is removed, and the browser request's cancellation signal is forwarded.
Existing HTTP transport and deployment limits still apply; timeout failures are
reported rather than swallowed.

The Java AI client has a 180-second read timeout **per model request**, with
bounded retries and up to five parallel batches. That is not a total conversion
deadline. Likewise, the configured servlet connection timeout and async request
timeout do not define a total deadline for this synchronous handler.

Deployment proxy/function limits must permit the expected conversion duration.
On a transport timeout, check the existing progress endpoint before retrying;
aborting the HTTP request does not necessarily stop Java's model calls.

## Progress, downloads and configuration

- `BACKEND_URL` uses the established `http://localhost:8090` default. An explicit
  port 8080 is no longer silently excluded. No environment files were changed.
- The Next.js progress route forwards Java's existing snapshot and metrics.
- Generated download URLs retain their field names but point to a same-origin,
  conversion-specific download proxy. Java and Next.js have separate document
  directories, so merely returning Java's relative `/documents/...` URLs would
  point browsers at files that are not in Next.js storage.
- The download proxy accepts only one tender ID and one PDF/DOCX filename; it
  cannot fetch arbitrary URLs.
- Next.js loads the existing Java-served compliance progress client through a
  same-origin route. This preserves upload percentage, live progress, metrics and
  review notes without maintaining a second copy.
- Existing JWT/header resolution is reused and authorization is forwarded.
- No Java extraction code, model settings, database schema, approval handlers,
  dependency manifests or `.env` files are changed.

## Checks

With the available Node 24 runtime, the route and caller tests require no package
installation, real database, application server or AI credentials:

```shell
cd frontend
node --experimental-vm-modules scripts/test-tech-spec-upload.cjs
```

They execute the actual TypeScript handlers using Node's type stripping, mock
only their external boundaries, and exercise synchronous completion beyond three
seconds, metadata/state changes, error propagation, cancellation, progress and
downloads. This is not a substitute for full TypeScript type checking or a Next.js
production build when complete frontend dependencies are available.

Backend regression/build check with existing dependencies:

```shell
cd backend
mvn -o verify
```
