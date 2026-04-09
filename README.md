```markdown
# SPMF-Server & Python Client

A **REST API server** that exposes the [SPMF](https://www.philippe-fournier-viger.com/spmf/)
data-mining library over HTTP, plus a **command-line Python client** that talks to it.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [SPMF-Server](#spmf-server)
  - [Requirements](#server-requirements)
  - [Configuration](#configuration)
  - [Running the Server](#running-the-server)
  - [REST API Reference](#rest-api-reference)
- [Python Client](#python-client)
  - [Requirements](#client-requirements)
  - [Installation](#installation)
  - [Global Options](#global-options)
  - [Commands](#commands)
  - [Examples](#examples)
- [Running the Example Batch Script](#running-the-example-batch-script)
- [Algorithm Parameters](#algorithm-parameters)
- [Troubleshooting](#troubleshooting)
- [License](#license)

---

## Overview

[SPMF](https://www.philippe-fournier-viger.com/spmf/) is one of the largest open-source
data-mining libraries, providing over 230 algorithms for:

- Frequent itemset mining (Apriori, FP-Growth, …)
- Sequential pattern mining
- Association rule mining
- Clustering, classification, and more

**SPMF-Server** wraps the SPMF Java library in a lightweight HTTP server so that any
language or tool can submit mining jobs and retrieve results without needing a local Java
integration.

**spmf-client.py** is a ready-to-run Python CLI that covers every server endpoint —
submit jobs, poll for completion, retrieve results and console output, and clean up,
all from one script.

```
+------------------+        HTTP / JSON        +------------------+
|  spmf-client.py  | ------------------------> |   SPMF-Server    |
|  (Python CLI)    | <------------------------ |   (Java / HTTP)  |
+------------------+    results, job status    +--------+---------+
                                                        |
                                                        v
                                               +------------------+
                                               |   SPMF Library   |
                                               |  230+ algorithms |
                                               +------------------+
```

---

## Architecture

| Component | Technology | Role |
|---|---|---|
| **SPMF-Server** | Java | Hosts SPMF algorithms, manages job queue, exposes REST API |
| **spmf-client.py** | Python 3 | CLI wrapper for every REST endpoint |
| **RUNCLIENT.BAT** | Windows Batch | Demonstration script running all client commands |

Jobs are **asynchronous**:

1. Client `POST /api/run` → server returns a **Job ID** immediately (`202 Accepted`)
2. Client polls `GET /api/jobs/{id}` until status is `DONE` or `FAILED`
3. Client fetches `GET /api/jobs/{id}/result` and `GET /api/jobs/{id}/console`
4. Client optionally `DELETE /api/jobs/{id}` to free server memory

---

## SPMF-Server

### Server Requirements

- Java 11 or later
- The SPMF jar (`spmf.jar`) present in the server's working directory or classpath

### Configuration

The server is configured via `application.properties` (or environment variables):

| Property | Default | Description |
|---|---|---|
| `server.port` | `8585` | HTTP port the server listens on |
| `spmf.max-concurrent-jobs` | `4` | Maximum parallel algorithm executions |
| `spmf.job-timeout-seconds` | `300` | Kill a job if it runs longer than this |
| `spmf.max-job-registry-size` | `100` | Maximum jobs kept in memory |
| `spmf.api-key` | *(none)* | If set, all requests must send `X-API-Key: <value>` |

### Running the Server

```bash
java -jar spmf-server.jar
```

The server starts on `http://localhost:8585` by default.

To use a custom port:

```bash
java -jar spmf-server.jar --server.port=9090
```

---

### REST API Reference

All requests and responses use `Content-Type: application/json`.

#### Health

```
GET /api/health
```

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

#### Server Info

```
GET /api/info
```

Returns full server configuration.

---

#### List Algorithms

```
GET /api/algorithms
```

Returns all available algorithm names grouped by category.

---

#### Describe Algorithm

```
GET /api/algorithms/{algorithmName}
```

