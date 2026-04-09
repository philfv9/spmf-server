package ca.pfv.spmf.server.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
/*
 *  Copyright (c) 2026 Philippe Fournier-Viger
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF.  If not, see <http://www.gnu.org/licenses/>.
 */
public final class Job {

    private final UUID         jobId;
    private final String       algorithmName;
    private final List<String> parameters;
    private final String       inputData;
    private final Instant      submittedAt;

    private volatile JobStatus status;
    private volatile Instant   startedAt;
    private volatile Instant   finishedAt;
    private volatile long      executionTimeMs;
    private volatile String    resultData;
    private volatile String    errorMessage;
    private volatile String    workDirPath;

    public Job(String algorithmName, List<String> parameters, String inputData) {
        this.jobId         = UUID.randomUUID();
        this.algorithmName = algorithmName;
        this.parameters    = List.copyOf(parameters);
        this.inputData     = inputData;
        this.submittedAt   = Instant.now();
        this.status        = JobStatus.PENDING;
    }

    public void markRunning() {
        this.status    = JobStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markDone(String resultData) {
        this.resultData      = resultData;
        this.finishedAt      = Instant.now();
        this.executionTimeMs = finishedAt.toEpochMilli() -
                               startedAt.toEpochMilli();
        this.status          = JobStatus.DONE;
    }

    public void markFailed(String errorMessage) {
        this.errorMessage    = errorMessage;
        this.finishedAt      = Instant.now();
        this.executionTimeMs = (startedAt != null)
                ? finishedAt.toEpochMilli() - startedAt.toEpochMilli() : 0L;
        this.status          = JobStatus.FAILED;
    }

    public UUID         getJobId()           { return jobId; }
    public String       getJobIdString()     { return jobId.toString(); }
    public String       getAlgorithmName()   { return algorithmName; }
    public List<String> getParameters()      { return parameters; }
    public String       getInputData()       { return inputData; }
    public Instant      getSubmittedAt()     { return submittedAt; }
    public JobStatus    getStatus()          { return status; }
    public Instant      getStartedAt()       { return startedAt; }
    public Instant      getFinishedAt()      { return finishedAt; }
    public long         getExecutionTimeMs() { return executionTimeMs; }
    public String       getResultData()      { return resultData; }
    public String       getErrorMessage()    { return errorMessage; }
    public String       getWorkDirPath()     { return workDirPath; }
    public void         setWorkDirPath(String p) { this.workDirPath = p; }

    /**
     * Returns the path to the console.log file for this job.
     * May not exist if the job hasn't started yet.
     */
    public String getConsolePath() {
        if (workDirPath == null) return null;
        return workDirPath + java.io.File.separator + "console.log";
    }

    public boolean isTerminal() {
        return status == JobStatus.DONE || status == JobStatus.FAILED;
    }
}