
[![License](https://img.shields.io/github/license/philfv9/spmf-server.svg)](https://github.com/philfv9/spmf-server/blob/main/LICENSE)
[![Release](https://img.shields.io/github/v/release/philfv9/spmf-server.svg)](https://github.com/philfv9/spmf-server/releases/latest)
[![Stars](https://img.shields.io/github/stars/philfv9/spmf-server.svg)](https://github.com/philfv9/spmf-server/stargazers)

# SPMF-Server

A **REST API server** that exposes the [SPMF](http://philippe-fournier-viger.com/spmf/)
data-mining library over HTTP, so that any language or tool can submit mining jobs
and retrieve results without needing a local Java integration.

---

## Table of Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Configuration](#configuration)
- [Running the Server](#running-the-server)
- [REST API Reference](#rest-api-reference)
- [Job Lifecycle](#job-lifecycle)
- [Clients](#clients)
- [Troubleshooting](#troubleshooting)
- [Authors and License](#authors-and-license)

---

## Overview

[SPMF](http://philippe-fournier-viger.com/spmf/) is one of the largest open-source
data-mining libraries, specialised in pattern mining, providing over 300 algorithms for:

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

```
+----------------+        HTTP / JSON        +------------------+
|   Any Client   | ----------------------->  |   SPMF-Server    |
|  (curl, Python,| <-----------------------  |   (Java / HTTP)  |
|   GUI, …)      |    results, job status    +--------+---------+
+----------------+                                    |
                                                      v
                                             +------------------+
                                             |   SPMF Library   |
                                             |  300+ algorithms |
                                             +------------------+
```

**Related projects:**

| Project | Description |
|---|---|
| [SPMF Library](https://github.com/philfv9/spmf) | The core SPMF data-mining library (Java) |
| [spmf-server-pythonclient](https://github.com/philfv9/spmf-server-pythonclient) | Ready-to-use Python CLI and GUI clients for SPMF-Server |
| [SPMF Website](http://philippe-fournier-viger.com/spmf/) | Official documentation, algorithm list, and downloads |

---

## Requirements

- **Java 11 or later**
- **`spmf-server.jar`** — the SPMF-Server application jar
- **`spmf.jar`** — the SPMF library jar (available from the
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

### File Layout

Before starting, make sure both jars are in the **same folder**:

```
my-server/
├── spmf-server.jar
├── spmf.jar
└── spmf-server.properties   ← optional, created from the example above
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

Omit all arguments to launch the Swing graphical interface.
This mode requires a display and is not suitable for headless servers.

**Windows:**
```bat
java -Xmx512m -cp "spmf-server.jar;spmf.jar" ca.pfv.spmf.server.ServerMain
```

**Linux / macOS:**
```bash
java -Xmx512m -cp "spmf-server.jar:spmf.jar" ca.pfv.spmf.server.ServerMain
```

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

## REST API Reference

All requests and responses use `Content-Type: application/json`.

---

### `GET /api/health`

Returns server status, version, uptime, and job queue counts.

<details>
<summary>Example response</summary>

```json
{
  "status": "UP",
  "version": "1.0.0",
  "spmfAlgorithmsLoaded": 231,
  "uptimeSeconds": 3742,
  "activeJobs": 1,
  "queuedJobs": 0,
  "totalJobsInRegistry": 4
}
```

</details>

---

### `GET /api/info`

Returns the active server configuration as a flat JSON object, including
`jobTimeoutMinutes` and `jobTtlMinutes` so clients can know the effective
limits without accessing the properties file.

---

### `GET /api/algorithms`

Returns all available algorithm names and their categories.

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

Returns the full descriptor for one algorithm: parameter names, types,
example values, and whether each parameter is mandatory or optional.

<details>
<summary>Example response — Apriori</summary>

```json
{
  "name": "Apriori",
  "algorithmCategory": "FREQUENT_ITEMSET_MINING",
  "algorithmType": "...",
  "implementationAuthorNames": "Philippe Fournier-Viger",
  "documentationURL": "https://...",
  "inputFileTypes":  ["TRANSACTION_DATABASE"],
  "outputFileTypes": ["FREQUENT_ITEMSETS"],
  "numberOfMandatoryParameters": 1,
  "parameters": [
    {
      "name": "minsup",
      "parameterType": "Double",
      "example": "0.4",
      "isOptional": false
    },
    {
      "name": "Max pattern length",
      "parameterType": "Integer",
      "example": "4",
      "isOptional": true
    }
  ]
}
```

</details>

---

### `POST /api/run`

Submit a new mining job. The server immediately returns a job ID; use
`GET /api/jobs/{jobId}` to poll for completion.

**Request body:**

```json
{
  "algorithmName": "Apriori",
  "parameters":    ["0.5"],
  "inputData":     "1 2 3\n2 3 4\n1 2\n",
  "inputEncoding": "plain"
}
```

| Field | Required | Description |
|---|---|---|
| `algorithmName` | Yes | Exact name from `GET /api/algorithms` |
| `parameters` | Yes | Ordered list of parameter values as strings |
| `inputData` | Yes | Raw input text or Base64-encoded string |
| `inputEncoding` | No | `"plain"` (default) or `"base64"` |

**Response:** `202 Accepted`

```json
{
  "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status":        "PENDING",
  "algorithmName": "Apriori",
  "submittedAt":   "2026-01-01T00:00:00Z"
}
```

**Error responses:**

| HTTP code | Cause |
|---|---|
| `400 Bad Request` | Unknown algorithm name, wrong parameter count or type |
| `401 Unauthorized` | Missing or incorrect `X-API-Key` header |
| `413 Payload Too Large` | Input data exceeds `input.maxSizeMb` |
| `503 Service Unavailable` | Job queue is full (`job.maxQueueSize` reached) |

---

### `GET /api/jobs/{jobId}`

Poll the status of a submitted job.

| `status` value | Meaning |
|---|---|
| `PENDING` | Waiting in the execution queue |
| `RUNNING` | Algorithm is currently executing in a child JVM |
| `DONE` | Completed successfully — result is ready |
| `FAILED` | Algorithm or server error — check `console` output |

<details>
<summary>Example response — DONE</summary>

```json
{
  "jobId":            "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "algorithmName":    "Apriori",
  "status":           "DONE",
  "submittedAt":      "2026-01-01T10:23:01Z",
  "startedAt":        "2026-01-01T10:23:01Z",
  "finishedAt":       "2026-01-01T10:23:02Z",
  "executionTimeMs":  385,
  "errorMessage":     null
}
```

</details>

---

### `GET /api/jobs/{jobId}/result`

Fetch the algorithm's output text for a completed (`DONE`) job.

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

Fetch the stdout/stderr output captured from the child JVM process that ran
the algorithm. Useful for diagnosing parameter errors, unexpected results,
or timeout kills.

> **Important:** Always fetch console output **before** calling `DELETE`.
> Deleting a job permanently removes its working directory and the console
> log with it.

<details>
<summary>Example response — successful run</summary>

```json
{
  "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status":        "DONE",
  "lines":         12,
  "consoleOutput": "[SpmfChild] Starting algorithm: 'Apriori' with 1 parameter(s)\n=== Apriori ===\nMinimum support: 50 %\n[SpmfChild] Algorithm completed successfully.\n"
}
```

</details>

<details>
<summary>Example response — timeout kill</summary>

```json
{
  "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status":        "FAILED",
  "lines":         5,
  "consoleOutput": "[SpmfChild] Starting algorithm: 'PrefixSpan' with 2 parameter(s)\n...\n[TIMEOUT] Algorithm exceeded 10 minute(s) and was forcibly killed.\n"
}
```

</details>

---

### `GET /api/jobs`

Returns a summary list of all jobs currently in the server registry
(PENDING, RUNNING, DONE, and FAILED jobs that have not yet been purged
by the TTL cleaner or explicitly deleted).

---

### `DELETE /api/jobs/{jobId}`

Removes the job and all its working files (input, output, console log) from
the server immediately, regardless of the TTL setting.

Call this after retrieving all results to keep the server registry clean and
reclaim disk space.

---

## Job Lifecycle

```
Client                                  Server
  |                                       |
  |--- POST /api/run ------------------->  |  Job created → returns jobId (202)
  |                                       |  status: PENDING
  |--- GET  /api/jobs/{id} ------------>  |  status: PENDING  (in queue)
  |--- GET  /api/jobs/{id} ------------>  |  status: RUNNING  (child JVM active)
  |--- GET  /api/jobs/{id} ------------>  |  status: DONE     (or FAILED)
  |                                       |
  |--- GET  /api/jobs/{id}/console ----->  |  ← fetch this FIRST
  |--- GET  /api/jobs/{id}/result ------>  |  ← then fetch result
  |                                       |
  |--- DELETE /api/jobs/{id} ---------->  |  clean up working files
  |                                       |
                  (or wait for TTL)
                                          |  background cleaner purges
                                          |  jobs older than job.ttlMinutes
```

**Rule:** Always fetch **console** before **result** before **delete**.
Once a job is deleted (either explicitly or by the TTL cleaner), all output
files are permanently removed from disk.

### Timeout behaviour

If an algorithm runs longer than `job.timeoutMinutes`, the server:

1. Sends **SIGTERM** to the child JVM and waits 2 seconds.
2. If still alive, sends **SIGKILL**.
3. Marks the job as **FAILED** with an error message indicating the timeout.
4. Appends a `[TIMEOUT]` annotation to `console.log`.

The job then enters the normal TTL countdown and is purged after
`job.ttlMinutes` minutes.

---

## Clients

A ready-to-use Python client package for SPMF-Server is available at:

**[https://github.com/philfv9/spmf-server-pythonclient](https://github.com/philfv9/spmf-server-pythonclient)**

The package provides both a command-line client and a graphical desktop client,
requiring no Java installation on the client machine — only Python 3.

| File | Description |
|---|---|
| `spmf-client.py` | Full-featured command-line client covering every API endpoint |
| `spmf-gui.py` | Graphical desktop client built with Python and tkinter |

See the
[spmf-server-pythonclient README](https://github.com/philfv9/spmf-server-pythonclient#readme)
for installation instructions and usage examples.

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
- Official SPMF website: [http://philippe-fournier-viger.com/spmf/](http://philippe-fournier-viger.com/spmf/)
