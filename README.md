# SPMF-Server

A **REST API server** that exposes the [SPMF](https://www.philippe-fournier-viger.com/spmf/)
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
- [License](#license)

---

## Overview

[SPMF](https://www.philippe-fournier-viger.com/spmf/) is one of the largest open-source
data-mining libraries, providing over 230 algorithms for:

- Frequent itemset mining (Apriori, FP-Growth, …)
- Sequential pattern mining
- Association rule mining
- Clustering, classification, and more

**SPMF-Server** wraps the SPMF Java library in a lightweight HTTP server.
Clients submit a job (algorithm name + input data + parameters), the server
runs the algorithm, and the client retrieves the result and console output.

```
+----------------+        HTTP / JSON        +------------------+
|   Any Client   | ----------------------->  |   SPMF-Server    |
|  (curl, Python,| <-----------------------  |   (Java / HTTP)  |
|   GUI, …)      |    results, job status    +--------+---------+
+----------------+                                    |
                                                      v
                                             +------------------+
                                             |   SPMF Library   |
                                             |  230+ algorithms |
                                             +------------------+
```

---

## Requirements

- Java 11 or later
- The SPMF jar (`spmf.jar`) present in the server's working directory or classpath

---

## Configuration

The server is configured via `application.properties` (or environment variables):

| Property | Default | Description |
|---|---|---|
| `server.port` | `8585` | HTTP port the server listens on |
| `spmf.max-concurrent-jobs` | `4` | Maximum parallel algorithm executions |
| `spmf.job-timeout-seconds` | `300` | Kill a job if it runs longer than this |
| `spmf.max-job-registry-size` | `100` | Maximum jobs kept in memory |
| `spmf.api-key` | *(none)* | If set, all requests must send `X-API-Key: <value>` |

---

## Running the Server

```bash
java -jar spmf-server.jar
```

The server starts on `http://localhost:8585` by default.

To use a custom port:

```bash
java -jar spmf-server.jar --server.port=9090
```

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

Returns full server configuration as a flat key/value map.

---

### `GET /api/algorithms`

Returns all available algorithm names grouped by category.

<details>
<summary>Example response (truncated)</summary>

```json
{
  "count": 231,
  "algorithms": [
    { "name": "Apriori",          "algorithmCategory": "FREQUENT_ITEMSET_MINING" },
    { "name": "FPGrowth_itemsets","algorithmCategory": "FREQUENT_ITEMSET_MINING" },
    { "name": "PrefixSpan",       "algorithmCategory": "SEQUENTIAL_PATTERN_MINING" }
  ]
}
```

</details>

---

### `GET /api/algorithms/{algorithmName}`

Returns parameter definitions for one algorithm: name, type, example value,
and whether each parameter is mandatory or optional.

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

Submit a new mining job.

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
| `inputData` | Yes | Raw input text or base64-encoded string |
| `inputEncoding` | Yes | `"plain"` or `"base64"` |

**Response:** `202 Accepted`

```json
{
  "jobId":  "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status": "PENDING"
}
```

---

### `GET /api/jobs/{jobId}`

Poll the status of a submitted job.

| `status` value | Meaning |
|---|---|
| `PENDING` | Waiting in queue |
| `RUNNING` | Algorithm executing |
| `DONE` | Completed successfully |
| `FAILED` | Algorithm or server error |

<details>
<summary>Example response — DONE</summary>

```json
{
  "jobId":            "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "algorithmName":    "Apriori",
  "status":           "DONE",
  "submittedAt":      "2024-01-15T10:23:01Z",
  "executionTimeMs":  385
}
```

</details>

---

### `GET /api/jobs/{jobId}/result`

Fetch the algorithm's output text for a completed job.

<details>
<summary>Example response</summary>

```json
{
  "jobId":           "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "outputData":      "1 2 #SUP: 3\n2 3 #SUP: 2\n...",
  "executionTimeMs": 385
}
```

</details>

---

### `GET /api/jobs/{jobId}/console`

Fetch lines printed by the SPMF algorithm to stdout/stderr during execution.
Essential for debugging parameter errors or unexpected results.

> **Important:** Always fetch console output **before** calling `DELETE`.
> Deleting a job removes its working directory and the console log with it.

<details>
<summary>Example response</summary>

```json
{
  "jobId":         "5d0b27f6-f330-4cfb-9803-53f74c7bfa6a",
  "status":        "DONE",
  "lines":         12,
  "consoleOutput": "=== Apriori ===\nMinimum support: 50 %\n..."
}
```

</details>

---

### `GET /api/jobs`

Returns all jobs currently in the server registry.

---

### `DELETE /api/jobs/{jobId}`

Removes the job and its working files from the server.
Call this after retrieving all results to keep the server registry clean.

---

## Job Lifecycle

```
Client                              Server
  |                                   |
  |--- POST /api/run --------------->  |   Job created, returns jobId (202)
  |                                   |
  |--- GET  /api/jobs/{id} -------->  |   status: PENDING or RUNNING
  |--- GET  /api/jobs/{id} -------->  |   status: RUNNING
  |--- GET  /api/jobs/{id} -------->  |   status: DONE
  |                                   |
  |--- GET  /api/jobs/{id}/console ->  |   fetch console FIRST
  |--- GET  /api/jobs/{id}/result --->  |   then fetch result
  |                                   |
  |--- DELETE /api/jobs/{id} ------->  |   clean up (optional)
  |                                   |
```

> **Rule:** Always fetch **console** before **result** before **delete**.
> Once deleted, both outputs are gone from disk.

---

## Clients

A ready-to-use Python client package for SPMF-Server is available in a
separate repository:

**[spmf-server-pythonclient](https://github.com/your-org/spmf-server-pythonclient)**

It includes:

| File | Description |
|---|---|
| `spmf-client.py` | Full-featured command-line client for every API endpoint |
| `spmf-gui.py` | Graphical desktop client built with Python + tkinter |
| `RUNCLIENT.BAT` | Windows batch script demonstrating all CLI commands |
| `RUNCLIENTGUI.BAT` | Windows batch launcher for the GUI client |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Connection refused` on port 8585 | Server not started | Run `java -jar spmf-server.jar` |
| `HTTP 400` on job submission | Wrong parameter type or count | Check `GET /api/algorithms/{name}` for exact types |
| `HTTP 404` on algorithm name | Misspelled algorithm name | Check `GET /api/algorithms` for the exact name |
| `HTTP 403` on all requests | API key mismatch | Set `X-API-Key` header to match `spmf.api-key` |
| Job stuck in `PENDING` | Server at max concurrent jobs | Wait, or increase `spmf.max-concurrent-jobs` |
| Job `FAILED` | Bad input data or wrong params | Check `/api/jobs/{id}/console` for the Java error |
| Console output empty after delete | Console fetched after cleanup | Always fetch console before calling DELETE |

---

## License

SPMF is copyright © Philippe Fournier-Viger and is distributed under the
[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

SPMF-Server is released under the
[MIT License](LICENSE).


`spmf-client.py` and `spmf-gui.py` are released under the
[MIT License](LICENSE).
```
