package ca.pfv.spmf.server.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;

import ca.pfv.spmf.server.ServerMain;
import ca.pfv.spmf.server.job.Job;
import ca.pfv.spmf.server.job.JobStatus;

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

/**
 * A Swing panel that displays a live list of all jobs managed by the server,
 * showing their status, algorithm name, submission time, execution time,
 * and result or error information.
 *
 * <p>The panel refreshes automatically every {@value #REFRESH_INTERVAL_MS} ms
 * <em>only while the server is running</em> — when the server is stopped the
 * timer still fires but skips the data reload, so the table drains to empty
 * without wasted work.
 *
 * <p>Users can:
 * <ul>
 *   <li>Manually refresh the table.</li>
 *   <li>Double-click a row to open a non-modal detail dialog.</li>
 *   <li>View the algorithm output or error message.</li>
 *   <li>View the raw {@code console.log} produced by the child JVM.</li>
 *   <li>Copy a job ID to the clipboard.</li>
 *   <li>Delete individual jobs or all finished jobs at once.</li>
 * </ul>
 *
 * @author Philippe Fournier-Viger
 */
public class JobsPanel extends JPanel {

    // ── Colours (shared with ServerGui palette) ────────────────────────────
    private static final Color BG_DARK   = new Color(30,  30,  30);
    private static final Color BG_PANEL  = new Color(45,  45,  48);
    private static final Color BG_INPUT  = new Color(60,  60,  63);
    private static final Color FG_TEXT   = new Color(220, 220, 220);
    private static final Color FG_LABEL  = new Color(180, 180, 180);
    private static final Color ACCENT    = new Color( 70, 130, 200);

    /** Colour used to highlight PENDING jobs. */
    private static final Color CLR_PENDING = new Color(180, 160,  60);
    /** Colour used to highlight RUNNING jobs. */
    private static final Color CLR_RUNNING = new Color( 80, 180, 255);
    /** Colour used to highlight DONE jobs. */
    private static final Color CLR_DONE    = new Color( 80, 200, 100);
    /** Colour used to highlight FAILED jobs. */
    private static final Color CLR_FAILED  = new Color(220,  70,  70);

    /**
     * How often (ms) the panel polls {@link ServerMain#jobManager} for an
     * updated job list. The timer fires even when the server is stopped, but
     * {@link #doRefresh()} short-circuits immediately in that case so no
     * actual work is done.
     */
    private static final int REFRESH_INTERVAL_MS = 3_000;

    /** Formatter for submission / finish timestamps shown in the table. */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss")
                             .withZone(ZoneId.systemDefault());

    // ── Column indices ─────────────────────────────────────────────────────
    private static final int COL_JOB_ID    = 0;
    private static final int COL_ALGORITHM = 1;
    private static final int COL_STATUS    = 2;
    private static final int COL_SUBMITTED = 3;
    private static final int COL_DURATION  = 4;
    private static final int COL_INFO      = 5;
    private static final int NUM_COLS      = 6;

    // ── Swing components ───────────────────────────────────────────────────
    private final JobTableModel tableModel;
    private final JTable        table;
    private final JLabel        lblSummary;   // stored directly — no fragile search
    private final Timer         refreshTimer;

    // ── Constructor ────────────────────────────────────────────────────────

    /**
     * Build the jobs panel. The auto-refresh timer is started immediately
     * but will skip data reloads when the server is not running.
     */
    public JobsPanel() {
        super(new BorderLayout(0, 0));
        setBackground(BG_DARK);
        setBorder(new EmptyBorder(8, 8, 8, 8));

        add(buildHeader(), BorderLayout.NORTH);

        tableModel = new JobTableModel();
        table      = buildTable();
        JScrollPane sp = new JScrollPane(table);
        sp.setBackground(BG_DARK);
        sp.getViewport().setBackground(new Color(25, 25, 25));
        sp.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
        add(sp, BorderLayout.CENTER);

        // Build footer and keep a direct reference to the summary label.
        lblSummary = new JLabel("No jobs.");
        lblSummary.setForeground(FG_LABEL);
        lblSummary.setFont(new Font("SansSerif", Font.ITALIC, 11));
        add(buildFooter(lblSummary), BorderLayout.SOUTH);

        refreshTimer = new Timer(REFRESH_INTERVAL_MS, e -> refresh());
        refreshTimer.setCoalesce(true);
        refreshTimer.setInitialDelay(REFRESH_INTERVAL_MS);
        refreshTimer.start();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Stop the background refresh timer.
     * <p>
     * Call this when the parent window is closing to prevent the timer from
     * continuing to fire after the GUI has been disposed. Named
     * {@code stopRefreshTimer} rather than {@code dispose} to avoid
     * confusion with {@link javax.swing.JDialog#dispose()}.
     */
    public void stopRefreshTimer() {
        refreshTimer.stop();
    }

    /**
     * Immediately refresh the job table from the current server state.
     * Safe to call from any thread; delegates to the EDT if needed.
     */
    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        doRefresh();
    }

    // ── Private — UI construction ──────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(0, 0, 8, 0));

        JLabel title = new JLabel("Job Queue");
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        title.setForeground(ACCENT);
        p.add(title, BorderLayout.WEST);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);

