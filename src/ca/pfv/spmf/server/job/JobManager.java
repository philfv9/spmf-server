package ca.pfv.spmf.server.job;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.spmfexecutor.SpmfCatalogue;
import ca.pfv.spmf.server.spmfexecutor.SpmfProcessExecutor;
import ca.pfv.spmf.server.util.FileUtil;
import ca.pfv.spmf.server.util.ServerLogger;
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
public final class JobManager {

	private static final Logger log = ServerLogger.get(JobManager.class);

	private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
	private final ThreadPoolExecutor executor;
	private final ServerConfig config;
	private final SpmfCatalogue catalogue;

	// ── Dedicated ThreadGroup for algorithm threads ────────────────────────
	// Grouping all algorithm threads lets us monitor them as a unit and
	// makes heap-dump analysis easier (all "spmf-algo-*" threads are in
	// one group). It does NOT prevent OOM propagation — that is handled
	// by the uncaught-exception handler set on each thread below.
	private static final ThreadGroup ALGO_GROUP = new ThreadGroup("spmf-algorithms") {
		/**
		 * Override uncaughtException so that an OOM (or any Error) thrown inside an
		 * algorithm thread is caught HERE and never propagates to the worker thread
		 * that called algoThread.join(). The worker thread therefore stays alive and
		 * can run more jobs.
		 */
		@Override
		public void uncaughtException(Thread t, Throwable e) {
			// Let the per-thread handler deal with result/failure
			// reporting. This override just swallows the re-throw
			// that the default ThreadGroup.uncaughtException() would
			// otherwise do, which would propagate to the parent group
			// and potentially kill the JVM.
			System.err.println("[SPMF-Server] Uncaught throwable on " + "algorithm thread '" + t.getName() + "': " + e);
		}
	};

	public JobManager(ServerConfig config, SpmfCatalogue catalogue) {
		this.config = config;
		this.catalogue = catalogue;

		BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(config.getMaxQueueSize());

		this.executor = new ThreadPoolExecutor(config.getCoreThreads(), config.getMaxThreads(), 60L, TimeUnit.SECONDS,
				queue, r -> {
					Thread t = new Thread(r);
					t.setName("spmf-worker-" + t.getId());
					t.setDaemon(true);
					return t;
				}, new ThreadPoolExecutor.AbortPolicy());

		log.info("JobManager started (core=" + config.getCoreThreads() + ", max=" + config.getMaxThreads() + ")");
	}

	// ── submit ─────────────────────────────────────────────────────────────

	public Job submit(String algorithmName, List<String> parameters, String inputData) {

		Job job = new Job(algorithmName, parameters, inputData);
		jobs.put(job.getJobId(), job);
		log.info("Job submitted: " + job.getJobIdString() + "  algo=" + algorithmName);

		executor.submit(() -> {
			job.markRunning();
			log.info("Job running: " + job.getJobIdString());

			try {
				// ── Use process-based executor instead of in-process ───────
				SpmfProcessExecutor exec = new SpmfProcessExecutor(config, catalogue);
				job.markDone(exec.execute(job));
				log.info("Job done: " + job.getJobIdString() + "  time=" + job.getExecutionTimeMs() + " ms");
			} catch (Exception e) {
				String msg = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
				job.markFailed(msg);
				log.severe("Job failed: " + job.getJobIdString() + "  " + msg);
			}
		});

		return job;
	}

	// ── Error message builder ──────────────────────────────────────────────

	/**
	 * Converts any Throwable into a human-readable API error message. Gives
	 * specific guidance for the most common JVM-level failures.
	 */
	private static String buildErrorMessage(Throwable t) {
		if (t instanceof OutOfMemoryError) {
			return "Algorithm ran out of memory (OutOfMemoryError). " + "Increase JVM heap with -Xmx (e.g. -Xmx2g) or "
					+ "reduce the input size.";
		}
		if (t instanceof StackOverflowError) {
			return "Algorithm caused a stack overflow (StackOverflowError). "
					+ "The input may be too large or deeply recursive.";
		}
		if (t instanceof ThreadDeath) {
			return "Algorithm thread was forcibly stopped.";
		}
		String msg = t.getMessage();
		return (msg != null && !msg.isBlank()) ? t.getClass().getSimpleName() + ": " + msg : t.getClass().getName();
	}

	// ── Standard methods (unchanged from your original) ───────────────────

	public Job getJob(String jobIdStr) {
		try {
			return jobs.get(UUID.fromString(jobIdStr));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public boolean deleteJob(String jobIdStr) {
		try {
			UUID uuid = UUID.fromString(jobIdStr);
			Job job = jobs.remove(uuid);
			if (job == null)
				return false;
			if (job.getWorkDirPath() != null)
				FileUtil.deleteDirectory(job.getWorkDirPath());
			log.info("Job deleted: " + jobIdStr);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	public Collection<Job> getAllJobs() {
		return new ArrayList<>(jobs.values());
	}

	public void shutdown() {
		log.info("Shutting down executor...");
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS))
				executor.shutdownNow();
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	public int activeJobCount() {
		return executor.getActiveCount();
	}

	public int queuedJobCount() {
		return executor.getQueue().size();
	}

	public int totalJobCount() {
		return jobs.size();
	}
}