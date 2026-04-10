package ca.pfv.spmf.server.job;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 *
 * This file is part of the SPMF SERVER
 * (http://www.philippe-fournier-viger.com/spmf).
 *
 * SPMF is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SPMF is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF. If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Enumeration of the possible lifecycle states of a {@link Job}.
 * <p>
 * A job always starts in {@link #PENDING} and transitions in one direction
 * only — it can never move backwards:
 * <pre>
 *   PENDING ──► RUNNING ──► DONE
 *                       └──► FAILED
 * </pre>
 * Once a job reaches {@link #DONE} or {@link #FAILED} it is considered
 * <em>terminal</em> (see {@link Job#isTerminal()}) and will eventually be
 * purged by the {@link JobCleaner} after the configured TTL expires.
 *
 * @author Philippe Fournier-Viger
 * @see Job
 * @see JobManager
 * @see JobCleaner
 */
public enum JobStatus {

    /**
     * The job has been accepted and is waiting in the execution queue.
     * No worker thread has picked it up yet.
     */
    PENDING,

    /**
     * A worker thread is actively executing the algorithm in a child JVM
     * process.  The job will transition to {@link #DONE} or {@link #FAILED}
     * when the process finishes.
     */
    RUNNING,

    /**
     * The algorithm completed successfully.  The result data is available via
     * {@link Job#getResultData()}.
     */
    DONE,

    /**
     * The algorithm failed, was killed by a timeout, or the child JVM process
     * exited with a non-zero code.  A description of the failure is available
     * via {@link Job#getErrorMessage()}.
     */
    FAILED
}