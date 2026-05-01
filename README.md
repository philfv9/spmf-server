
[![License](https://img.shields.io/github/license/philfv9/spmf-server.svg)](https://github.com/philfv9/spmf-server/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/philfv9/spmf-server.svg)](https://github.com/philfv9/spmf-server/releases/latest)
[![Stars](https://img.shields.io/github/stars/philfv9/spmf-server.svg)](https://github.com/philfv9/spmf-server/stargazers)
[![Last Commit](https://img.shields.io/github/last-commit/philfv9/spmf-server-webclient)](https://github.com/philfv9/spmf-server-webclient/commits/main)
[![SPMF](https://img.shields.io/badge/SPMF-300%2B%20Algorithms-blue)](http://www.philippe-fournier-viger.com/spmf/)

# SPMF-Server

<div align="center">
  <img src="/images/spmf-server-logo.png" alt="SPMF server">
</div>

A **REST API server** that exposes the [SPMF](http://philippe-fournier-viger.com/spmf/)
data-mining library over HTTP, so that any language or tool can submit mining jobs
and retrieve results without needing a local Java integration.
This includes a [Python client](https://github.com/philfv9/spmf-server-pythonclient) and also a [Web client](https://github.com/philfv9/spmf-server-webclient).

---

## Table of Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Configuration](#configuration)
- [Running the Server](#running-the-server)
- [Clients](#clients-for-the-spmf-server)
- [REST API Reference](#rest-api-reference)
- [Job Lifecycle](#job-lifecycle)
- [Troubleshooting](#troubleshooting)
- [Authors and License](#authors-and-license)

---

## Overview

[SPMF](http://philippe-fournier-viger.com/spmf/) is the largest open-source
data-mining library that is specialised in pattern mining, providing over 300 algorithms for:

- Frequent itemset mining (Apriori, FP-Growth, …)
- Sequential pattern mining
- Association rule mining
- Sequential rule mining
- Episode rule mining
- Graph mining
- Clustering, classification, and more

**SPMF-Server** wraps the SPMF Java library in a lightweight HTTP server.
Clients submit a job (algorithm name + input data + parameters), the server
runs the algorithm in an isolated child JVM process, and the client retrieves
the result and console output when the job completes.

<div align="center">
  <img src="/images/spmf-server.png" alt="SPMF GUI use case" width="600">
</div>

**Related projects:**

| Project | Description |
|---|---|
| [SPMF Library](https://github.com/philfv9/spmf) | The core SPMF data-mining library (Java) |
| [spmf-server-pythonclient](https://github.com/philfv9/spmf-server-pythonclient) | Ready-to-use Python CLI and GUI clients for SPMF-Server |
| [spmf-server-webclient](https://github.com/philfv9/spmf-server-webclient) | A HTML+JS Web-based client for the SPMF-Server |
| [SPMF Website](http://philippe-fournier-viger.com/spmf/) | Official documentation, algorithm list, and downloads |

---

## Requirements

- **Java 11 or later**
- **`spmf-server.jar`** — the SPMF-Server application jar （can be downloaded from the release page)
- **`spmf.jar`** — the SPMF library jar (available from the release page of the
  [SPMF GitHub repository](https://github.com/philfv9/spmf) or the
  [SPMF website](http://philippe-fournier-viger.com/spmf/))

> **Important:** `spmf-server.jar` and `spmf.jar` **must be placed in the same
> folder**. The server passes the classpath to a child JVM process for each job,
> and both jars must be resolvable from the same directory.

---

## Configuration

The server is configured via a `.properties` file (default name:
`spmf-server.properties`). All properties are optional — sensible defaults
are used for any property that is absent or invalid.

| Property | Default | Description |
|---|---|---|
| `server.port` | `8585` | TCP port the HTTP server listens on (1–65535) |
| `server.host` | `0.0.0.0` | Bind address (`0.0.0.0` = all interfaces) |
| `executor.coreThreads` | `4` | Always-alive worker threads in the job pool (≥ 1) |
| `executor.maxThreads` | `8` | Maximum worker threads under peak load (≥ coreThreads) |
| `job.ttlMinutes` | `30` | Minutes a **finished** job is retained before auto-cleanup (≥ 1) |
| `job.timeoutMinutes` | `10` | Maximum minutes an **algorithm** is allowed to run before being killed (≥ 1) |
| `job.maxQueueSize` | `100` | Maximum jobs waiting in the submission queue (≥ 1) |
| `work.dir` | `./spmf-work` | Directory for per-job input / output / console files |
| `input.maxSizeMb` | `50` | Maximum upload size (MB) per job (≥ 1) |
| `security.apiKey` | *(empty — disabled)* | If set, every request must include `X-API-Key: <value>` |
| `log.level` | `INFO` | JUL log level (`OFF` `SEVERE` `WARNING` `INFO` `FINE` `FINER` `FINEST` `ALL`) |
| `log.file` | `./logs/spmf-server.log` | Path to the rotating log file; leave blank to disable |

### `job.ttlMinutes` vs `job.timeoutMinutes`

These two properties are often confused:

| Property | When it fires | What it does |
|---|---|---|
| `job.timeoutMinutes` | While the job is **RUNNING** | Kills the child JVM process if the algorithm exceeds this time |
| `job.ttlMinutes` | After the job is **DONE or FAILED** | Purges the finished job from memory and disk after this many minutes |

A typical setup might use `job.timeoutMinutes=10` (kill slow algorithms after
10 minutes) and `job.ttlMinutes=60` (keep results accessible for 1 hour after
completion).

**Example `spmf-server.properties`:**

```properties
server.port=8585
server.host=0.0.0.0
executor.coreThreads=4
executor.maxThreads=8
job.ttlMinutes=30
job.timeoutMinutes=10
job.maxQueueSize=100
work.dir=./spmf-work
input.maxSizeMb=50
security.apiKey=
log.level=INFO
log.file=./logs/spmf-server.log
```

---

## Running the Server

<div align="center">
  <img src="/images/modes.jpg" alt="SPMF server modes">
</div>

### File Layout

Before starting, make sure both jars are in the **same folder**:

```
my-server/
├── spmf-server.jar
├── spmf.jar
└── spmf-server.properties   ← optional, to store configuration
```

---

### Option 1 — Headless mode with a properties file (recommended)

Pass the path to your properties file as the first argument.
The server starts on the command line with no GUI.

**Windows:**
```bat
java -Xmx512m -cp "spmf-server.jar;spmf.jar" ca.pfv.spmf.server.ServerMain spmf-server.properties
```

**Linux / macOS:**
```bash
java -Xmx512m -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain spmf-server.properties
```

> The only difference between Windows and Linux/macOS is the classpath
> separator: **`;`** on Windows, **`:`** on Linux/macOS.

If the server is running, you will see information in the console as follows (here, on Windows):

<div align="center">
  <img src="/images/headless.png" alt="SPMF SERVER Headless mode" width="700">
</div>

---

### Option 2 — Explicit headless flag

Use `--headless` to force CLI mode explicitly, with or without a properties file.

**Windows:**
```bat
java -Xmx512m -cp "spmf-server.jar;spmf.jar" ca.pfv.spmf.server.ServerMain --headless spmf-server.properties
```

**Linux / macOS:**
```bash
java -Xmx512m -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain --headless spmf-server.properties
```

---

### Option 3 — GUI mode (desktop environments only)

It is also possible to launch the server with its graphical interface. This requires a display and is not suitable for headless servers.

**Windows:**
If Java is correctly installed, you can launch it by double-clicking on the spmf-server.jar file. This will work if the spmf.jar file is in the same directory as spmf-server.jar. Alternatively, the server can be launched in GUI mode from the command line as follows:
```bat
java -Xmx512m -cp "spmf-server.jar;spmf.jar" ca.pfv.spmf.server.ServerMain
```

**Linux / macOS:**
```bash
java -Xmx512m -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain
```

This will open the GUI:

<div align="center">
  <img src="/images/server.jpg" alt="SPMF server GUI">
</div>


The GUI lets you:

- Browse for and reload a `.properties` file without restarting.
- Edit all configuration values in text fields (fields are locked while
  the server is running to prevent confusing mid-run edits).
- Start and stop the server with a single button click.
- Monitor the live job queue in the **Jobs** tab.
- View algorithm output, error messages, and raw child-process console logs
  without leaving the application.
- Open the working directory in your system file manager.

---

### Option 4 — All defaults, no properties file

If `spmf-server.properties` does not exist the server starts with built-in
defaults (port 8585, 10-minute execution timeout, 30-minute TTL, no API key,
etc.).

**Windows:**
```bat
java -Xmx512m -cp "spmf-server.jar;spmf.jar" ca.pfv.spmf.server.ServerMain --headless
```

**Linux / macOS:**
```bash
java -Xmx512m -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain --headless
```

---

### Controlling the child JVM heap

Each algorithm job runs in its own child JVM process. The child heap defaults
to **1 GB**. The `-Xmx` flag on the main command controls only the **server
process** heap; to increase the child heap set the `SPMF_CHILD_XMX`
environment variable before starting the server:

**Windows:**
```bat
set SPMF_CHILD_XMX=2g
java -Xmx512m -cp "spmf-server.jar;spmf.jar" ca.pfv.spmf.server.ServerMain spmf-server.properties
```

**Linux / macOS:**
```bash
export SPMF_CHILD_XMX=2g
java -Xmx512m -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain spmf-server.properties
```

> **Tip:** If jobs fail with `OutOfMemoryError` in the console log, increase
> `SPMF_CHILD_XMX`. If the algorithm simply runs too long, decrease
> `job.timeoutMinutes` to reclaim resources sooner.

---

### Verifying the server started

Once running, open a browser or run:

```bash
curl http://localhost:8585/api/health
```

Expected response:

```json
{
  "status": "UP",
  "version": "1.0.0",
  "spmfAlgorithmsLoaded": 231,
  "uptimeSeconds": 5,
  "activeJobs": 0,
  "queuedJobs": 0,
  "totalJobsInRegistry": 0
}
```

Press **Ctrl+C** to stop the server. It will wait up to 30 seconds for
in-flight jobs to finish before shutting down.

---

## Clients for the SPMF-Server

There are two clients designed to work with the SPMF-Server for now.

* A Python client package for SPMF-Server is available with optional GUI: **[https://github.com/philfv9/spmf-server-pythonclient](https://github.com/philfv9/spmf-server-pythonclient)**

* A Web client (HTML+CSS+JS) that can run in your browser is available at: **[https://github.com/philfv9/spmf-server-webclient](https://github.com/philfv9/spmf-server-webclient)**

---

## REST API Reference

All endpoints are served under the base path `/api`. Every request and response
uses `Content-Type: application/json`. If `security.apiKey` is configured, all
requests must include the header `X-API-Key: <your-key>`.

### Endpoint Summary

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/health` | Server liveness check with runtime statistics |
| `GET` | `/api/info` | Active server configuration |
| `GET` | `/api/algorithms` | List all available algorithms |
| `GET` | `/api/algorithms/{algorithmName}` | Full descriptor for one algorithm |
| `POST` | `/api/run` | Submit a new mining job |
| `GET` | `/api/jobs` | List all jobs in the registry |
| `GET` | `/api/jobs/{jobId}` | Full status of one job |
| `GET` | `/api/jobs/{jobId}/result` | Algorithm output for a completed job |
| `GET` | `/api/jobs/{jobId}/console` | Child-process stdout/stderr for a job |
| `DELETE` | `/api/jobs/{jobId}` | Delete a job and its working files |

---

### Authentication

If `security.apiKey` is set in `spmf-server.properties`, every request must
include the following HTTP header:

```
X-API-Key: <your-configured-key>
```

Requests with a missing or incorrect key receive `401 Unauthorized`. When
`security.apiKey` is empty (the default), no authentication is required.

---

### Common Error Response Format

All error responses share the same JSON shape:

```json
{
  "error":  "Human-readable description of what went wrong.",
  "status": 404
}
```

| Field | Type | Description |
|---|---|---|
| `error` | `string` | Human-readable error message |
| `status` | `integer` | Mirrors the HTTP status code |

---

### `GET /api/health`

Returns a liveness check with live runtime statistics. Use this endpoint to
confirm the server is running and to monitor queue pressure.

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `status` | `string` | Always `"UP"` |
| `version` | `string` | Server version string |
| `spmfAlgorithmsLoaded` | `integer` | Number of algorithms available in the catalogue |
| `uptimeSeconds` | `integer` | Seconds elapsed since the server started |
| `activeJobs` | `integer` | Jobs currently executing in a child JVM |
| `queuedJobs` | `integer` | Jobs waiting in the submission queue |
| `totalJobsInRegistry` | `integer` | All tracked jobs, including terminal ones not yet purged |

<details>
<summary>Example response</summary>

```json
{
  "status":               "UP",
  "version":              "1.0.0",
  "spmfAlgorithmsLoaded": 231,
  "uptimeSeconds":        3742,
  "activeJobs":           1,
  "queuedJobs":           0,
  "totalJobsInRegistry":  4
}
```

</details>

---

### `GET /api/info`

Returns the active server configuration as a flat JSON object. Useful for
clients that need to know effective limits (timeout, TTL, max input size)
without accessing the properties file directly.

> **Security note:** The actual API key value is never returned. Only the
> boolean flag `apiKeyEnabled` is exposed.

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `version` | `string` | Server version string |
| `host` | `string` | Bind address the server is listening on |
| `port` | `integer` | TCP port the server is listening on |
| `coreThreads` | `integer` | Minimum number of worker threads in the job pool |
| `maxThreads` | `integer` | Maximum number of worker threads under peak load |
| `jobTtlMinutes` | `integer` | Minutes a finished job is retained before auto-purge |
| `jobTimeoutMinutes` | `integer` | Maximum minutes an algorithm may run before being killed |
| `maxQueueSize` | `integer` | Maximum number of jobs allowed in the submission queue |
| `workDir` | `string` | Path to the directory where per-job files are stored |
| `maxInputSizeMb` | `integer` | Maximum allowed input upload size in megabytes |
| `apiKeyEnabled` | `boolean` | Whether API key authentication is active |
| `logLevel` | `string` | Active JUL log level |

<details>
<summary>Example response</summary>

```json
{
  "version":          "1.0.0",
  "host":             "0.0.0.0",
  "port":             8585,
  "coreThreads":      4,
  "maxThreads":       8,
  "jobTtlMinutes":    30,
  "jobTimeoutMinutes": 10,
  "maxQueueSize":     100,
  "workDir":          "./spmf-work",
  "maxInputSizeMb":   50,
  "apiKeyEnabled":    false,
  "logLevel":         "INFO"
}
```

</details>

---

### `GET /api/algorithms`

Returns the names and categories of all algorithms currently available in the
SPMF catalogue.

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `count` | `integer` | Total number of available algorithms |
| `algorithms` | `array` | List of algorithm descriptor objects |
| `algorithms[].name` | `string` | Exact algorithm name (use this in `POST /api/run`) |
| `algorithms[].algorithmCategory` | `string` | Category the algorithm belongs to |

<details>
<summary>Example response (truncated)</summary>

```json
{
  "count": 231,
  "algorithms": [
    { "name": "Apriori",           "algorithmCategory": "FREQUENT_ITEMSET_MINING" },
    { "name": "FPGrowth_itemsets", "algorithmCategory": "FREQUENT_ITEMSET_MINING" },
    { "name": "PrefixSpan",        "algorithmCategory": "SEQUENTIAL_PATTERN_MINING" }
  ]
}
```

</details>

---

### `GET /api/algorithms/{algorithmName}`

Returns the full descriptor for a single algorithm, including all parameters
with their types, example values, and whether each is mandatory or optional.

**Path parameter**

| Parameter | Description |
|---|---|
| `algorithmName` | Exact algorithm name as returned by `GET /api/algorithms` (case-sensitive) |

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `name` | `string` | Algorithm name |
| `algorithmCategory` | `string` | Category (e.g. `FREQUENT_ITEMSET_MINING`) |
| `algorithmType` | `string` | Internal type identifier |
| `implementationAuthorNames` | `string` | Author(s) of the implementation |
| `documentationURL` | `string` | URL to the algorithm's documentation page |
| `inputFileTypes` | `array<string>` | Expected input file format(s) |
| `outputFileTypes` | `array<string>` | Output file format(s) produced |
| `numberOfMandatoryParameters` | `integer` | Count of required parameters |
| `parameters` | `array` | Ordered list of parameter descriptors |
| `parameters[].name` | `string` | Parameter name |
| `parameters[].parameterType` | `string` | Java type (`Double`, `Integer`, `String`, …) |
| `parameters[].example` | `string` | An example value for this parameter |
| `parameters[].isOptional` | `boolean` | Whether the parameter may be omitted |

**Error responses**

| HTTP code | Cause |
|---|---|
| `404 Not Found` | No algorithm with the given name exists in the catalogue |

<details>
<summary>Example response — Apriori</summary>

```json
{
  "name":                        "Apriori",
  "algorithmCategory":           "FREQUENT_ITEMSET_MINING",
  "algorithmType":               "...",
  "implementationAuthorNames":   "Philippe Fournier-Viger",
  "documentationURL":            "https://...",
  "inputFileTypes":              ["TRANSACTION_DATABASE"],
  "outputFileTypes":             ["FREQUENT_ITEMSETS"],
  "numberOfMandatoryParameters": 1,
  "parameters": [
    {
      "name":          "minsup",
      "parameterType": "Double",
      "example":       "0.4",
      "isOptional":    false
    },
    {
      "name":          "Max pattern length",
      "parameterType": "Integer",
      "example":       "4",
      "isOptional":    true
    }
  ]
}
```

</details>

---

### `POST /api/run`

Submit a new mining job. The server validates the request, enqueues the job,
and immediately returns a job ID. Use [`GET /api/jobs/{jobId}`](#get-apijobsjobid)
to poll for completion.

**Request body** (`Content-Type: application/json`)

| Field | Type | Required | Description |
|---|---|---|---|
| `algorithmName` | `string` | Yes | Exact algorithm name from `GET /api/algorithms` (case-sensitive) |
| `parameters` | `array<string>` | Yes | Ordered list of parameter values as strings. Pass an empty array `[]` if the algorithm requires no parameters |
| `inputData` | `string` | Yes | Raw input text or Base64-encoded bytes |
| `inputEncoding` | `string` | No | `"plain"` (default) or `"base64"` |

> **Parameter ordering:** Parameters must be supplied in the same order as
> listed in `GET /api/algorithms/{algorithmName}`. Mandatory parameters come
> first. Optional parameters may be omitted by truncating the array from the
> end.

<details>
<summary>Example request body</summary>

```json
{
  "algorithmName": "Apriori",
  "parameters":    ["0.5"],
  "inputData":     "1 2 3\n2 3 4\n1 2\n",
  "inputEncoding": "plain"
}
```

</details>

**Response `202 Accepted`**

The job was accepted and is now queued for execution.

| Field | Type | Description |
|---|---|---|
| `jobId` | `string` | UUID that uniquely identifies the job |
| `status` | `string` | Initial status — always `"PENDING"` |
| `algorithmName` | `string` | The algorithm name as submitted |
| `submittedAt` | `string` | ISO-8601 UTC timestamp of submission |

<details>
<summary>Example response</summary>

```json
{
  "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status":        "PENDING",
  "algorithmName": "Apriori",
  "submittedAt":   "2026-01-01T00:00:00Z"
}
```

</details>

**Error responses**

| HTTP code | Cause |
|---|---|
| `400 Bad Request` | Malformed JSON body, missing required field, unknown algorithm name, or wrong parameter count or type |
| `401 Unauthorized` | Missing or incorrect `X-API-Key` header |
| `413 Payload Too Large` | `inputData` size exceeds `input.maxSizeMb` |
| `503 Service Unavailable` | The submission queue has reached `job.maxQueueSize` — retry later |

---

### `GET /api/jobs`

Returns a summary list of all jobs currently in the server registry —
including `PENDING`, `RUNNING`, `DONE`, and `FAILED` jobs that have not yet
been purged by the TTL cleaner or explicitly deleted.

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `count` | `integer` | Total number of jobs in the registry |
| `jobs` | `array` | List of job summary objects |
| `jobs[].jobId` | `string` | Job UUID |
| `jobs[].algorithmName` | `string` | Algorithm used for this job |
| `jobs[].status` | `string` | Current job status |
| `jobs[].submittedAt` | `string` | ISO-8601 UTC submission timestamp |

<details>
<summary>Example response</summary>

```json
{
  "count": 2,
  "jobs": [
    {
      "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
      "algorithmName": "Apriori",
      "status":        "DONE",
      "submittedAt":   "2026-01-01T10:23:01Z"
    },
    {
      "jobId":         "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "algorithmName": "PrefixSpan",
      "status":        "RUNNING",
      "submittedAt":   "2026-01-01T10:25:00Z"
    }
  ]
}
```

</details>

---

### `GET /api/jobs/{jobId}`

Returns the full status detail for a single job.

**Path parameter**

| Parameter | Description |
|---|---|
| `jobId` | UUID of the job as returned by `POST /api/run` |

**Job status values**

| `status` | Meaning |
|---|---|
| `PENDING` | Waiting in the execution queue — no child process started yet |
| `RUNNING` | Algorithm is executing in a child JVM process |
| `DONE` | Completed successfully — result and console output are available |
| `FAILED` | Algorithm or server error — check `console` output for details |

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `jobId` | `string` | Job UUID |
| `algorithmName` | `string` | Algorithm used for this job |
| `status` | `string` | Current job status |
| `submittedAt` | `string` | ISO-8601 UTC submission timestamp |
| `startedAt` | `string\|null` | ISO-8601 UTC timestamp when execution began, or `null` if still `PENDING` |
| `finishedAt` | `string\|null` | ISO-8601 UTC timestamp when execution ended, or `null` if not yet finished |
| `executionTimeMs` | `integer\|null` | Wall-clock execution time in milliseconds, or `null` if not yet finished |
| `errorMessage` | `string\|null` | Human-readable error description for `FAILED` jobs, otherwise `null` |

**Error responses**

| HTTP code | Cause |
|---|---|
| `404 Not Found` | No job with the given `jobId` exists in the registry |

<details>
<summary>Example response — DONE</summary>

```json
{
  "jobId":           "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "algorithmName":   "Apriori",
  "status":          "DONE",
  "submittedAt":     "2026-01-01T10:23:01Z",
  "startedAt":       "2026-01-01T10:23:01Z",
  "finishedAt":      "2026-01-01T10:23:02Z",
  "executionTimeMs": 385,
  "errorMessage":    null
}
```

</details>

<details>
<summary>Example response — PENDING</summary>

```json
{
  "jobId":           "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "algorithmName":   "PrefixSpan",
  "status":          "PENDING",
  "submittedAt":     "2026-01-01T10:25:00Z",
  "startedAt":       null,
  "finishedAt":      null,
  "executionTimeMs": null,
  "errorMessage":    null
}
```

</details>

<details>
<summary>Example response — FAILED</summary>

```json
{
  "jobId":           "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "algorithmName":   "PrefixSpan",
  "status":          "FAILED",
  "submittedAt":     "2026-01-01T10:26:00Z",
  "startedAt":       "2026-01-01T10:26:01Z",
  "finishedAt":      "2026-01-01T10:36:01Z",
  "executionTimeMs": 600000,
  "errorMessage":    "Algorithm exceeded the configured timeout of 10 minute(s)."
}
```

</details>

---

### `GET /api/jobs/{jobId}/result`

Fetches the algorithm output text for a completed (`DONE`) job.

**Path parameter**

| Parameter | Description |
|---|---|
| `jobId` | UUID of the job |

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `jobId` | `string` | Job UUID |
| `outputData` | `string` | Raw algorithm output text |
| `outputEncoding` | `string` | Always `"plain"` in the current version |
| `executionTimeMs` | `integer` | Wall-clock execution time in milliseconds |

**Error responses**

| HTTP code | Cause |
|---|---|
| `404 Not Found` | No job with the given `jobId` exists in the registry |
| `409 Conflict` | Job is still `PENDING` or `RUNNING` — result not yet available |
| `422 Unprocessable Entity` | Job `FAILED` — no result is available; check `/console` |

<details>
<summary>Example response</summary>

```json
{
  "jobId":           "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "outputData":      "1 2 #SUP: 3\n2 3 #SUP: 2\n...",
  "outputEncoding":  "plain",
  "executionTimeMs": 385
}
```

</details>

---

### `GET /api/jobs/{jobId}/console`

Fetches the stdout and stderr captured from the child JVM process that ran
the algorithm. Useful for diagnosing parameter errors, unexpected results,
or timeout kills.

> **Important:** Always fetch console output **before** calling
> `DELETE /api/jobs/{jobId}`. Deleting a job permanently removes its working
> directory and the console log with it.

**Path parameter**

| Parameter | Description |
|---|---|
| `jobId` | UUID of the job |

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `jobId` | `string` | Job UUID |
| `status` | `string` | Current job status at the time of the request |
| `consoleOutput` | `string` | Full captured stdout + stderr from the child JVM |
| `lines` | `integer` | Number of lines in `consoleOutput` |

**Error responses**

| HTTP code | Cause |
|---|---|
| `404 Not Found` | No job with the given `jobId` exists in the registry |
| `410 Gone` | Job is `PENDING` (child process not yet started), or is `RUNNING` but the log file has not been flushed to disk yet |
| `500 Internal Server Error` | Job is in a terminal state but no `console.log` file was produced (unexpected) |

<details>
<summary>Example response — successful run</summary>

```json
{
  "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status":        "DONE",
  "consoleOutput": "[SpmfChild] Starting algorithm: 'Apriori' with 1 parameter(s)\n=== Apriori ===\nMinimum support: 50 %\n[SpmfChild] Algorithm completed successfully.\n",
  "lines":         4
}
```

</details>

<details>
<summary>Example response — timeout kill</summary>

```json
{
  "jobId":         "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "status":        "FAILED",
  "consoleOutput": "[SpmfChild] Starting algorithm: 'PrefixSpan' with 2 parameter(s)\n...\n[TIMEOUT] Algorithm exceeded 10 minute(s) and was forcibly killed.\n",
  "lines":         3
}
```

</details>

---

### `DELETE /api/jobs/{jobId}`

Removes the job and all its working files (input data, output data, console
log) from the server immediately, regardless of the TTL setting.

Call this after you have retrieved all results to keep the server registry
clean and reclaim disk space.

**Path parameter**

| Parameter | Description |
|---|---|
| `jobId` | UUID of the job to delete |

**Response `200 OK`**

| Field | Type | Description |
|---|---|---|
| `jobId` | `string` | UUID of the deleted job |
| `deleted` | `boolean` | Always `true` on a successful deletion |

**Error responses**

| HTTP code | Cause |
|---|---|
| `404 Not Found` | No job with the given `jobId` exists in the registry |

<details>
<summary>Example response</summary>

```json
{
  "jobId":   "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "deleted": true
}
```

</details>

---

### Recommended Client Workflow

The recommended order of API calls for a complete job lifecycle is:

```
POST   /api/run                          → obtain jobId
  └─► GET    /api/jobs/{jobId}           → poll until status is DONE or FAILED
        ├─► GET    /api/jobs/{jobId}/console  → retrieve diagnostic output
        ├─► GET    /api/jobs/{jobId}/result   → retrieve algorithm output (DONE only)
        └─► DELETE /api/jobs/{jobId}          → clean up working files
```

> **Rule:** Always fetch `/console` and `/result` **before** calling `DELETE`.
> Once a job is deleted — either explicitly or by the TTL cleaner — all output
> files are permanently removed from disk.

---

## Job Lifecycle

The job lifecycle is described below:

<div align="center">
  <img src="/images/workflow.png" alt="SPMF server">
</div>

### Timeout behaviour

If an algorithm runs longer than `job.timeoutMinutes`, the server:

1. Sends **SIGTERM** to the child JVM and waits 2 seconds.
2. If still alive, sends **SIGKILL**.
3. Marks the job as **FAILED** with an error message indicating the timeout.
4. Appends a `[TIMEOUT]` annotation to `console.log`.

The job then enters the normal TTL countdown and is purged after
`job.ttlMinutes` minutes.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection refused` on port 8585 | Server not started | Run the startup command from [Running the Server](#running-the-server) |
| `HTTP 400` on job submission | Wrong parameter type or count | Check `GET /api/algorithms/{name}` for exact parameter types and counts |
| `HTTP 404` on algorithm name | Misspelled or wrong algorithm name | Check `GET /api/algorithms` for the exact name (case-sensitive) |
| `HTTP 401` on all requests | API key mismatch or missing header | Set the `X-API-Key` header to match `security.apiKey` in your properties file |
| `HTTP 413` on job submission | Input file too large | Increase `input.maxSizeMb` in your properties file |
| `HTTP 503` on job submission | Job queue is full | Wait for running jobs to finish, or increase `job.maxQueueSize` and `executor.maxThreads` |
| Job stuck in `PENDING` | All worker threads busy | Wait, or increase `executor.maxThreads` |
| Job `FAILED` immediately | Bad input data or wrong parameters | Check `GET /api/jobs/{id}/console` for the Java stack trace |
| Job `FAILED` with `[TIMEOUT]` in console | Algorithm exceeded `job.timeoutMinutes` | Increase `job.timeoutMinutes` or reduce input size |
| Job `FAILED` with `OutOfMemoryError` in console | Child JVM ran out of heap | Set `SPMF_CHILD_XMX=2g` (or higher) and restart the server |
| Results disappeared before retrieval | Job was purged by the TTL cleaner | Increase `job.ttlMinutes`; fetch results promptly after the job finishes |
| Algorithms not found / `ClassNotFoundException` in console | `spmf.jar` not on classpath | Ensure both `spmf-server.jar` and `spmf.jar` are in the **same folder** and listed in `-cp` |
| Child JVM cannot find jars | Relative classpath entries resolved from wrong directory | Always run the `java` command from the folder containing both jars, or use absolute paths |
| Console output missing after delete | Console fetched after job was deleted | Always fetch `/console` **before** calling `DELETE` |
| Config field changes have no effect | Server must be restarted to apply config changes | Stop the server, edit the properties file, then restart |
| GUI config fields are greyed out | Server is running — fields are locked to prevent mid-run edits | Stop the server first, then edit the fields and restart |

---

## Authors and License

**Author:** Philippe Fournier-Viger and contributors.

This software is distributed under the
[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).
You are free to use, modify, and redistribute it under the terms of that licence.

**Related links:**

- SPMF Library source code: [https://github.com/philfv9/spmf](https://github.com/philfv9/spmf)
- Python client for SPMF-Server: [https://github.com/philfv9/spmf-server-pythonclient](https://github.com/philfv9/spmf-server-pythonclient)
- Web client for SPMF-Server: [https://github.com/philfv9/spmf-server-webclient](https://github.com/philfv9/spmf-server-webclient)
- Official SPMF website: [http://philippe-fournier-viger.com/spmf/](http://philippe-fournier-viger.com/spmf/)