Returns parameter definitions (name, type, example, mandatory/optional) for one algorithm.

<details>
<summary>Example response</summary>

```json
{
  "name": "Apriori",
  "algorithmCategory": "FREQUENT_ITEMSET_MINING",
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

#### Submit a Job

```
POST /api/run
```

**Request body:**

```json
{
  "algorithmName": "Apriori",
  "parameters": ["0.5"],
  "inputData": "1 2 3\n2 3 4\n1 2\n",
  "inputEncoding": "plain"
}
```

| Field | Required | Description |
|---|---|---|
| `algorithmName` | Yes | Exact name from `/api/algorithms` |
| `parameters` | Yes | Ordered list of parameter values as strings |
| `inputData` | Yes | Raw input text or base64-encoded string |
| `inputEncoding` | Yes | `"plain"` or `"base64"` |

**Response:** `202 Accepted`

```json
{
  "jobId": "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status": "PENDING"
}
```

---

#### Poll Job Status

```
GET /api/jobs/{jobId}
```

| Status value | Meaning |
|---|---|
| `PENDING` | Waiting in queue |
| `RUNNING` | Algorithm executing |
| `DONE` | Completed successfully |
| `FAILED` | Algorithm error |

---

#### Fetch Result

```
GET /api/jobs/{jobId}/result
```

Returns the algorithm's output text and execution time in milliseconds.

---

#### Fetch Console Output

```
GET /api/jobs/{jobId}/console
```

Returns lines printed by the SPMF algorithm to stdout/stderr during execution.
Useful for debugging parameter errors or unexpected results.

---

#### List All Jobs

```
GET /api/jobs
```

Returns all jobs currently in the server registry.

---

#### Delete a Job

```
DELETE /api/jobs/{jobId}
```

Removes the job and its files from the server.
Always call this after retrieving results to free server memory.

---

## Python Client

### Client Requirements

- Python 3.8 or later
- `requests` library

### Installation

```bash
pip install requests
```

No other dependencies. Copy `spmf-client.py` to your working directory.

---

### Global Options

Global options **must** be placed **before** the subcommand name.

```
python spmf-client.py [global options] <subcommand> [subcommand arguments]
```

| Option | Default | Description |
|---|---|---|
| `--host` | `localhost` | Server hostname or IP |
| `--port` | `8585` | Server port |
| `--apikey` | *(none)* | Value for `X-API-Key` header |
| `--out <file>` | *(stdout)* | Save output text to a file |
| `--poll-interval` | `1.0` | Seconds between status polls |
| `--timeout` | `300` | Max seconds to wait for job completion |
| `--base64` | off | Encode input file as base64 before sending |
| `--no-cleanup` | off | Skip `DELETE` after `run` (keep job on server) |
| `--raw` | off | Print raw JSON instead of formatted output |

---

### Commands

| Command | Description |
|---|---|
| `health` | Check server health and queue stats |
| `info` | Show full server configuration |
| `list` | List all available algorithms by category |
| `describe <name>` | Show parameters for one algorithm |
| `jobs` | List all jobs in the server registry |
| `run <name> <file> [params…]` | Submit a job, wait for completion, print result |
| `result <jobId>` | Fetch result and console output for a finished job |
| `console <jobId>` | Fetch console output only |
| `delete <jobId>` | Delete a job from the server |

---

### Examples

#### Check server health

```bash
python spmf-client.py --host localhost --port 8585 health
```

#### List all algorithms

```bash
python spmf-client.py list
```

#### Describe an algorithm before running it

```bash
python spmf-client.py describe Apriori
python spmf-client.py describe FPGrowth_itemsets
```

Always describe an algorithm first — it shows you exactly how many parameters
are required, their types, and example values.

#### Run Apriori with minimum support 50%

```bash
python spmf-client.py run Apriori input.txt 0.5
```

#### Run Apriori with optional max-pattern-length (must be an integer)

```bash
python spmf-client.py run Apriori input.txt 0.5 3
```

#### Run FP-Growth

```bash
python spmf-client.py run FPGrowth_itemsets input.txt 0.4
```

#### Save result output to a file

```bash
python spmf-client.py --out results.txt run Apriori input.txt 0.5
```

#### Send input as base64

```bash
python spmf-client.py --base64 run Apriori input.txt 0.5
```

#### Keep the job on the server after running (no auto-delete)

```bash
python spmf-client.py --no-cleanup run Apriori input.txt 0.5
```

#### Get raw JSON from any command

```bash
python spmf-client.py --raw health
python spmf-client.py --raw describe Apriori
```

#### Fetch result and console output for an existing job

```bash
python spmf-client.py result 5d0b27f6-f330-4cfb-9803-53f74c7bfa6a
```

#### Delete a job manually

```bash
python spmf-client.py delete 5d0b27f6-f330-4cfb-9803-53f74c7bfa6a
```

---

### What `run` does automatically

The `run` command handles the full job lifecycle so you do not have to:

```
1. Read input file from disk
2. POST /api/run  →  receive jobId
3. Poll GET /api/jobs/{id}  until DONE or FAILED
4. GET /api/jobs/{id}/console   (fetched BEFORE result and delete)
5. GET /api/jobs/{id}/result
6. Print result output
7. Print console output
8. DELETE /api/jobs/{id}        (unless --no-cleanup)
```

> **Note:** Console output is always fetched **before** the result and before cleanup,
> because deleting the job removes its working directory from disk.

---

## Running the Example Batch Script

`RUNCLIENT.BAT` demonstrates every client command in sequence.

```
RUNCLIENT.BAT
```

It runs 13 examples covering health, info, list, describe, jobs, run (plain, base64,
no-cleanup), raw JSON, file output, FP-Growth, and parameterised Apriori.

**Requirements:**

- Windows (uses `cmd.exe` batch syntax)
- Python 3 on `PATH`
- SPMF-Server running on `localhost:8585`

To change the server address edit the top of the file:

```batch
set HOST=localhost
set PORT=8585
```

---

## Algorithm Parameters

> Getting parameters wrong is the most common source of `400 Bad Request` errors.

### Rules

- Always run `describe <algorithmName>` first to see exact parameter types.
- Pass parameters in the **exact order** shown by `describe`.
- Pass **only mandatory parameters** unless you intentionally want optional ones.
- Pass integer parameters as integers (`3`, not `3.0`).
- Pass double parameters as decimals (`0.5`, not `1/2`).

### Common parameter mistakes

| Mistake | Result | Fix |
|---|---|---|
| Passing `0.6` for an `Integer` param | `400 Bad Request` | Use `1`, `2`, `3`, … |
| Using algorithm name `FPGrowth` | `404 Not Found` | Use exact name `FPGrowth_itemsets` |
| Putting `--base64` after the subcommand | Flag ignored / parse error | Move all `--flags` before the subcommand |
| Not deleting jobs after use | Server registry fills up | Use auto-cleanup (default) or call `delete` |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ERROR: Cannot connect to SPMF-Server` | Server not running | Start `spmf-server.jar` first |
| `ERROR [400]` on `run` | Wrong parameter type or count | Run `describe <name>` and check types |
| `ERROR [404]` on algorithm | Misspelled algorithm name | Run `list` to find the exact name |
| `UnicodeEncodeError` on Windows | Terminal codepage not UTF-8 | `chcp 65001` or set `PYTHONIOENCODING=utf-8` |
| Console output empty | Fetched after job was deleted | The client fetches console before result automatically |
| Job stuck in `PENDING` | Server at max concurrent jobs | Wait, or increase `spmf.max-concurrent-jobs` |
| Job `FAILED` | Bad input data or wrong params | Check console output — it contains the Java stack trace |

---

## License

SPMF is copyright © Philippe Fournier-Viger and is distributed under the
[GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).

SPMF-Server and spmf-client.py are released under the
[MIT License](LICENSE).
```
