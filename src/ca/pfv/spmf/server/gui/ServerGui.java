package ca.pfv.spmf.server.gui;

import ca.pfv.spmf.server.ServerConfig;
import ca.pfv.spmf.server.ServerMain;
import ca.pfv.spmf.server.util.ServerLogger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;
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
 * Swing GUI for SPMF-Server.
 *
 * Features:
 *  - Configuration panel (port, threads, work dir, API key, log level)
 *  - Start / Stop buttons
 *  - Live log console (JTextPane with colour-coded levels)
 *  - Status bar (running indicator, algorithm count, active jobs)
 *  - Browse buttons for config file and work directory
 *  - "Open work dir" button
 *  - About dialog (author, license, version)
 *  - Graceful shutdown on window close
 */
public class ServerGui {

    // ── Colours ────────────────────────────────────────────────────────────
    private static final Color BG_DARK     = new Color(30,  30,  30);
    private static final Color BG_PANEL    = new Color(45,  45,  48);
    private static final Color BG_INPUT    = new Color(60,  60,  63);
    private static final Color FG_TEXT     = new Color(220, 220, 220);
    private static final Color FG_LABEL    = new Color(180, 180, 180);
    private static final Color CLR_INFO    = new Color(100, 200, 100);
    private static final Color CLR_WARN    = new Color(255, 200,  60);
    private static final Color CLR_ERROR   = new Color(255,  80,  80);
    private static final Color CLR_DEBUG   = new Color(120, 180, 255);
    private static final Color CLR_TIME    = new Color(140, 140, 140);
    private static final Color GREEN_LED   = new Color( 50, 200,  80);
    private static final Color RED_LED     = new Color(200,  50,  50);
    private static final Color ACCENT      = new Color( 70, 130, 200);

    // ── Version / product constants ────────────────────────────────────────
    private static final String APP_VERSION  = "1.0.0";
    private static final String APP_NAME     = "SPMF-Server";
    private static final String AUTHOR       = "Philippe Fournier-Viger";
    private static final String WEBSITE      = "https://www.philippe-fournier-viger.com/spmf/";
    private static final String LICENSE_NAME = "GNU General Public License v3.0  (GPLv3)";
    private static final String LICENSE_URL  = "https://www.gnu.org/licenses/gpl-3.0.html";
    private static final String COPYRIGHT    = "\u00A9 Philippe Fournier-Viger. All rights reserved.";
    private static final String DESCRIPTION  =
            "SPMF-Server exposes the SPMF data-mining library as an HTTP\n" +
            "REST API, allowing clients to submit pattern-mining jobs,\n" +
            "poll their status, and retrieve results programmatically\n" +
            "or through a graphical client.";
    private static final String GPLv3_SUMMARY =
            "This program is free software: you can redistribute it and/or\n" +
            "modify it under the terms of the GNU General Public License as\n" +
            "published by the Free Software Foundation, either version 3 of\n" +
            "the License, or (at your option) any later version.\n\n" +
            "This program is distributed in the hope that it will be useful,\n" +
            "but WITHOUT ANY WARRANTY; without even the implied warranty of\n" +
            "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the\n" +
            "GNU General Public License for more details.\n\n" +
            "You should have received a copy of the GNU General Public\n" +
            "License along with this program.  If not, see:\n" +
            LICENSE_URL;

    // ── Timer interval ─────────────────────────────────────────────────────
    // FIX: was 1 000 ms — posting a Swing event every second caused the
    //      TimerQueue to accumulate events faster than the EDT could drain
    //      them, eventually exhausting heap.  5 000 ms is more than enough
    //      for a status bar that shows rough counts.
    private static final int STATUS_TIMER_INTERVAL_MS = 5_000;

    // ── Log size cap ───────────────────────────────────────────────────────
    // FIX: keep the in-memory log document small so it cannot grow without
    //      bound and contribute to heap pressure.
    private static final int LOG_MAX_CHARS = 100_000;   // ~3 000 lines

