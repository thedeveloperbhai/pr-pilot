package com.vitiquest.peerreview.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.vitiquest.peerreview.ai.AiReviewResult
import com.vitiquest.peerreview.ai.OpenAIClient
import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.bitbucket.PullRequestService
import com.vitiquest.peerreview.jira.JiraIntegrationService
import com.vitiquest.peerreview.jira.ReviewOutcome
import com.vitiquest.peerreview.settings.GitProvider
import com.vitiquest.peerreview.settings.PluginSettings
import com.vitiquest.peerreview.utils.GitUtils
import com.vitiquest.peerreview.utils.RepoInfo
import java.awt.*
import javax.swing.*
import javax.swing.border.MatteBorder

// ---------------------------------------------------------------------------
// Main panel – uses CardLayout to switch between PR list and File list views
// ---------------------------------------------------------------------------
class PRToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val service  = PullRequestService()
    private val aiClient = OpenAIClient(project)
    private val jiraService = JiraIntegrationService()

    @Volatile private var disposed = false

    // ── shared state ─────────────────────────────────────────────────────────
    private var allPrs: List<PullRequest>     = emptyList()
    private var repoInfo: RepoInfo?           = null
    private var currentPr: PullRequest?       = null
    private var currentFileEntries: List<FileDiffEntry> = emptyList()

    // prId → number of changed files (populated when diff is fetched)
    private val prFileCount = mutableMapOf<Int, Int>()

    // prId → cached AI review result (summary + inline comments) — avoids repeat API calls
    private val aiSummaryCache = mutableMapOf<Int, AiReviewResult>()

    // filePath → open diff Window, so clicking the same file twice focuses instead of duplicating
    private val openDiffWindows = mutableMapOf<String, Window>()

    // ── status bar (shared across both views) ─────────────────────────────────
    private val statusLabel = JBLabel("Ready")
    private val spinner     = AsyncProcessIcon("pr-pilot-spinner").apply { isVisible = false }

    // ── PR list view components ───────────────────────────────────────────────
    private val listModel        = DefaultListModel<PullRequest>()
    private val prList           = JBList(listModel)
    private val idFilterField    = JBTextField(6)
    private val titleFilterField = JBTextField(14)
    private val stateFilter      = ComboBox(arrayOf("OPEN", "MERGED", "DECLINED", "ALL"))

    // ── File list view components ─────────────────────────────────────────────
    private val fileListModel = DefaultListModel<FileDiffEntry>()
    private val fileList      = JBList(fileListModel)
    private val breadcrumbBar = JPanel(BorderLayout())   // "← Back  •  PR #42 — title"
    private val statsBar      = JPanel(BorderLayout())   // "● N modified  ● N added  ● N deleted"

    // ── Card layout ───────────────────────────────────────────────────────────
    private val cardLayout   = CardLayout()
    private val cardPanel    = JPanel(cardLayout)
    private val CARD_PR_LIST = "PR_LIST"
    private val CARD_FILES   = "FILE_LIST"

    // ── Extracted helpers ─────────────────────────────────────────────────────
    private val aiReviewBuilder     = AiReviewBuilder(project, service, aiClient)
    private val aiSummaryDialogHelper by lazy {
        AiSummaryDialog(
            parent             = this,
            project            = project,
            service            = service,
            jiraService        = jiraService,
            onRegenerate       = ::regenerateAiSummary,
            onSetStatus        = ::setStatus,
            onSetBusy          = ::setBusy,
            onNotify           = ::notify,
            onLoadPullRequests = ::loadPullRequests,
            runInBackground    = ::runInBackground,
            invokeLater        = ::invokeLater
        )
    }

    init {
        buildUi()
        detectRepo()
        // Show the welcome screen on very first load
        if (!PluginSettings.instance.welcomeShown) {
            PluginSettings.instance.welcomeShown = true
            SwingUtilities.invokeLater { WelcomeDialog.show(this) }
        }
    }

    // =========================================================================
    // UI Construction
    // =========================================================================

    private fun buildUi() {
        background = JBColor.PanelBackground

        cardPanel.add(buildPrListView(), CARD_PR_LIST)
        cardPanel.add(buildFileListView(), CARD_FILES)

        val statusBar = JPanel(BorderLayout()).apply {
            border = MatteBorder(1, 0, 0, 0, JBColor.border())
            background = JBColor.PanelBackground
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(spinner)
                add(statusLabel.apply { border = JBUI.Borders.empty(3, 4, 3, 4) })
            }
            val helpBtn = JButton(AllIcons.General.Information).apply {
                toolTipText         = "PR Pilot — Documentation & Help"
                isBorderPainted     = false
                isContentAreaFilled = false
                preferredSize       = Dimension(22, 22)
                cursor              = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener   { WelcomeDialog.show(this@PRToolWindowPanel) }
            }
            add(left, BorderLayout.WEST)
            add(helpBtn, BorderLayout.EAST)
        }

        add(cardPanel,  BorderLayout.CENTER)
        add(statusBar,  BorderLayout.SOUTH)

        cardLayout.show(cardPanel, CARD_PR_LIST)
    }

    // ── View 1: PR list ───────────────────────────────────────────────────────

    private fun buildPrListView(): JPanel {
        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText         = "Refresh pull requests"
            isBorderPainted     = false
            isContentAreaFilled = false
            preferredSize       = Dimension(28, 28)
        }
        val filterBtn = JButton(AllIcons.General.Filter).apply {
            toolTipText         = "Apply filter"
            isBorderPainted     = false
            isContentAreaFilled = false
            preferredSize       = Dimension(28, 28)
        }

        // ── Action icon buttons ───────────────────────────────────────────────
        val viewDiffBtn = makeIconButton(AllIcons.Actions.Diff,                 "View changed files")
        val aiBtn       = makeIconButton(AllIcons.Actions.GeneratedFolder,      "AI Summary")
        val approveBtn  = makeIconButton(AllIcons.RunConfigurations.TestPassed, "Approve PR")
        val mergeBtn    = makeIconButton(AllIcons.Vcs.Merge,                    "Merge PR")
        val declineBtn  = makeIconButton(AllIcons.RunConfigurations.TestFailed, "Decline / Close PR")

        // ── Row 1: Filter bar ─────────────────────────────────────────────────
        val filterBar = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border   = JBUI.Borders.empty(4, 8, 4, 4)
        }
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(0, 2, 0, 2)
            anchor = GridBagConstraints.WEST
            fill   = GridBagConstraints.NONE
        }
        gbc.gridx = 0; filterBar.add(refreshBtn, gbc)
        gbc.gridx = 1; filterBar.add(JBLabel("Status:"), gbc)
        gbc.gridx = 2; filterBar.add(stateFilter, gbc)
        gbc.gridx = 3; filterBar.add(JBLabel("ID:"), gbc)
        gbc.gridx = 4; filterBar.add(idFilterField, gbc)
        gbc.gridx = 5; filterBar.add(JBLabel("Title:"), gbc)
        gbc.gridx = 6; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        filterBar.add(titleFilterField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.gridx = 7; filterBar.add(filterBtn, gbc)

        val filterRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = MatteBorder(0, 0, 1, 0, JBColor.border())
            add(filterBar, BorderLayout.CENTER)
        }

        // ── Row 2: PR action bar ───────────────────────────────────────────────
        val actionLabel = JBLabel("PR Actions:").apply {
            border = JBUI.Borders.empty(0, 8, 0, 4)
            font   = Font(font.family, Font.PLAIN, 11)
        }
        val actionBtnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 3)).apply {
            isOpaque = false
            add(actionLabel)
            add(viewDiffBtn)
            add(aiBtn)
            add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 20) })
            add(approveBtn)
            add(mergeBtn)
            add(declineBtn)
        }

        val actionRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = MatteBorder(0, 0, 1, 0, JBColor.border())
            add(actionBtnPanel, BorderLayout.WEST)
        }

        // ── Combined top bar: two stacked rows ────────────────────────────────
        val topBar = JPanel().apply {
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.PanelBackground
            add(filterRow)
            add(actionRow)
        }

        // ── PR list ───────────────────────────────────────────────────────────
        prList.cellRenderer = PRCardRenderer(prFileCount)
        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.fixedCellHeight = -1
        prList.background = JBColor.PanelBackground
        val scrollPane = JBScrollPane(prList).apply {
            border = MatteBorder(0, 0, 0, 0, JBColor.border())
        }

        // ── Wire listeners ────────────────────────────────────────────────────
        // If no repo has been detected yet, refresh should re-run detection
        // (handles the startup race-condition and manual "retry" after an error).
        refreshBtn.addActionListener  { if (repoInfo == null) detectRepo() else loadPullRequests() }
        filterBtn.addActionListener   { applyFilter() }
        stateFilter.addActionListener { loadPullRequests() }
        viewDiffBtn.addActionListener { onViewFiles() }
        aiBtn.addActionListener       { onAiSummary() }
        approveBtn.addActionListener  { onApprove() }
        mergeBtn.addActionListener    { onMerge() }
        declineBtn.addActionListener  { onDecline() }
        prList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) onViewFiles()
            }
        })

        return JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            add(topBar,    BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    // ── View 2: File list ─────────────────────────────────────────────────────

    private fun buildFileListView(): JPanel {
        breadcrumbBar.apply {
            border     = MatteBorder(0, 0, 1, 0, JBColor.border())
            background = JBColor.PanelBackground
            isOpaque   = true
        }
        statsBar.apply {
            border     = MatteBorder(0, 0, 1, 0, JBColor.border())
            background = JBColor.PanelBackground
            isOpaque   = true
            isVisible  = false   // hidden until a PR diff is loaded
        }
        populateInitialBreadcrumb()

        // North compound: breadcrumb + stats bar stacked vertically
        val northPanel = JPanel().apply {
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque   = false
            add(breadcrumbBar)
            add(statsBar)
        }

        // file list
        fileList.cellRenderer = FileEntryRenderer()
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.fixedCellHeight = -1
        fileList.background = JBColor.PanelBackground
        fileList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 1) {
                    fileList.selectedValue?.let { openFileDiff(it) }
                }
            }
        })
        val scroll = JBScrollPane(fileList).apply {
            border = MatteBorder(0, 0, 0, 0, JBColor.border())
        }

        val hintLabel = JBLabel("Click a file to open side-by-side diff").apply {
            foreground = JBColor.GRAY
            font       = Font(font.family, Font.ITALIC, 11)
            border     = JBUI.Borders.empty(4, 12, 4, 12)
            horizontalAlignment = SwingConstants.CENTER
        }

        return JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            add(northPanel, BorderLayout.NORTH)
            add(scroll,     BorderLayout.CENTER)
            add(hintLabel,  BorderLayout.SOUTH)
        }
    }

    /** Puts a plain back button in breadcrumbBar before any PR is selected. */
    private fun populateInitialBreadcrumb() {
        breadcrumbBar.removeAll()
        val backBtn = makeBackButton()
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(backBtn)
            add(JBLabel("Changed Files").apply {
                font = Font(font.family, Font.BOLD, 12)
                border = JBUI.Borders.empty(0, 4, 0, 0)
            })
        }
        breadcrumbBar.add(left, BorderLayout.CENTER)
        breadcrumbBar.revalidate()
        breadcrumbBar.repaint()
    }

    private fun updateBreadcrumb(pr: PullRequest, entries: List<FileDiffEntry>) {
        val fileCount = entries.size

        // ── Breadcrumb bar ────────────────────────────────────────────────────
        breadcrumbBar.removeAll()
        val prLabel = JBLabel("PR #${pr.id}  —  ${pr.title}").apply {
            font       = Font(font.family, Font.BOLD, 12)
            foreground = JBColor.foreground()
            border     = JBUI.Borders.empty(0, 6, 0, 0)
        }
        val branchLabel = JBLabel("${pr.source.branch.name}  →  ${pr.destination.branch.name}").apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border     = JBUI.Borders.empty(0, 10, 0, 0)
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(makeBackButton())
            add(prLabel)
            add(branchLabel)
        }
        val fileCountLabel = JBLabel("📄 $fileCount file${if (fileCount == 1) "" else "s"} changed").apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border     = JBUI.Borders.empty(0, 0, 0, 8)
        }
        val rightWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            isOpaque = false
            add(fileCountLabel)
            add(StatePill(pr.state))
        }
        breadcrumbBar.add(left,      BorderLayout.CENTER)
        breadcrumbBar.add(rightWrap, BorderLayout.EAST)
        breadcrumbBar.revalidate()
        breadcrumbBar.repaint()

        // ── Stats bar — counts per status ─────────────────────────────────────
        val modified = entries.count { it.statusTag == "MODIFIED" }
        val added    = entries.count { it.statusTag == "ADDED"    }
        val deleted  = entries.count { it.statusTag == "DELETED"  }
        val renamed  = entries.count { it.statusTag == "RENAMED"  }

        statsBar.removeAll()
        val chips = JPanel(FlowLayout(FlowLayout.LEFT, 6, 5)).apply {
            isOpaque = false
            if (modified > 0) add(statChip("$modified modified", JBColor(Color(0xB45309), Color(0xD29922))))
            if (added    > 0) add(statChip("$added added",       JBColor(Color(0x166534), Color(0x3FB950))))
            if (deleted  > 0) add(statChip("$deleted deleted",   JBColor(Color(0x9F1239), Color(0xF85149))))
            if (renamed  > 0) add(statChip("$renamed renamed",   JBColor(Color(0x0550AE), Color(0x58A6FF))))
        }
        statsBar.add(chips, BorderLayout.CENTER)
        statsBar.isVisible = true
        statsBar.revalidate()
        statsBar.repaint()
    }

    /** Small coloured dot + label chip for the stats bar. */
    private fun statChip(text: String, color: Color): JPanel {
        val dot = object : JComponent() {
            init { preferredSize = Dimension(8, 8); isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(0, 0, 8, 8)
            }
        }
        val lbl = JLabel(text).apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = color
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            isOpaque = false
            add(dot)
            add(lbl)
        }
    }

    private fun makeBackButton() = JButton(AllIcons.Actions.Back).apply {
        toolTipText         = "Back to pull requests"
        isBorderPainted     = false
        isContentAreaFilled = false
        preferredSize       = Dimension(28, 28)
        addActionListener   { showPrListView() }
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private fun showPrListView() {
        openDiffWindows.clear()
        cardLayout.show(cardPanel, CARD_PR_LIST)
        setStatus("${listModel.size()} PR(s) loaded.")
    }

    private fun showFileListView(pr: PullRequest, entries: List<FileDiffEntry>) {
        currentPr          = pr
        currentFileEntries = entries
        prFileCount[pr.id] = entries.size
        prList.repaint()
        fileListModel.clear()
        entries.forEach { fileListModel.addElement(it) }
        updateBreadcrumb(pr, entries)
        setStatus("${entries.size} file(s) changed in PR #${pr.id}.")
        cardLayout.show(cardPanel, CARD_FILES)
    }

    // =========================================================================
    // Repository Detection
    // =========================================================================

    /**
     * Detects the repository for the current project.
     *
     * Git4Idea initialises its GitRepositoryManager asynchronously, so on a
     * cold startup the repositories list can be empty for a few seconds even
     * when the project definitely contains a .git directory.  We therefore
     * retry up to MAX_RETRIES times with an exponential back-off (1 s, 2 s, 4 s …)
     * before giving up and showing an error.
     *
     * When repos have loaded but no GitHub/Bitbucket remote exists we show a
     * different, more actionable message than the "loading …" state.
     */
    private fun detectRepo(retryCount: Int = 0) {
        val MAX_RETRIES = 6
        runInBackground {
            PluginSettings.instance.warmUpSecretsCache()

            // Check whether Git4Idea has finished scanning yet
            val reposReady = GitUtils.hasRepositories(project)

            if (!reposReady && retryCount < MAX_RETRIES) {
                val delayMs = (1000L shl retryCount).coerceAtMost(10_000L) // 1 s, 2 s, 4 s … capped at 10 s
                invokeLater {
                    setStatus("⏳ Waiting for Git to initialise… (attempt ${retryCount + 1}/$MAX_RETRIES)")
                }
                Thread.sleep(delayMs)
                invokeLater { detectRepo(retryCount + 1) }
                return@runInBackground
            }

            val info = GitUtils.detectRepo(project)
            invokeLater {
                if (info == null) {
                    if (!reposReady) {
                        setStatus("⚠ Git repository not found. Make sure this project is inside a Git repo. Click Refresh to retry.")
                    } else {
                        setStatus("⚠ No GitHub or Bitbucket remote found. Check your remote URLs. Click Refresh to retry.")
                    }
                    return@invokeLater
                }
                repoInfo = info
                // Auto-sync the git provider setting with what was detected
                PluginSettings.instance.gitProvider = info.provider
                val providerLabel = if (info.provider == GitProvider.GITHUB) "GitHub" else "Bitbucket"
                setStatus("📦 $providerLabel · ${info.owner}/${info.repoSlug}")
                loadPullRequests()
            }
        }
    }

    // =========================================================================
    // Load PRs
    // =========================================================================

    private fun loadPullRequests() {
        val info = repoInfo ?: run { setStatus("⚠ Repository not detected."); return }
        val selectedState = stateFilter.selectedItem as String
        setStatus("Loading pull requests…")
        setBusy(true)
        runInBackground {
            try {
                val prs = service.getPullRequests(info.owner, info.repoSlug, selectedState)
                allPrs = prs
                invokeLater { setBusy(false); applyFilter(); setStatus("${prs.size} PR(s) loaded.") }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                invokeLater {
                    setBusy(false)
                    setStatus("⚠ Failed to load PRs — see error dialog.")
                    val isAuthError = msg.contains("401") || msg.contains("No Access Token") ||
                                      msg.contains("No PAT") || msg.contains("Invalid or expired")
                    val detail = if (isAuthError)
                        "$msg\n\nTip: Go to Settings → PR Pilot → Git Providers and " +
                        "verify the Access Token for '${info.owner}/${info.repoSlug}'."
                    else msg
                    Messages.showErrorDialog(project, detail, "Load Pull Requests Failed")
                }
            }
        }
    }

    private fun applyFilter() {
        val id    = idFilterField.text.trim()
        val title = titleFilterField.text.trim().lowercase()
        listModel.clear()
        allPrs.filter {
            (id.isEmpty()    || it.id.toString() == id) &&
            (title.isEmpty() || it.title.lowercase().contains(title))
        }.forEach { listModel.addElement(it) }
    }

    // =========================================================================
    // "View Files" — fetch diff, parse, switch to file list view
    // =========================================================================

    private fun onViewFiles() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        setStatus("Fetching diff for PR #${pr.id}…")
        setBusy(true)
        runInBackground {
            try {
                val rawDiff = service.getPullRequestDiff(info.owner, info.repoSlug, pr.id)
                val entries = DiffParser.parseToEntries(rawDiff)
                invokeLater {
                    setBusy(false)
                    if (entries.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No changes found in PR #${pr.id}.",
                            "No Diff", JOptionPane.INFORMATION_MESSAGE)
                    } else {
                        showFileListView(pr, entries)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                invokeLater {
                    setBusy(false)
                    setStatus("⚠ Diff error — see error dialog.")
                    Messages.showErrorDialog(project, msg, "Fetch Diff Failed")
                }
            }
        }
    }

    // =========================================================================
    // Open a single file's diff in IntelliJ side-by-side viewer
    // =========================================================================

    private fun openFileDiff(entry: FileDiffEntry) {
        val pr = currentPr ?: return

        // If a window for this file is already open, focus it instead of opening a new one
        val cacheKey = "${pr.id}::${entry.displayLabel}"
        val existing = openDiffWindows[cacheKey]
        if (existing != null && existing.isDisplayable && existing.isVisible) {
            existing.toFront()
            existing.requestFocus()
            return
        }

        val factory = DiffContentFactory.getInstance()

        // Detect file type for syntax highlighting — use the non-deleted path
        val filePath = if (entry.statusTag == "DELETED") entry.oldPath else entry.newPath
        val ext = filePath.substringAfterLast('.', "")
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)

        // DiffContentFactory.create(project, text, fileType) must run inside a read action
        val leftContent  = com.intellij.openapi.application.ReadAction.compute<com.intellij.diff.contents.DiffContent, Exception> {
            factory.create(project, entry.oldText, fileType)
        }
        val rightContent = com.intellij.openapi.application.ReadAction.compute<com.intellij.diff.contents.DiffContent, Exception> {
            factory.create(project, entry.newText, fileType)
        }

        val leftTitle  = when (entry.statusTag) {
            "ADDED"   -> "(new file)"
            else      -> "${pr.destination.branch.name}  (base)"
        }
        val rightTitle = when (entry.statusTag) {
            "DELETED" -> "(deleted)"
            else      -> "${pr.source.branch.name}  (PR)"
        }

        val request = SimpleDiffRequest(
            "PR #${pr.id}  —  ${entry.displayLabel}",
            leftContent, rightContent,
            leftTitle,   rightTitle
        )

        // Show the diff — then locate the newly created window and cache it
        DiffManager.getInstance().showDiff(project, request, DiffDialogHints.DEFAULT)

        // The diff dialog is shown modally on EDT; find it by title in the Window list
        val title = "PR #${pr.id}  —  ${entry.displayLabel}"
        Window.getWindows()
            .firstOrNull { w -> w.isVisible && windowTitle(w) == title }
            ?.let { w ->
                openDiffWindows[cacheKey] = w
                // Clean up cache when the window is closed
                w.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosed(e: java.awt.event.WindowEvent) {
                        openDiffWindows.remove(cacheKey)
                    }
                })
            }
    }

    /** Extract the title from a Window (works for JDialog and JFrame). */
    private fun windowTitle(w: Window): String = when (w) {
        is java.awt.Dialog -> w.title
        is java.awt.Frame  -> w.title
        else               -> ""
    }

    // =========================================================================
    // AI Summary
    // =========================================================================

    private fun onAiSummary() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return

        // ── Serve from cache if available ─────────────────────────────────────
        val cached = aiSummaryCache[pr.id]
        if (cached != null) {
            setStatus("AI summary loaded from cache for PR #${pr.id}.")
            showSummaryDialog(pr, cached, info)
            return
        }

        generateAiSummary(pr, info, forceRegenerate = false)
    }

    /** Clears the cache for [pr] and re-runs the full AI pipeline. */
    private fun regenerateAiSummary(pr: PullRequest, info: RepoInfo) {
        aiSummaryCache.remove(pr.id)
        generateAiSummary(pr, info, forceRegenerate = true)
    }

    private fun generateAiSummary(pr: PullRequest, info: RepoInfo, forceRegenerate: Boolean) {
        setStatus("${if (forceRegenerate) "Regenerating" else "Building"} AI summary for PR #${pr.id}…")
        setBusy(true)
        runInBackground {
            try {
                val rawText = aiReviewBuilder.buildSummaryText(pr, info) { msg ->
                    invokeLater { setStatus(msg) }
                }
                val result  = aiReviewBuilder.parseResponse(rawText)
                invokeLater {
                    setBusy(false)
                    aiSummaryCache[pr.id] = result
                    showSummaryDialog(pr, result, info)
                    setStatus("AI summary generated (${result.inlineComments.size} inline comment(s)).")
                }
            } catch (e: Exception) {
                invokeLater {
                    setBusy(false)
                    setStatus("AI error: ${e.message}")
                    Messages.showErrorDialog(project, e.message ?: "Unknown error", "AI Summary Failed")
                }
            }
        }
    }

    private fun getOrBuildAiSummary(pr: PullRequest, info: RepoInfo): String {
        return aiSummaryCache[pr.id]?.summary
            ?: aiReviewBuilder.parseResponse(aiReviewBuilder.buildSummaryText(pr, info))
                .also { aiSummaryCache[pr.id] = it }
                .summary
    }

    private fun showSummaryDialog(pr: PullRequest, result: AiReviewResult, info: RepoInfo) =
        aiSummaryDialogHelper.show(pr, result, info)



















    // =========================================================================
    // Approve / Decline
    // =========================================================================

    private fun onApprove() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        val actionLabel = if (PluginSettings.instance.gitProvider == GitProvider.GITHUB) "Approve (review)" else "Approve"
        if (Messages.showYesNoDialog(project, "$actionLabel PR #${pr.id}: \"${pr.title}\"?",
                "Confirm Approve", Messages.getQuestionIcon()) != Messages.YES) return
        setStatus("Approving PR #${pr.id}…")
        setBusy(true)
        runInBackground {
            try {
                service.approvePullRequest(info.owner, info.repoSlug, pr.id)
                val jiraMessage = runCatching {
                    jiraService.syncReviewOutcome(pr, ReviewOutcome.APPROVED).userMessage()
                }.getOrElse { "JIRA sync failed: ${it.message}" }
                invokeLater {
                    setBusy(false)
                    notify(
                        buildString {
                            append("PR #${pr.id} approved.")
                            if (!jiraMessage.isNullOrBlank()) append(" $jiraMessage")
                        },
                        NotificationType.INFORMATION
                    )
                    loadPullRequests()
                }
            } catch (e: Exception) {
                invokeLater {
                    setBusy(false)
                    setStatus("Approve failed.")
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Unknown error",
                        "Approve PR #${pr.id} Failed"
                    )
                }
            }
        }
    }

    private fun onDecline() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        val actionLabel = if (PluginSettings.instance.gitProvider == GitProvider.GITHUB) "Close" else "Decline"
        if (Messages.showYesNoDialog(project, "$actionLabel PR #${pr.id}: \"${pr.title}\"?",
                "Confirm $actionLabel", Messages.getWarningIcon()) != Messages.YES) return
        setStatus("${actionLabel}ing PR #${pr.id}…")
        setBusy(true)
        runInBackground {
            try {
                val summary = runCatching {
                    invokeLater { setStatus("Generating AI summary for PR #${pr.id}…") }
                    getOrBuildAiSummary(pr, info)
                }.getOrNull()
                service.declinePullRequest(info.owner, info.repoSlug, pr.id)
                val jiraMessage = runCatching {
                    jiraService.syncReviewOutcome(pr, ReviewOutcome.DECLINED, summary).userMessage()
                }.getOrElse { "JIRA sync failed: ${it.message}" }
                invokeLater {
                    setBusy(false)
                    notify(
                        buildString {
                            append("PR #${pr.id} ${actionLabel.lowercase()}d.")
                            if (!jiraMessage.isNullOrBlank()) append(" $jiraMessage")
                        },
                        NotificationType.WARNING
                    )
                    loadPullRequests()
                }
            } catch (e: Exception) {
                invokeLater {
                    setBusy(false)
                    setStatus("$actionLabel failed.")
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Unknown error",
                        "$actionLabel PR #${pr.id} Failed"
                    )
                }
            }
        }
    }

    private fun onMerge() {
        val pr   = selectedPr() ?: return
        val info = repoInfo ?: return
        if (pr.state.uppercase() != "OPEN") {
            Messages.showWarningDialog(
                project,
                "PR #${pr.id} is not open and cannot be merged.",
                "Cannot Merge"
            )
            return
        }
        if (Messages.showYesNoDialog(
                project,
                "Merge PR #${pr.id}: \"${pr.title}\"?\n\nThis will create a merge commit on \"${pr.destination.branch.name}\".",
                "Confirm Merge",
                Messages.getQuestionIcon()
            ) != Messages.YES) return
        setStatus("Merging PR #${pr.id}…")
        setBusy(true)
        runInBackground {
            try {
                service.mergePullRequest(info.owner, info.repoSlug, pr.id)
                val jiraMessage = runCatching {
                    jiraService.syncReviewOutcome(pr, ReviewOutcome.MERGED).userMessage()
                }.getOrElse { "JIRA sync failed: ${it.message}" }
                invokeLater {
                    setBusy(false)
                    notify(
                        buildString {
                            append("PR #${pr.id} merged successfully.")
                            if (!jiraMessage.isNullOrBlank()) append(" $jiraMessage")
                        },
                        NotificationType.INFORMATION
                    )
                    loadPullRequests()
                }
            } catch (e: Exception) {
                invokeLater {
                    setBusy(false)
                    setStatus("Merge failed.")
                    Messages.showErrorDialog(
                        project,
                        e.message ?: "Unknown error",
                        "Merge PR #${pr.id} Failed"
                    )
                }
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun selectedPr(): PullRequest? = prList.selectedValue ?: run {
        JOptionPane.showMessageDialog(this, "Please select a Pull Request first.",
            "No Selection", JOptionPane.WARNING_MESSAGE); null
    }

    private fun setStatus(msg: String) { statusLabel.text = msg }
    private fun setBusy(busy: Boolean) {
        spinner.isVisible = busy
        if (busy) spinner.resume() else spinner.suspend()
    }

    private fun notify(msg: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("PR Pilot")
            .createNotification(msg, type).notify(project)
    }

    private fun makeIconButton(icon: Icon, tooltip: String): JButton {
        return object : JButton(icon) {
            private var hovered = false
            init {
                toolTipText         = tooltip
                isBorderPainted     = false
                isContentAreaFilled = false
                isFocusPainted      = false
                isOpaque            = false
                preferredSize       = Dimension(28, 28)
                cursor              = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true;  repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent)  { hovered = false; repaint() }
                })
            }
            override fun paintComponent(g: Graphics) {
                if (hovered) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    // Grey rounded background — visible in light theme, subtle in dark
                    g2.color = JBColor(Color(0, 0, 0, 45), Color(255, 255, 255, 40))
                    g2.fillRoundRect(0, 0, width, height, 6, 6)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
    }

    private fun runInBackground(block: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().executeOnPooledThread(block)
    }

    private fun invokeLater(block: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) block()
        }
    }

    // =========================================================================
    // Disposable — called when the tool window content is removed
    // =========================================================================

    override fun dispose() {
        disposed = true
        // Drop all Window references so they can be GC'd
        openDiffWindows.clear()
    }
}


