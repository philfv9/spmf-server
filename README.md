
# SPMF-Server & Python Client

A **REST API server** that exposes the [SPMF](https://www.philippe-fournier-viger.com/spmf/)
data-mining library over HTTP, plus a **command-line Python client** and a
**graphical desktop client** that talk to it.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [SPMF-Server](#spmf-server)
  - [Requirements](#server-requirements)
  - [Configuration](#configuration)
  - [Running the Server](#running-the-server)
  - [REST API Reference](#rest-api-reference)
- [Python CLI Client](#python-cli-client)
  - [Requirements](#client-requirements)
  - [Installation](#installation)
  - [Global Options](#global-options)
  - [Commands](#commands)
  - [Examples](#examples)
- [Graphical GUI Client](#graphical-gui-client)
  - [GUI Requirements](#gui-requirements)
  - [Launching the GUI](#launching-the-gui)
  - [GUI Features](#gui-features)
  - [GUI Tabs](#gui-tabs)
- [Batch Launchers](#batch-launchers)
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

**spmf-gui.py** is a full graphical desktop client built with `tkinter` that provides
the same capabilities through a modern dark-themed interface — no command line required.

```
+------------------+        HTTP / JSON        +------------------+
|  spmf-client.py  | ----------------------->  |                  |
|  (Python CLI)    | <-----------------------  |   SPMF-Server    |
+------------------+    results, job status    |   (Java / HTTP)  |
                                               |                  |
+------------------+        HTTP / JSON        |                  |
|  spmf-gui.py     | ----------------------->  |                  |
|  (Python GUI)    | <-----------------------  +--------+---------+
+------------------+    results, job status             |
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
| **spmf-client.py** | Python 3 | Full-featured CLI wrapper for every REST endpoint |
| **spmf-gui.py** | Python 3 + tkinter | Graphical desktop client for the same REST API |
| **RUNCLIENT.BAT** | Windows Batch | Demonstration script running all CLI commands |
| **RUNCLIENTGUI.BAT** | Windows Batch | Launcher script for the GUI client |

Jobs are **asynchronous**:

1. Client `POST /api/run` → server returns a **Job ID** immediately (`202 Accepted`)
2. Client polls `GET /api/jobs/{id}` until status is `DONE` or `FAILED`
3. Client fetches `GET /api/jobs/{id}/result` and `GET /api/jobs/{id}/console`
4. Client optionally `DELETE /api/jobs/{id}` to free server memory

> **Important:** Console output is always fetched **before** the result and before
> cleanup. Deleting a job removes its working directory from disk, taking the console
> log with it.

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

## Python CLI Client

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
2. POST /api/run  ->  receive jobId
3. Poll GET /api/jobs/{id}  until DONE or FAILED
4. GET /api/jobs/{id}/console   (fetched BEFORE result and delete)
5. GET /api/jobs/{id}/result
6. Print result output
7. Print console output
8. DELETE /api/jobs/{id}        (unless --no-cleanup)
```

---

## Graphical GUI Client

`spmf-gui.py` is a modern dark-themed desktop application that provides the same
capabilities as the CLI client — without needing to use the command line at all.

```
+---------------------------------------------------------------+
|  SPMF Server Client                                  v1.0.0   |
|  Host: [localhost]  Port: [8585]  API Key: [      ] [Connect] |
+---------------------------------------------------------------+
|  Dashboard  |  Algorithms  |  Run Job  |  Jobs  |  Result    |
+---------------------------------------------------------------+
|                                                               |
|   [ Server Health card ]    [ Server Config card ]           |
|                                                               |
|   [ Activity Log                                          ]   |
|                                                               |
+---------------------------------------------------------------+
```

### GUI Requirements

- Python 3.8 or later
- `tkinter` — included with the standard Python installer from
  [python.org](https://www.python.org/downloads/)
- `requests` library

```bash
pip install requests
```

> **Note:** If you installed Python from the **Microsoft Store**, `tkinter` may be
> missing. Reinstall Python from [python.org](https://www.python.org/downloads/) to
> get `tkinter` included.

### Launching the GUI

**Windows — recommended (uses the batch launcher):**

```
RUNCLIENTGUI.BAT
```

The launcher will:
1. Verify Python is installed and on `PATH`
2. Check that `pip` is available
3. Auto-install `requests` if missing
4. Verify `tkinter` is available and give a helpful error if not
5. Check that `spmf-gui.py` exists in the same folder
6. Launch the GUI using `pythonw.exe` (no background console window)
   or fall back to `python.exe` if `pythonw` is not found

**Direct launch:**

```bash
python spmf-gui.py
```

---

### GUI Features

| Feature | Description |
|---|---|
| **Auto-connect** | Attempts to connect to `localhost:8585` automatically on startup |
| **Live status dot** | Green/red indicator in the header shows connection state at a glance |
| **Algorithm search** | Filter 230+ algorithms by name or category in real time |
| **Parameter guide** | Selecting an algorithm shows its full parameter list with types and examples |
| **One-click run** | Browse for input file, enter parameters, click Submit — the GUI polls and waits |
| **Side-by-side output** | Result output and console output displayed in two panels simultaneously |
| **Save outputs** | Save result or console output to any file with a standard save dialog |
| **Job manager** | View all server jobs, delete individual jobs, or load any job's result |
| **Color-coded jobs** | DONE = green, FAILED = red, RUNNING = amber, QUEUED = grey |
| **Activity log** | Timestamped log of all actions on the Dashboard tab |
| **About dialog** | Version info, author, license summary |
| **Background threads** | All network calls run in background threads — the UI never freezes |

---

### GUI Tabs

#### Dashboard
Server health stats, full server configuration, and a live activity log showing
every action taken during the session.

#### Algorithms
Browse and search all 230+ SPMF algorithms. Click any algorithm to see its full
description: category, author, input/output types, and every parameter with its
type and an example value. Use **"Use in Run Job"** to load the selected algorithm
directly into the Run Job tab.

#### Run Job
The main job submission panel:

- Select algorithm from a dropdown (all 230+ loaded automatically)
- Browse for an input data file
- Enter space-separated parameters (the Parameter Guide on the right shows
  exactly what each algorithm expects)
- Optional: base64-encode input, keep job after completion, adjust poll
  interval and timeout
- Click **Submit Job** — progress is shown live, then the Result tab opens
  automatically when complete

#### Jobs
Live list of all jobs in the server registry with status colour coding.
Double-click any row to load its result and console output into the Result tab.
Delete individual jobs from this tab.

#### Result
Side-by-side view of:

- **Left panel** — algorithm result output (the patterns / rules / clusters found)
- **Right panel** — console output (stdout/stderr from the SPMF Java process,
  shown in green monospace text)

Both panels support horizontal scrolling for wide output. Either panel can be
saved to a file independently.

---

## Batch Launchers

### RUNCLIENT.BAT — CLI demo script

Runs 13 CLI examples in sequence and displays the full output of each command
inside a bordered box so script output and batch labels are clearly separated.

```batch
RUNCLIENT.BAT
```

Examples covered:

| # | What it demonstrates |
|---|---|
| 1 | Server health check |
| 2 | Server info / configuration |
| 3 | List all algorithms |
| 4 | Describe Apriori |
| 5 | List all jobs |
| 6 | Run Apriori (1 mandatory param) |
| 7 | Run Apriori with base64 input encoding |
| 8 | Run Apriori with `--no-cleanup` |
| 9 | Health check with raw JSON output |
| 10 | Save algorithm list to file |
| 11 | Run FPGrowth_itemsets |
| 12 | Describe FPGrowth_itemsets |
| 13 | Run Apriori with optional integer max-length param |

Edit the top of `RUNCLIENT.BAT` to change the server address:

```batch
set HOST=localhost
set PORT=8585
```

---

### RUNCLIENTGUI.BAT — GUI launcher

Performs environment checks before launching the GUI:

```
RUNCLIENTGUI.BAT
```

Check sequence:

```
[OK] Python found: Python 3.12.0
[OK] requests is already installed.
[OK] tkinter is available.
[OK] spmf-gui.py found.

========================================
 Launching SPMF GUI...
========================================
```

If any check fails the script prints a clear error message and a fix suggestion
before exiting — it will never silently open a broken GUI.

Both batch files must be placed in the **same folder** as `spmf-client.py` and
`spmf-gui.py`. Both require Python to be on the system `PATH`.

---

## Algorithm Parameters

> Getting parameters wrong is the most common source of `400 Bad Request` errors.

### Rules

- Always run `describe <algorithmName>` (CLI) or select the algorithm in the GUI
  **Algorithms** tab first to see exact parameter types.
- Pass parameters in the **exact order** shown by `describe`.
- Pass **only mandatory parameters** unless you intentionally want optional ones.
- Pass integer parameters as integers (`3`, not `3.0`).
- Pass double parameters as decimals (`0.5`, not `1/2`).

### Common parameter mistakes

| Mistake | Result | Fix |
|---|---|---|
| Passing `0.6` for an `Integer` param | `400 Bad Request` | Use `1`, `2`, `3`, … |
| Using algorithm name `FPGrowth` | `404 Not Found` | Use exact name `FPGrowth_itemsets` |
| Putting `--base64` after the subcommand (CLI) | Flag ignored / parse error | Move all `--flags` before the subcommand |
| Not deleting jobs after use | Server registry fills up | Use auto-cleanup (default) or call `delete` |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `ERROR: Cannot connect to SPMF-Server` | Server not running | Start `spmf-server.jar` first |
| `ERROR [400]` on `run` | Wrong parameter type or count | Run `describe <name>` and check types |
| `ERROR [404]` on algorithm | Misspelled algorithm name | Run `list` to find the exact name |
| `UnicodeEncodeError` on Windows (CLI) | Terminal codepage not UTF-8 | `chcp 65001` or set `PYTHONIOENCODING=utf-8` |
| Console output empty | Fetched after job was deleted | Both clients fetch console before result automatically |
| Job stuck in `PENDING` | Server at max concurrent jobs | Wait, or increase `spmf.max-concurrent-jobs` |
| Job `FAILED` | Bad input data or wrong params | Check console output — it contains the Java stack trace |
| GUI won't launch — `tkinter` missing | Python installed from Microsoft Store | Reinstall Python from [python.org](https://www.python.org/downloads/) |
| GUI connection dot stays red | Server not reachable | Check host/port fields and confirm the server is running |
| GUI freezes during job | Should not happen — all network calls are threaded | Report as a bug if it does |

---

## License

SPMF is copyright © Philippe Fournier-Viger and is distributed under the
[GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).

SPMF-Server and the Python clients (`spmf-client.py`, `spmf-gui.py`) are released
under the [MIT License](LICENSE).