    // ── State ──────────────────────────────────────────────────────────────
    private final String      initialConfigPath;
    private ServerConfig      loadedConfig;

    // ── Swing components ───────────────────────────────────────────────────
    private JFrame            frame;
    private JTextField        tfPort;
    private JTextField        tfHost;
    private JTextField        tfCoreThreads;
    private JTextField        tfMaxThreads;
    private JTextField        tfWorkDir;
    private JTextField        tfApiKey;
    private JTextField        tfTtl;
    private JTextField        tfMaxQueueSize;
    private JTextField        tfMaxInputMb;
    private JComboBox<String> cbLogLevel;
    private JTextField        tfConfigFile;

    private JButton           btnStart;
    private JButton           btnStop;
    private JButton           btnOpenWorkDir;
    private JButton           btnClearLog;

    private JLabel            lblStatus;
    private JLabel            ledIndicator;
    private JLabel            lblAlgoCount;
    private JLabel            lblActiveJobs;

    private JTextPane         logPane;
    private StyledDocument    logDoc;

    private Timer             statusTimer;

    // ── Constructor ────────────────────────────────────────────────────────

    public ServerGui(String configPath) {
        this.initialConfigPath = configPath;
    }

    /** Build and display the GUI on the EDT. */
    public void show() {
        buildFrame();
        installLogHandler();
        loadConfigIntoFields(initialConfigPath);
        frame.setVisible(true);
        appendLog("SPMF-Server GUI ready. Configure and click Start.", Level.INFO);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FRAME CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════════

    private void buildFrame() {
        frame = new JFrame(APP_NAME + " v" + APP_VERSION);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });

        frame.setPreferredSize(new Dimension(950, 750));
        frame.getContentPane().setBackground(BG_DARK);
        frame.setLayout(new BorderLayout(0, 0));

        frame.setJMenuBar(buildMenuBar());
        frame.add(buildNorthBar(),    BorderLayout.NORTH);
        frame.add(buildCentrePanel(), BorderLayout.CENTER);
        frame.add(buildStatusBar(),   BorderLayout.SOUTH);

        frame.pack();
        frame.setLocationRelativeTo(null);