        JButton btnRefresh = smallButton("⟳  Refresh");
        btnRefresh.addActionListener(e -> refresh());
        btns.add(btnRefresh);

        JButton btnDeleteAll = smallButton("Delete finished");
        btnDeleteAll.setForeground(new Color(220, 120, 120));
        btnDeleteAll.addActionListener(e -> deleteAllTerminal());
        btns.add(btnDeleteAll);

        p.add(btns, BorderLayout.EAST);
        return p;
    }

    private JTable buildTable() {
        JTable t = new JTable(tableModel);
        t.setBackground(new Color(25, 25, 25));
        t.setForeground(FG_TEXT);
        t.setGridColor(new Color(55, 55, 55));
        t.setFont(new Font("Monospaced", Font.PLAIN, 12));
        t.setRowHeight(22);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setSelectionBackground(new Color(50, 80, 120));
        t.setSelectionForeground(Color.WHITE);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);

        t.getTableHeader().setBackground(BG_PANEL);
        t.getTableHeader().setForeground(FG_LABEL);
        t.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        t.getTableHeader().setBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(70, 70, 70)));

        TableColumnModel cm = t.getColumnModel();
        cm.getColumn(COL_JOB_ID)   .setPreferredWidth(290);
        cm.getColumn(COL_ALGORITHM).setPreferredWidth(180);
        cm.getColumn(COL_STATUS)   .setPreferredWidth( 80);
        cm.getColumn(COL_SUBMITTED).setPreferredWidth( 75);
        cm.getColumn(COL_DURATION) .setPreferredWidth( 85);
        cm.getColumn(COL_INFO)     .setPreferredWidth(250);

        StatusCellRenderer renderer = new StatusCellRenderer();
        for (int i = 0; i < NUM_COLS; i++) {
            cm.getColumn(i).setCellRenderer(renderer);
        }

        // Double-click → open a non-modal detail dialog so the table stays
        // usable while the dialog is open.
        t.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = t.getSelectedRow();
                    if (row >= 0) openDetailDialog(tableModel.getJobAt(row));
                }
            }
        });

        return t;
    }

    /**
     * Build the footer bar.
     *
     * @param summaryLabel the pre-created summary label to embed
     * @return the footer panel
     */
    private JPanel buildFooter(JLabel summaryLabel) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(6, 0, 0, 0));

        p.add(summaryLabel, BorderLayout.WEST);

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actionBtns.setOpaque(false);

        JButton btnView = smallButton("View output");
        btnView.setToolTipText("Open a detail dialog for the selected job (or double-click the row)");
        btnView.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) openDetailDialog(tableModel.getJobAt(row));
            else hint("Select a job row first.");
        });

        JButton btnConsole = smallButton("Console log");
        btnConsole.setToolTipText("Show the raw stdout/stderr captured from the algorithm child process");
        btnConsole.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) openConsoleLog(tableModel.getJobAt(row));
            else hint("Select a job row first.");
        });

        JButton btnCopyId = smallButton("Copy ID");
        btnCopyId.setToolTipText("Copy the selected job's UUID to the clipboard");
        btnCopyId.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                String id = tableModel.getJobAt(row).getJobIdString();
                Toolkit.getDefaultToolkit()
                       .getSystemClipboard()
                       .setContents(new StringSelection(id), null);
                // No modal dialog — just show a transient message in the
                // summary label so the user isn't interrupted.
                lblSummary.setText("Job ID copied to clipboard: " + id);
            } else {
                hint("Select a job row first.");
            }
        });

        JButton btnDelete = smallButton("Delete");
        btnDelete.setForeground(new Color(220, 120, 120));
        btnDelete.setToolTipText("Remove the selected job from the server (does not cancel a running job)");
        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Job job = tableModel.getJobAt(row);
                if (ServerMain.jobManager != null) {
                    ServerMain.jobManager.deleteJob(job.getJobIdString());
                    refresh();
                }
            } else {
                hint("Select a job row first.");
            }
        });

        actionBtns.add(btnView);
        actionBtns.add(btnConsole);   // ← NEW button
        actionBtns.add(btnCopyId);
        actionBtns.add(btnDelete);
        p.add(actionBtns, BorderLayout.EAST);

        return p;
    }

    // ── Private — data refresh ─────────────────────────────────────────────

    /**
     * Pull fresh data from {@link ServerMain#jobManager} and push it to the
     * table model. Must be called on the EDT.
     * <p>
     * If the server is not running this method immediately clears the table
     * and returns, avoiding any wasted work from the timer.
     */
    private void doRefresh() {
        // Short-circuit when the server is stopped — clear and exit early
        if (!ServerMain.running || ServerMain.jobManager == null) {
            if (tableModel.getRowCount() > 0) {
                tableModel.setJobs(List.of());
                lblSummary.setText("Server is stopped.");
            }
            return;
        }

        List<Job> jobs = new ArrayList<>(ServerMain.jobManager.getAllJobs());

        // Sort: Running → Pending → Done → Failed, then newest-first within
        // each group so the most interesting rows float to the top.
        jobs.sort((a, b) -> {
            int pa = statusPriority(a.getStatus());
            int pb = statusPriority(b.getStatus());
            if (pa != pb) return Integer.compare(pa, pb);
            return b.getSubmittedAt().compareTo(a.getSubmittedAt());
        });

        tableModel.setJobs(jobs);
        updateSummary(jobs);
    }

    /** Lower number = shown first in the table. */
    private static int statusPriority(JobStatus s) {
        return switch (s) {
            case RUNNING -> 0;
            case PENDING -> 1;
            case DONE    -> 2;
            case FAILED  -> 3;
        };
    }

    /** Update the footer summary label from the current job list. */
    private void updateSummary(List<Job> jobs) {
        if (jobs.isEmpty()) {
            lblSummary.setText("No jobs.");
            return;
        }
        long pending = jobs.stream().filter(j -> j.getStatus() == JobStatus.PENDING).count();
        long running = jobs.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).count();
        long done    = jobs.stream().filter(j -> j.getStatus() == JobStatus.DONE   ).count();
        long failed  = jobs.stream().filter(j -> j.getStatus() == JobStatus.FAILED ).count();
        lblSummary.setText(String.format(
                "Total: %d   |   Running: %d   Pending: %d   Done: %d   Failed: %d",
                jobs.size(), running, pending, done, failed));
    }

    // ── Private — actions ──────────────────────────────────────────────────

    /**
     * Delete all terminal (DONE / FAILED) jobs from the job manager and
     * report how many were removed in the summary label.
     */
    private void deleteAllTerminal() {
        if (ServerMain.jobManager == null) return;
        List<Job> snapshot = new ArrayList<>(ServerMain.jobManager.getAllJobs());
        int deleted = 0;
        for (Job job : snapshot) {
            if (job.isTerminal()) {
                ServerMain.jobManager.deleteJob(job.getJobIdString());
                deleted++;
            }
        }
        refresh();
        if (deleted == 0) {
            lblSummary.setText("No finished jobs to delete.");
        } else {
            // Summary will be rebuilt by refresh(); set it here as a
            // confirmation that the deletion happened.
            lblSummary.setText(deleted + " finished job(s) deleted.");
        }
    }

    /**
     * Open a <em>non-modal</em> dialog showing full details for the given job,
     * including parameters, result data, or error message.
     *
     * <p>Using a non-modal dialog (instead of {@link JOptionPane}) keeps the
     * table interactive and lets the auto-refresh timer continue updating
     * the status while the dialog is open.
     *
     * @param job the job to display; must not be {@code null}
     */
    private void openDetailDialog(Job job) {
        // Take a consistent snapshot so the dialog shows a coherent view
        // even if the job transitions during display.
        Job.JobSnapshot snap = job.snapshot();

        StringBuilder sb = new StringBuilder();
        sb.append("Job ID      : ").append(job.getJobIdString()).append('\n');
        sb.append("Algorithm   : ").append(job.getAlgorithmName()).append('\n');
        sb.append("Status      : ").append(snap.status).append('\n');
        sb.append("Submitted   : ").append(fmtInstant(job.getSubmittedAt())).append('\n');
        sb.append("Started     : ").append(fmtInstant(snap.startedAt)).append('\n');
        sb.append("Finished    : ").append(fmtInstant(snap.finishedAt)).append('\n');
        sb.append("Duration    : ").append(fmtDuration(snap.executionTimeMs)).append('\n');
        sb.append("Work dir    : ").append(
                job.getWorkDirPath() != null ? job.getWorkDirPath() : "—").append('\n');
        sb.append("Parameters  : ").append(job.getParameters()).append('\n');
        sb.append('\n');

        switch (snap.status) {
            case DONE -> {
                sb.append("─── RESULT OUTPUT ──────────────────────────────────────────\n");
                String result = snap.resultData;
                sb.append(result != null && !result.isBlank() ? result : "(no output)");
            }
            case FAILED -> {
                sb.append("─── ERROR ──────────────────────────────────────────────────\n");
                sb.append(snap.errorMessage != null ? snap.errorMessage : "(no detail)");
            }
            default -> sb.append("(job has not finished yet — refresh to see updates)");
        }

        showTextDialog("Job Detail — " + job.getJobIdString(),
                       sb.toString(), 24, 70);
    }

    /**
     * Open a <em>non-modal</em> dialog showing the raw {@code console.log}
     * captured from the algorithm's child JVM process.
     *
     * <p>If the file does not exist yet (job still pending) or the work
     * directory is not set, an appropriate message is shown instead.
     *
     * @param job the job whose console log should be displayed
     */
    private void openConsoleLog(Job job) {
        String consolePath = job.getConsolePath();
        if (consolePath == null) {
            hint("Console log is not available yet (job has not started).");
            return;
        }

        Path path = Paths.get(consolePath);
        String content;
        if (!Files.exists(path)) {
            content = "(console.log not found — the job may not have started yet)\n"
                    + "Expected path: " + consolePath;
        } else {
            try {
                content = Files.readString(path);
                if (content.isBlank()) {
                    content = "(console.log exists but is empty)";
                }
            } catch (IOException e) {
                content = "Could not read console.log:\n" + e.getMessage();
            }
        }

        showTextDialog("Console Log — " + job.getJobIdString(), content, 28, 90);
    }

    /**
     * Create and display a non-modal, scrollable text dialog.
     *
     * @param title   dialog window title
     * @param content text to display
     * @param rows    preferred row count of the text area
     * @param cols    preferred column count of the text area
     */
    private void showTextDialog(String title, String content, int rows, int cols) {
        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this), title);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(BG_DARK);

        JTextArea ta = new JTextArea(content, rows, cols);
        ta.setEditable(false);
        ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
        ta.setBackground(new Color(22, 22, 22));
        ta.setForeground(FG_TEXT);
        ta.setCaretPosition(0);
        ta.setBorder(new EmptyBorder(6, 8, 6, 8));

        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60)));
        dialog.add(sp, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottom.setBackground(BG_DARK);

        JButton btnCopy = smallButton("Copy all");
        btnCopy.addActionListener(e ->
            Toolkit.getDefaultToolkit()
                   .getSystemClipboard()
                   .setContents(new StringSelection(ta.getText()), null));
        bottom.add(btnCopy);

        JButton btnClose = smallButton("Close");
        btnClose.addActionListener(e -> dialog.dispose());
        bottom.add(btnClose);

        dialog.add(bottom, BorderLayout.SOUTH);

        dialog.pack();
        // Constrain size so very long output doesn't create a window larger
        // than the screen.
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxW = (int)(screen.width  * 0.85);
        int maxH = (int)(screen.height * 0.85);
        Dimension pref = dialog.getSize();
        dialog.setSize(Math.min(pref.width, maxW), Math.min(pref.height, maxH));
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));

        // Non-modal — does NOT block the EDT or the refresh timer.
        dialog.setModal(false);
        dialog.setVisible(true);
    }

    /** Show a short informational message in a modal option pane. */
    private void hint(String message) {
        JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this),
                message, "Info", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Static helpers ─────────────────────────────────────────────────────

    static String fmtInstant(Instant instant) {
        return (instant == null) ? "—" : TS_FMT.format(instant);
    }

    static String fmtDuration(long ms) {
        if (ms <= 0) return "—";
        if (ms < 1_000) return ms + " ms";
        if (ms < 60_000) return String.format("%.2f s", ms / 1_000.0);
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1_000;
        return minutes + "m " + seconds + "s";
    }

    private static JButton smallButton(String text) {
        JButton b = new JButton(text);
        b.setBackground(BG_INPUT);
        b.setForeground(FG_TEXT);
        b.setFocusPainted(false);
        b.setFont(new Font("SansSerif", Font.PLAIN, 11));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 90, 90)),
                new EmptyBorder(2, 6, 2, 6)));
        return b;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INNER CLASS — Table Model
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@link AbstractTableModel} that backs the job table.
     * Holds a snapshot list of {@link Job} objects and exposes their
     * fields as columns.
     */
    private static final class JobTableModel extends AbstractTableModel {

        private static final String[] COLUMN_NAMES = {
            "Job ID", "Algorithm", "Status", "Submitted", "Duration", "Info"
        };

        /** Snapshot of jobs, replaced on each refresh. Must only be touched on EDT. */
        private List<Job> jobs = new ArrayList<>();

        /**
         * Replace the current job list and notify listeners.
         * Must be called on the EDT.
         *
         * @param newJobs the replacement list; must not be {@code null}
         */
        void setJobs(List<Job> newJobs) {
            this.jobs = new ArrayList<>(newJobs);
            fireTableDataChanged();
        }

        /**
         * Return the {@link Job} at the given view row index.
         *
         * @param row zero-based row index
         * @return the job; never {@code null}
         */
        Job getJobAt(int row) { return jobs.get(row); }

        @Override public int getRowCount()    { return jobs.size(); }
        @Override public int getColumnCount() { return NUM_COLS; }
        @Override public String getColumnName(int col) { return COLUMN_NAMES[col]; }
        @Override public boolean isCellEditable(int row, int col) { return false; }

        @Override
        public Object getValueAt(int row, int col) {
            Job job = jobs.get(row);
            return switch (col) {
                case COL_JOB_ID    -> job.getJobIdString();
                case COL_ALGORITHM -> job.getAlgorithmName();
                case COL_STATUS    -> job.getStatus().name();
                case COL_SUBMITTED -> fmtInstant(job.getSubmittedAt());
                case COL_DURATION  -> fmtDuration(job.getExecutionTimeMs());
                case COL_INFO      -> buildInfo(job);
                default            -> "";
            };
        }

        /** Build a concise one-line info string for the Info column. */
        private static String buildInfo(Job job) {
            return switch (job.getStatus()) {
                case PENDING -> "Waiting in queue";
                case RUNNING -> "Running…";
                case DONE -> {
                    String r = job.getResultData();
                    if (r == null || r.isBlank()) yield "Completed (no output)";
                    String preview = r.replace('\n', ' ').strip();
                    yield preview.length() > 60
                            ? preview.substring(0, 60) + "…"
                            : preview;
                }
                case FAILED -> {
                    String e = job.getErrorMessage();
                    if (e == null || e.isBlank()) yield "Failed (no detail)";
                    yield e.length() > 60 ? e.substring(0, 60) + "…" : e;
                }
            };
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INNER CLASS — Cell Renderer
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A {@link DefaultTableCellRenderer} that colour-codes the Status column
     * and applies alternating row backgrounds.
     */
    private final class StatusCellRenderer extends DefaultTableCellRenderer {

        private static final Color ROW_ALT  = new Color(32, 32, 32);
        private static final Color ROW_BASE = new Color(25, 25, 25);

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int col) {

            super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, col);

            setBorder(new EmptyBorder(1, 6, 1, 6));

            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
                return this;
            }

            setBackground(row % 2 == 0 ? ROW_BASE : ROW_ALT);
            setForeground(FG_TEXT);

            if (col == COL_STATUS && value instanceof String status) {
                setForeground(switch (status) {
                    case "PENDING" -> CLR_PENDING;
                    case "RUNNING" -> CLR_RUNNING;
                    case "DONE"    -> CLR_DONE;
                    case "FAILED"  -> CLR_FAILED;
                    default        -> FG_TEXT;
                });
                setFont(getFont().deriveFont(Font.BOLD));
            } else {
                setFont(table.getFont());
            }

            return this;
        }
    }
}