        // ── FIX: use a longer interval and guard the callback against OOM ──
        statusTimer = new Timer(STATUS_TIMER_INTERVAL_MS, e -> {
            try {
                refreshStatusBar();
            } catch (OutOfMemoryError oom) {
                // Stop the timer immediately so it stops posting new events.
                // Log to stderr because the logging system may also be OOM.
                statusTimer.stop();
                System.err.println("[SPMF-Server] OOM in status timer — " +
                                   "timer stopped. Restart the server with " +
                                   "more heap (-Xmx).");
            }
        });
        // FIX: do not coalesce — if the EDT falls behind, old events are
        //      dropped rather than piling up in the TimerQueue.
        statusTimer.setCoalesce(true);   // default is true, stated explicitly
        statusTimer.setInitialDelay(STATUS_TIMER_INTERVAL_MS);
        statusTimer.start();
    }

    // ── Menu bar ───────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(BG_PANEL);
        menuBar.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, new Color(60, 60, 60)));

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setForeground(FG_TEXT);
        helpMenu.setFont(new Font("SansSerif", Font.PLAIN, 12));

        JMenuItem aboutItem = new JMenuItem("About " + APP_NAME + "…");
        aboutItem.setBackground(BG_PANEL);
        aboutItem.setForeground(FG_TEXT);
        aboutItem.setFont(new Font("SansSerif", Font.PLAIN, 12));
        aboutItem.addActionListener(e -> showAboutDialog());

        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);
        return menuBar;
    }

    // ── North bar ──────────────────────────────────────────────────────────

    private JPanel buildNorthBar() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(BG_PANEL);
        p.setBorder(new EmptyBorder(10, 14, 10, 14));

        JPanel leftBlock = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftBlock.setOpaque(false);

        JLabel title = new JLabel(APP_NAME);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setForeground(ACCENT);
        leftBlock.add(title);

        JLabel versionLbl = new JLabel("v" + APP_VERSION);
        versionLbl.setFont(new Font("SansSerif", Font.PLAIN, 11));
        versionLbl.setForeground(new Color(120, 120, 120));
        leftBlock.add(versionLbl);

        JButton btnAbout = smallButton("ℹ  About");
        btnAbout.setForeground(new Color(150, 180, 220));
        btnAbout.addActionListener(e -> showAboutDialog());
        leftBlock.add(Box.createHorizontalStrut(8));
        leftBlock.add(btnAbout);

        p.add(leftBlock, BorderLayout.WEST);

        JPanel cfgRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        cfgRow.setOpaque(false);
        cfgRow.add(label("Config file:"));
        tfConfigFile = styledTextField(30);
        tfConfigFile.setText(initialConfigPath);
        cfgRow.add(tfConfigFile);

        JButton btnBrowseCfg = smallButton("Browse…");
        btnBrowseCfg.addActionListener(e -> browseConfigFile());
        cfgRow.add(btnBrowseCfg);

        JButton btnReload = smallButton("Reload");
        btnReload.addActionListener(
                e -> loadConfigIntoFields(tfConfigFile.getText().trim()));
        cfgRow.add(btnReload);

        p.add(cfgRow, BorderLayout.EAST);
        return p;
    }

    // ── Centre panel ───────────────────────────────────────────────────────

    private JSplitPane buildCentrePanel() {
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                buildConfigPanel(),
                buildLogPanel());
        split.setDividerLocation(340);
        split.setResizeWeight(0.0);
        split.setBackground(BG_DARK);
        split.setBorder(null);
        split.setDividerSize(5);
        return split;
    }

    // ── Left config panel ──────────────────────────────────────────────────

    private JScrollPane buildConfigPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(8, 8, 8, 4));

        p.add(buildNetworkSection());
        p.add(Box.createVerticalStrut(8));
        p.add(buildThreadSection());
        p.add(Box.createVerticalStrut(8));
        p.add(buildJobSection());
        p.add(Box.createVerticalStrut(8));
        p.add(buildSecuritySection());
        p.add(Box.createVerticalStrut(8));
        p.add(buildLoggingSection());
        p.add(Box.createVerticalStrut(12));
        p.add(buildButtonPanel());
        p.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(p);
        sp.setBackground(BG_DARK);
        sp.getViewport().setBackground(BG_DARK);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    private JPanel buildNetworkSection() {
        JPanel p = titledSection("Network");
        tfHost = styledTextField(15);
        tfPort = styledTextField(7);
        addRow(p, "Host:", tfHost);
        addRow(p, "Port:", tfPort);
        return p;
    }

    private JPanel buildThreadSection() {
        JPanel p = titledSection("Thread Pool");
        tfCoreThreads = styledTextField(5);
        tfMaxThreads  = styledTextField(5);
        addRow(p, "Core threads:", tfCoreThreads);
        addRow(p, "Max threads:",  tfMaxThreads);
        return p;
    }

    private JPanel buildJobSection() {
        JPanel p = titledSection("Jobs & Files");
        tfTtl          = styledTextField(5);
        tfMaxQueueSize = styledTextField(5);
        tfMaxInputMb   = styledTextField(5);
        tfWorkDir      = styledTextField(14);

        addRow(p, "Job TTL (min):",  tfTtl);
        addRow(p, "Max queue size:", tfMaxQueueSize);
        addRow(p, "Max input (MB):", tfMaxInputMb);

        JPanel wdRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        wdRow.setOpaque(false);
        wdRow.add(tfWorkDir);
        JButton btnBrowseWd = smallButton("…");
        btnBrowseWd.addActionListener(e -> browseWorkDir());
        wdRow.add(Box.createHorizontalStrut(4));
        wdRow.add(btnBrowseWd);
        addRowRaw(p, "Work dir:", wdRow);
        return p;
    }

    private JPanel buildSecuritySection() {
        JPanel p = titledSection("Security");
        tfApiKey = styledTextField(20);
        tfApiKey.setToolTipText("Leave blank to disable API key authentication");
        addRow(p, "API key:", tfApiKey);
        return p;
    }

    private JPanel buildLoggingSection() {
        JPanel p = titledSection("Logging");
        String[] levels = {"OFF","SEVERE","WARNING","INFO","FINE","FINER","FINEST","ALL"};
        cbLogLevel = new JComboBox<>(levels);
        styleCombo(cbLogLevel);
        addRowRaw(p, "Log level:", cbLogLevel);
        return p;
    }

    private JPanel buildButtonPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        p.setBackground(BG_DARK);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        btnStart = new JButton("▶  Start");
        btnStart.setBackground(new Color(40, 140, 60));
        btnStart.setForeground(Color.WHITE);
        btnStart.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnStart.setFocusPainted(false);
        btnStart.addActionListener(e -> onStart());

        btnStop = new JButton("■  Stop");
        btnStop.setBackground(new Color(160, 40, 40));
        btnStop.setForeground(Color.WHITE);
        btnStop.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnStop.setFocusPainted(false);
        btnStop.setEnabled(false);
        btnStop.addActionListener(e -> onStop());

        btnOpenWorkDir = new JButton("Open work dir");
        btnOpenWorkDir.setBackground(BG_INPUT);
        btnOpenWorkDir.setForeground(FG_TEXT);
        btnOpenWorkDir.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btnOpenWorkDir.setFocusPainted(false);
        btnOpenWorkDir.addActionListener(e -> openWorkDir());

        p.add(btnStart);
        p.add(btnStop);
        p.add(btnOpenWorkDir);
        return p;
    }

    // ── Right log panel ────────────────────────────────────────────────────

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_DARK);
        p.setBorder(new EmptyBorder(8, 4, 4, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG_DARK);
        JLabel lbl = label("Server Log");
        lbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        header.add(lbl, BorderLayout.WEST);

        btnClearLog = smallButton("Clear");
        btnClearLog.addActionListener(e -> {
            try { logDoc.remove(0, logDoc.getLength()); }
            catch (BadLocationException ignored) {}
        });
        header.add(btnClearLog, BorderLayout.EAST);
        p.add(header, BorderLayout.NORTH);

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(20, 20, 20));
        logPane.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logDoc = logPane.getStyledDocument();

        JScrollPane sp = new JScrollPane(logPane);
        sp.setBorder(null);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    // ── Status bar ─────────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        p.setBackground(new Color(35, 35, 35));
        p.setBorder(BorderFactory.createMatteBorder(
                1, 0, 0, 0, Color.DARK_GRAY));

        ledIndicator = new JLabel("●");
        ledIndicator.setFont(new Font("SansSerif", Font.BOLD, 16));
        ledIndicator.setForeground(RED_LED);
        p.add(ledIndicator);

        lblStatus = new JLabel("Stopped");
        lblStatus.setForeground(FG_LABEL);
        p.add(lblStatus);

        p.add(new JSeparator(SwingConstants.VERTICAL));

        lblAlgoCount = new JLabel("Algorithms: —");
        lblAlgoCount.setForeground(FG_LABEL);
        p.add(lblAlgoCount);

        p.add(new JSeparator(SwingConstants.VERTICAL));

        lblActiveJobs = new JLabel("Active jobs: 0");
        lblActiveJobs.setForeground(FG_LABEL);
        p.add(lblActiveJobs);

        return p;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ABOUT DIALOG
    // ══════════════════════════════════════════════════════════════════════

    private void showAboutDialog() {
        JDialog dialog = new JDialog(frame, "About " + APP_NAME, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);
        dialog.getContentPane().setBackground(BG_DARK);
        dialog.setLayout(new BorderLayout());

        JPanel stripe = new JPanel();
        stripe.setBackground(ACCENT);
        stripe.setPreferredSize(new Dimension(0, 6));
        dialog.add(stripe, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG_DARK);
        content.setBorder(new EmptyBorder(20, 28, 10, 28));

        JPanel logoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        logoRow.setOpaque(false);

        JLabel iconLbl = new JLabel("\u2B21");
        iconLbl.setFont(new Font("SansSerif", Font.BOLD, 48));
        iconLbl.setForeground(ACCENT);
        logoRow.add(iconLbl);

        JPanel titleBlock = new JPanel();
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
        titleBlock.setOpaque(false);

        JLabel nameLbl = new JLabel(APP_NAME);
        nameLbl.setFont(new Font("SansSerif", Font.BOLD, 22));
        nameLbl.setForeground(FG_TEXT);
        titleBlock.add(nameLbl);

        JLabel verLbl = new JLabel("Version " + APP_VERSION);
        verLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
        verLbl.setForeground(new Color(130, 130, 130));
        titleBlock.add(verLbl);

        JLabel tagLbl = new JLabel(
                "Sequential Pattern Mining Framework \u2014 Server");
        tagLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        tagLbl.setForeground(new Color(110, 110, 110));
        titleBlock.add(tagLbl);

        logoRow.add(titleBlock);
        content.add(logoRow);
        content.add(Box.createVerticalStrut(14));

        content.add(makeDivider());
        content.add(Box.createVerticalStrut(10));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(BG_PANEL);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70)),
                new EmptyBorder(10, 16, 10, 16)));

        String[][] rows = {
            { "Author",     AUTHOR       },
            { "Website",    WEBSITE      },
            { "License",    LICENSE_NAME },
            { "Copyright",  COPYRIGHT    },
            { "Built with", "Java  \u2022  Swing  \u2022  SPMF Library" },
        };

        for (String[] row : rows) {
            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3));
            rowPanel.setOpaque(false);

            JLabel keyLbl = new JLabel(row[0] + ":  ");
            keyLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            keyLbl.setForeground(new Color(100, 160, 220));
            keyLbl.setPreferredSize(new Dimension(90, 20));
            rowPanel.add(keyLbl);

            JLabel valLbl = new JLabel(row[1]);
            valLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            valLbl.setForeground(FG_TEXT);
            rowPanel.add(valLbl);

            card.add(rowPanel);
        }
        content.add(card);
        content.add(Box.createVerticalStrut(12));

        content.add(makeDivider());
        content.add(Box.createVerticalStrut(8));

        JTextArea descArea = plainTextArea(DESCRIPTION, 4);
        content.add(descArea);
        content.add(Box.createVerticalStrut(12));

        JLabel licTitle = new JLabel("License");
        licTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        licTitle.setForeground(new Color(100, 160, 220));
        content.add(licTitle);
        content.add(Box.createVerticalStrut(4));

        JTextArea licArea = plainTextArea(GPLv3_SUMMARY, 11);
        licArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        licArea.setForeground(new Color(160, 160, 160));
        JScrollPane licScroll = new JScrollPane(licArea);
        licScroll.setBorder(BorderFactory.createLineBorder(
                new Color(70, 70, 70)));
        licScroll.setBackground(new Color(22, 22, 22));
        licScroll.getViewport().setBackground(new Color(22, 22, 22));
        licScroll.setPreferredSize(new Dimension(480, 160));
        licScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        content.add(licScroll);
        content.add(Box.createVerticalStrut(16));

        content.add(makeDivider());
        content.add(Box.createVerticalStrut(12));

        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.setOpaque(false);

        JLabel copyrightLbl = new JLabel(COPYRIGHT);
        copyrightLbl.setFont(new Font("SansSerif", Font.ITALIC, 10));
        copyrightLbl.setForeground(new Color(100, 100, 100));
        bottomRow.add(copyrightLbl, BorderLayout.WEST);

        JButton closeBtn = new JButton("Close");
        closeBtn.setBackground(ACCENT);
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setFocusPainted(false);
        closeBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        closeBtn.setBorder(new EmptyBorder(6, 20, 6, 20));
        closeBtn.addActionListener(e -> dialog.dispose());
        bottomRow.add(closeBtn, BorderLayout.EAST);

        content.add(bottomRow);
        content.add(Box.createVerticalStrut(4));

        dialog.add(content, BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private JTextArea plainTextArea(String text, int rows) {
        JTextArea ta = new JTextArea(text, rows, 0);
        ta.setEditable(false);
        ta.setFocusable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setBackground(BG_DARK);
        ta.setForeground(new Color(170, 170, 170));
        ta.setFont(new Font("SansSerif", Font.PLAIN, 12));
        ta.setBorder(null);
        ta.setOpaque(true);
        ta.setAlignmentX(Component.LEFT_ALIGNMENT);
        ta.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return ta;
    }

    private JPanel makeDivider() {
        JPanel line = new JPanel();
        line.setBackground(new Color(65, 65, 65));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        line.setPreferredSize(new Dimension(0, 1));
        return line;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ACTIONS
    // ══════════════════════════════════════════════════════════════════════

    private void onStart() {
        ServerConfig cfg = buildConfigFromFields();
        if (cfg == null) return;

        btnStart.setEnabled(false);
        appendLog("Starting server on port " + cfg.getPort() + "…", Level.INFO);

        new Thread(() -> {
            try {
                ServerLogger.configure(cfg.getLogLevel(), cfg.getLogFile());
                ServerMain.startServer(cfg);
                loadedConfig = cfg;
                SwingUtilities.invokeLater(() -> {
                    btnStop.setEnabled(true);
                    appendLog("Server is running on port " + cfg.getPort(),
                              Level.INFO);
                    refreshStatusBar();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    btnStart.setEnabled(true);
                    appendLog("ERROR starting server: " + ex.getMessage(),
                              Level.SEVERE);
                    JOptionPane.showMessageDialog(frame,
                            "Failed to start server:\n" + ex.getMessage(),
                            "Start Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "gui-start-thread").start();
    }

    private void onStop() {
        btnStop.setEnabled(false);
        appendLog("Stopping server…", Level.INFO);
        new Thread(() -> {
            ServerMain.stopServer();
            SwingUtilities.invokeLater(() -> {
                btnStart.setEnabled(true);
                appendLog("Server stopped.", Level.INFO);
                refreshStatusBar();
            });
        }, "gui-stop-thread").start();
    }

    private void onClose() {
        if (ServerMain.running) {
            int choice = JOptionPane.showConfirmDialog(
                    frame,
                    "The server is still running.\nStop it and exit?",
                    "Confirm Exit",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            ServerMain.stopServer();
        }
        statusTimer.stop();
        frame.dispose();
        System.exit(0);
    }

    private void browseConfigFile() {
        JFileChooser fc = new JFileChooser(".");
        fc.setDialogTitle("Select configuration file");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Properties files (*.properties)", "properties"));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            String path = fc.getSelectedFile().getAbsolutePath();
            tfConfigFile.setText(path);
            loadConfigIntoFields(path);
        }
    }

    private void browseWorkDir() {
        JFileChooser fc = new JFileChooser(tfWorkDir.getText().trim());
        fc.setDialogTitle("Select work directory");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            tfWorkDir.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void openWorkDir() {
        String path = (loadedConfig != null)
                ? loadedConfig.getWorkDir()
                : tfWorkDir.getText().trim();
        try {
            File dir = new File(path);
            if (!dir.exists()) dir.mkdirs();
            Desktop.getDesktop().open(dir);
        } catch (Exception ex) {
            appendLog("Cannot open work dir: " + ex.getMessage(), Level.WARNING);
        }
    }

    // ── Status bar refresh ─────────────────────────────────────────────────

    /**
     * Called every STATUS_TIMER_INTERVAL_MS ms from the Swing Timer.
     *
     * FIX: kept deliberately minimal — only reads volatile fields and sets
     * label text.  No object allocation, no I/O, no lock contention.
     * The OOM was caused by this method being called every 1 s and Swing
     * accumulating timer events faster than the EDT could process them.
     */
    private void refreshStatusBar() {
        if (ServerMain.running) {
            ledIndicator.setForeground(GREEN_LED);
            int port = (loadedConfig != null) ? loadedConfig.getPort() : 0;
            lblStatus.setText("Running  \u00B7  port " + port);
            int algos = (ServerMain.catalogue != null)
                    ? ServerMain.catalogue.size() : 0;
            lblAlgoCount.setText("Algorithms: " + algos);
            int active = (ServerMain.jobManager != null)
                    ? ServerMain.jobManager.activeJobCount() : 0;
            int queued = (ServerMain.jobManager != null)
                    ? ServerMain.jobManager.queuedJobCount() : 0;
            lblActiveJobs.setText(
                    "Active jobs: " + active + "  Queued: " + queued);
        } else {
            ledIndicator.setForeground(RED_LED);
            lblStatus.setText("Stopped");
            lblActiveJobs.setText("Active jobs: 0");
        }
    }

    // ── Config → fields ────────────────────────────────────────────────────

    private void loadConfigIntoFields(String path) {
        ServerConfig c = ServerConfig.load(path);
        tfHost.setText(c.getHost());
        tfPort.setText(String.valueOf(c.getPort()));
        tfCoreThreads.setText(String.valueOf(c.getCoreThreads()));
        tfMaxThreads.setText(String.valueOf(c.getMaxThreads()));
        tfTtl.setText(String.valueOf(c.getJobTtlMinutes()));
        tfMaxQueueSize.setText(String.valueOf(c.getMaxQueueSize()));
        tfMaxInputMb.setText(String.valueOf(c.getMaxInputSizeMb()));
        tfWorkDir.setText(c.getWorkDir());
        tfApiKey.setText(c.getApiKey());
        cbLogLevel.setSelectedItem(c.getLogLevel().toUpperCase());
        appendLog("Configuration loaded from: " + path, Level.INFO);
    }

    private ServerConfig buildConfigFromFields() {
        try {
            int port = Integer.parseInt(tfPort.getText().trim());
            if (port < 1 || port > 65535)
                throw new NumberFormatException("port range");
            Integer.parseInt(tfCoreThreads.getText().trim());
            Integer.parseInt(tfMaxThreads.getText().trim());
            Integer.parseInt(tfTtl.getText().trim());
            Integer.parseInt(tfMaxQueueSize.getText().trim());
            Integer.parseInt(tfMaxInputMb.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(frame,
                    "Invalid numeric value in configuration.\n" +
                    "Please check port, threads, TTL, etc.",
                    "Validation Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        java.util.Properties p = new java.util.Properties();
        p.setProperty("server.host",          tfHost.getText().trim());
        p.setProperty("server.port",          tfPort.getText().trim());
        p.setProperty("executor.coreThreads", tfCoreThreads.getText().trim());
        p.setProperty("executor.maxThreads",  tfMaxThreads.getText().trim());
        p.setProperty("job.ttlMinutes",       tfTtl.getText().trim());
        p.setProperty("job.maxQueueSize",     tfMaxQueueSize.getText().trim());
        p.setProperty("input.maxSizeMb",      tfMaxInputMb.getText().trim());
        p.setProperty("work.dir",             tfWorkDir.getText().trim());
        p.setProperty("security.apiKey",      tfApiKey.getText().trim());
        p.setProperty("log.level",
                cbLogLevel.getSelectedItem() != null
                        ? cbLogLevel.getSelectedItem().toString() : "INFO");
        p.setProperty("log.file",
                loadedConfig != null
                        ? loadedConfig.getLogFile()
                        : "./logs/spmf-server.log");

        try {
            File tmp = File.createTempFile("spmf-gui-", ".properties");
            tmp.deleteOnExit();
            try (java.io.FileOutputStream fos =
                         new java.io.FileOutputStream(tmp)) {
                p.store(fos, "Generated by SPMF-Server GUI");
            }
            return ServerConfig.load(tmp.getAbsolutePath());
        } catch (Exception e) {
            appendLog("Could not build config: " + e.getMessage(), Level.SEVERE);
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOGGING TO GUI CONSOLE
    // ══════════════════════════════════════════════════════════════════════

    private void installLogHandler() {
        Handler guiHandler = new Handler() {
            @Override public void publish(LogRecord r) {
                if (!isLoggable(r)) return;
                String msg = getFormatter().format(r);
                SwingUtilities.invokeLater(() -> appendRawLog(msg, r.getLevel()));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        guiHandler.setFormatter(new SimpleFormatter());
        guiHandler.setLevel(Level.ALL);

        Logger pkgLogger = Logger.getLogger("ca.pfv.spmfserver");
        pkgLogger.addHandler(guiHandler);
    }

    public void appendLog(String message, Level level) {
        String ts   = LocalTime.now().format(
                DateTimeFormatter.ofPattern("HH:mm:ss"));
        String line = "[" + ts + "] " + level.getName() + "  " + message + "\n";
        SwingUtilities.invokeLater(() -> appendRawLog(line, level));
    }

    /**
     * FIX: log document size is capped at LOG_MAX_CHARS characters.
     * Previously the cap was 200 000 chars; now it is 100 000 and the
     * trimming happens BEFORE the insert, so the document never grows
     * beyond the cap even during a flood of log messages from an algorithm.
     */
    private void appendRawLog(String text, Level level) {
        try {
            // ── Trim BEFORE inserting so the doc never exceeds the cap ─────
            int excess = logDoc.getLength() + text.length() - LOG_MAX_CHARS;
            if (excess > 0) {
                // Remove from the top so the newest lines are always visible
                logDoc.remove(0, Math.min(excess, logDoc.getLength()));
            }

            Style lvlStyle = logPane.addStyle("level", null);
            Color c;
            if      (level.intValue() >= Level.SEVERE.intValue())  c = CLR_ERROR;
            else if (level.intValue() >= Level.WARNING.intValue()) c = CLR_WARN;
            else if (level.intValue() >= Level.FINE.intValue())    c = CLR_DEBUG;
            else                                                    c = CLR_INFO;
            StyleConstants.setForeground(lvlStyle, c);

            logDoc.insertString(logDoc.getLength(), text, lvlStyle);
            logPane.setCaretPosition(logDoc.getLength());

        } catch (BadLocationException ignored) {
        } catch (OutOfMemoryError oom) {
            // Last-resort: clear the log document entirely and note the OOM
            try {
                logDoc.remove(0, logDoc.getLength());
                Style s = logPane.addStyle("err", null);
                StyleConstants.setForeground(s, CLR_ERROR);
                logDoc.insertString(0,
                        "[LOG CLEARED — OutOfMemoryError]\n", s);
            } catch (BadLocationException ignored) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SWING HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private JPanel titledSection(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_PANEL);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80)), title);
        border.setTitleColor(FG_LABEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                new EmptyBorder(0, 0, 0, 0), border));
        return p;
    }

    private void addRow(JPanel panel, String labelText, JTextField field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row.setOpaque(false);
        JLabel lbl = label(labelText);
        lbl.setPreferredSize(new Dimension(110, 22));
        row.add(lbl);
        row.add(field);
        panel.add(row);
    }

    private void addRowRaw(JPanel panel, String labelText, JComponent comp) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        row.setOpaque(false);
        JLabel lbl = label(labelText);
        lbl.setPreferredSize(new Dimension(110, 22));
        row.add(lbl);
        row.add(comp);
        panel.add(row);
    }

    private JTextField styledTextField(int cols) {
        JTextField tf = new JTextField(cols);
        tf.setBackground(BG_INPUT);
        tf.setForeground(FG_TEXT);
        tf.setCaretColor(FG_TEXT);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 90, 90)),
                new EmptyBorder(2, 4, 2, 4)));
        return tf;
    }

    private void styleCombo(JComboBox<String> cb) {
        cb.setBackground(BG_INPUT);
        cb.setForeground(FG_TEXT);
        cb.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                setBackground(isSelected ? ACCENT : BG_INPUT);
                setForeground(FG_TEXT);
                return this;
            }
        });
    }

    private JLabel label(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(FG_LABEL);
        return lbl;
    }

    private JButton smallButton(String text) {
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
}