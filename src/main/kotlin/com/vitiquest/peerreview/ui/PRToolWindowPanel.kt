package com.vitiquest.peerreview.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.vitiquest.peerreview.ai.OpenAIClient
import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.bitbucket.PullRequestService
import com.vitiquest.peerreview.utils.GitUtils
import java.awt.*
import java.io.File
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.border.MatteBorder

class PRToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = PullRequestService()
    private val aiClient = OpenAIClient()

    private val listModel = DefaultListModel<PullRequest>()
    private val prList = JBList(listModel)
    private val statusLabel = JBLabel("Ready")

    // Filters
    private val idFilterField = JBTextField(6)
    private val titleFilterField = JBTextField(14)
    private val stateFilter = ComboBox(arrayOf("OPEN", "MERGED", "DECLINED", "ALL"))

    // All loaded PRs (unfiltered)
    private var allPrs: List<PullRequest> = emptyList()
    private var bitbucketRepo: Pair<String, String>? = null

    init {
        buildUi()
        detectRepo()
    }

    // -------------------------------------------------------------------------
    // UI Construction
    // -------------------------------------------------------------------------

    private fun buildUi() {
        background = JBColor.PanelBackground

        // ── Top toolbar ──────────────────────────────────────────────────────
        val topPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 4, 8)
            background = JBColor.PanelBackground
        }
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(0, 2, 0, 2)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }

        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh pull requests"
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(28, 28)
        }

        gbc.gridx = 0; topPanel.add(refreshBtn, gbc)
        gbc.gridx = 1; topPanel.add(JBLabel("Status:"), gbc)
        gbc.gridx = 2; topPanel.add(stateFilter, gbc)
        gbc.gridx = 3; topPanel.add(JBLabel("ID:"), gbc)
        gbc.gridx = 4; topPanel.add(idFilterField, gbc)
        gbc.gridx = 5; topPanel.add(JBLabel("Title:"), gbc)
        gbc.gridx = 6; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        topPanel.add(titleFilterField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        val filterBtn = JButton("Filter").apply { putClientProperty("JButton.buttonType", "tag") }
        gbc.gridx = 7; topPanel.add(filterBtn, gbc)

        // ── PR List ───────────────────────────────────────────────────────────
        prList.cellRenderer = PRCardRenderer()
        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.fixedCellHeight = -1   // variable height
        prList.background = JBColor.PanelBackground
        val scrollPane = JBScrollPane(prList).apply {
            border = MatteBorder(1, 0, 1, 0, JBColor.border())
        }

        // ── Action buttons ────────────────────────────────────────────────────
        val btnPanel = JPanel(GridLayout(1, 4, 4, 0)).apply {
            border = JBUI.Borders.empty(6, 8, 4, 8)
            background = JBColor.PanelBackground
        }
        val viewDiffBtn    = makeActionButton("View Diff",            AllIcons.Actions.Diff)
        val aiBtn          = makeActionButton("AI Summary",           AllIcons.Actions.GeneratedFolder)
        val approveBtn     = makeActionButton("✔  Approve",           AllIcons.RunConfigurations.TestPassed)
        val declineBtn     = makeActionButton("✘  Decline",           AllIcons.RunConfigurations.TestFailed)
        btnPanel.add(viewDiffBtn)
        btnPanel.add(aiBtn)
        btnPanel.add(approveBtn)
        btnPanel.add(declineBtn)

        // ── Status bar ────────────────────────────────────────────────────────
        val statusBar = JPanel(BorderLayout()).apply {
            border = MatteBorder(1, 0, 0, 0, JBColor.border())
            background = JBColor.PanelBackground
            add(statusLabel.apply { border = JBUI.Borders.empty(3, 8, 3, 8) }, BorderLayout.WEST)
        }

        val southPanel = JPanel(BorderLayout()).apply {
            background = JBColor.PanelBackground
            add(btnPanel, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }

        add(topPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)

        // ── Listeners ─────────────────────────────────────────────────────────
        refreshBtn.addActionListener  { loadPullRequests() }
        filterBtn.addActionListener   { applyFilter() }
        stateFilter.addActionListener { loadPullRequests() }
        viewDiffBtn.addActionListener { onViewDiff() }
        aiBtn.addActionListener       { onAiSummary() }
        approveBtn.addActionListener  { onApprove() }
        declineBtn.addActionListener  { onDecline() }

        // double-click to view diff
        prList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) onViewDiff()
            }
        })
    }

    private fun makeActionButton(label: String, icon: Icon) = JButton(label, icon).apply {
        horizontalAlignment = SwingConstants.LEFT
        iconTextGap = 6
    }

    // -------------------------------------------------------------------------
    // Repository Detection
    // -------------------------------------------------------------------------

    private fun detectRepo() {
        val repo = GitUtils.detectBitbucketRepo(project)
        if (repo == null) {
            setStatus("⚠ No Bitbucket remote found for this project.")
            return
        }
        bitbucketRepo = repo.workspace to repo.repoSlug
        setStatus("📦 ${repo.workspace}/${repo.repoSlug}")
        loadPullRequests()
    }

    // -------------------------------------------------------------------------
    // Load PRs
    // -------------------------------------------------------------------------

    private fun loadPullRequests() {
        val (workspace, slug) = bitbucketRepo ?: run {
            setStatus("⚠ Bitbucket repository not detected.")
            return
        }
        val selectedState = stateFilter.selectedItem as String
        val state = if (selectedState == "ALL") "OPEN&state=MERGED&state=DECLINED" else selectedState
        setStatus("Loading pull requests…")
        runInBackground {
            try {
                val prs = service.getPullRequests(workspace, slug, state)
                allPrs = prs
                invokeLater {
                    applyFilter()
                    setStatus("${prs.size} PR(s) loaded.")
                }
            } catch (e: Exception) {
                invokeLater { setStatus("⚠ ${e.message}") }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Filtering
    // -------------------------------------------------------------------------

    private fun applyFilter() {
        val idFilter    = idFilterField.text.trim()
        val titleFilter = titleFilterField.text.trim().lowercase()
        val filtered = allPrs.filter { pr ->
            (idFilter.isEmpty()    || pr.id.toString() == idFilter) &&
            (titleFilter.isEmpty() || pr.title.lowercase().contains(titleFilter))
        }
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
    }

    // -------------------------------------------------------------------------
    // Diff Viewer  (side-by-side per file, Bitbucket-style)
    // -------------------------------------------------------------------------

    private fun onViewDiff() {
        val pr = selectedPr() ?: return
        val (workspace, slug) = bitbucketRepo ?: return
        setStatus("Fetching diff for PR #${pr.id}…")
        runInBackground {
            try {
                val rawDiff = service.getPullRequestDiff(workspace, slug, pr.id)
                val requests = buildDiffRequests(pr, rawDiff)
                invokeLater {
                    if (requests.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No changes found in this PR.", "No Diff", JOptionPane.INFORMATION_MESSAGE)
                    } else {
                        val chain = SimpleDiffRequestChain(requests)
                        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
                    }
                    setStatus("Diff loaded — ${requests.size} file(s) changed.")
                }
            } catch (e: Exception) {
                invokeLater { setStatus("Diff error: ${e.message}") }
            }
        }
    }

    /**
     * Parses a unified diff into one SimpleDiffRequest per file.
     * Each request has left = old content, right = new content,
     * giving a clean Bitbucket-style side-by-side view inside IntelliJ's diff viewer.
     */
    private fun buildDiffRequests(pr: PullRequest, rawDiff: String): List<SimpleDiffRequest> {
        val factory = DiffContentFactory.getInstance()
        val requests = mutableListOf<SimpleDiffRequest>()

        // Split on "diff --git" boundaries
        val filePatches = rawDiff.split(Regex("(?=diff --git )")).filter { it.isNotBlank() }

        for (patch in filePatches) {
            val lines = patch.lines()

            // Extract file paths from the "--- a/..." / "+++ b/..." headers
            val oldPath = lines.firstOrNull { it.startsWith("--- ") }
                ?.removePrefix("--- ")
                ?.removePrefix("a/")
                ?.trim() ?: "unknown"
            val newPath = lines.firstOrNull { it.startsWith("+++ ") }
                ?.removePrefix("+++ ")
                ?.removePrefix("b/")
                ?.trim() ?: "unknown"

            val isNewFile     = oldPath == "/dev/null" || oldPath == "dev/null"
            val isDeletedFile = newPath == "/dev/null" || newPath == "dev/null"

            // Reconstruct old and new file content from the diff hunks
            val oldLines = mutableListOf<String>()
            val newLines = mutableListOf<String>()

            for (line in lines) {
                when {
                    line.startsWith("--- ") || line.startsWith("+++ ") ||
                    line.startsWith("diff ") || line.startsWith("index ") ||
                    line.startsWith("new file") || line.startsWith("deleted file") ||
                    line.startsWith("@@") -> Unit   // skip header lines
                    line.startsWith("-")  -> oldLines.add(line.drop(1))
                    line.startsWith("+")  -> newLines.add(line.drop(1))
                    line.startsWith("\\") -> Unit   // "\ No newline at end of file"
                    else                  -> {      // context line
                        val ctx = line.drop(1).ifEmpty { line } // drop leading space
                        oldLines.add(ctx)
                        newLines.add(ctx)
                    }
                }
            }

            val oldText = if (isNewFile) "" else oldLines.joinToString("\n")
            val newText = if (isDeletedFile) "" else newLines.joinToString("\n")

            val leftContent  = factory.create(project, oldText)
            val rightContent = factory.create(project, newText)

            val displayPath  = if (isNewFile) newPath else if (isDeletedFile) oldPath else newPath
            val label        = when {
                isNewFile     -> "✚ $displayPath"
                isDeletedFile -> "✖ $displayPath"
                oldPath != newPath -> "↷ $oldPath → $newPath"
                else          -> displayPath
            }

            val request = SimpleDiffRequest(
                "PR #${pr.id} — $label",
                leftContent,
                rightContent,
                if (isNewFile) "(new file)" else "${pr.destination.branch.name}  (base)",
                if (isDeletedFile) "(deleted)" else "${pr.source.branch.name}  (PR)"
            )
            requests.add(request)
        }

        return requests
    }

    // -------------------------------------------------------------------------
    // AI Summary
    // -------------------------------------------------------------------------

    private fun onAiSummary() {
        val pr = selectedPr() ?: return
        val (workspace, slug) = bitbucketRepo ?: return
        setStatus("Building AI summary for PR #${pr.id}…")
        runInBackground {
            try {
                val diffStat    = service.getDiffStat(workspace, slug, pr.id)
                val changedFiles = diffStat.take(20).mapNotNull { it.newFile?.path ?: it.oldFile?.path }
                val basePath    = project.basePath ?: ""
                val fileContents = changedFiles.joinToString("\n\n") { path ->
                    val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, path))
                    val content = vFile?.let {
                        try { String(it.contentsToByteArray()).take(3000) } catch (_: Exception) { "[unreadable]" }
                    } ?: "[not found in local checkout]"
                    "### $path\n$content"
                }
                val prompt  = buildPrompt(pr, changedFiles, fileContents)
                val summary = aiClient.generateSummary(prompt)
                invokeLater {
                    showSummaryDialog(pr, summary)
                    setStatus("AI summary generated.")
                }
            } catch (e: Exception) {
                invokeLater { setStatus("AI summary error: ${e.message}") }
            }
        }
    }

    private fun buildPrompt(pr: PullRequest, files: List<String>, fileContents: String) = """
Summarize the following pull request changes.
Explain:
- What was changed
- Potential risks
- Suggested improvements
- Code quality concerns

PR #${pr.id}: ${pr.title}
Author: ${pr.author.displayName}
Source branch: ${pr.source.branch.name} → ${pr.destination.branch.name}

Changed files (${files.size}):
${files.joinToString("\n") { "  - $it" }}

File contents:
$fileContents
""".trimIndent()

    private fun showSummaryDialog(pr: PullRequest, summary: String) {
        val textArea = JTextArea(summary).apply {
            lineWrap = true; wrapStyleWord = true; isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            border = JBUI.Borders.empty(8)
        }
        JOptionPane.showMessageDialog(
            this,
            JBScrollPane(textArea).apply { preferredSize = Dimension(750, 520) },
            "AI Summary — PR #${pr.id}: ${pr.title}",
            JOptionPane.INFORMATION_MESSAGE
        )
    }

    // -------------------------------------------------------------------------
    // Approve / Decline
    // -------------------------------------------------------------------------

    private fun onApprove() {
        val pr = selectedPr() ?: return
        val (workspace, slug) = bitbucketRepo ?: return
        if (Messages.showYesNoDialog(project,
                "Approve PR #${pr.id}: \"${pr.title}\"?", "Confirm Approve",
                Messages.getQuestionIcon()) != Messages.YES) return
        setStatus("Approving PR #${pr.id}…")
        runInBackground {
            try {
                service.approvePullRequest(workspace, slug, pr.id)
                invokeLater { notify("PR #${pr.id} approved.", NotificationType.INFORMATION); loadPullRequests() }
            } catch (e: Exception) { invokeLater { setStatus("Approve error: ${e.message}") } }
        }
    }

    private fun onDecline() {
        val pr = selectedPr() ?: return
        val (workspace, slug) = bitbucketRepo ?: return
        if (Messages.showYesNoDialog(project,
                "Decline PR #${pr.id}: \"${pr.title}\"?", "Confirm Decline",
                Messages.getWarningIcon()) != Messages.YES) return
        setStatus("Declining PR #${pr.id}…")
        runInBackground {
            try {
                service.declinePullRequest(workspace, slug, pr.id)
                invokeLater { notify("PR #${pr.id} declined.", NotificationType.WARNING); loadPullRequests() }
            } catch (e: Exception) { invokeLater { setStatus("Decline error: ${e.message}") } }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun selectedPr(): PullRequest? {
        return prList.selectedValue ?: run {
            JOptionPane.showMessageDialog(this, "Please select a Pull Request first.",
                "No Selection", JOptionPane.WARNING_MESSAGE)
            null
        }
    }

    private fun setStatus(msg: String) { statusLabel.text = " $msg" }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("PR Review Assistant")
            .createNotification(message, type)
            .notify(project)
    }

    private fun runInBackground(block: () -> Unit) =
        ApplicationManager.getApplication().executeOnPooledThread(block)

    private fun invokeLater(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block)
}

// =============================================================================
// Beautiful Card-style PR List Renderer
// =============================================================================

private class PRCardRenderer : ListCellRenderer<PullRequest> {


    private val DATE_IN  = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val DATE_OUT = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>, value: PullRequest?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val pr = value ?: return JLabel()

        // ── outer card ───────────────────────────────────────────────────────
        val card = JPanel(BorderLayout(0, 2)).apply {
            border = JBUI.Borders.empty(8, 12, 8, 12)
            background = if (isSelected) list.selectionBackground else list.background
        }

        // ── top row: ID badge + title + state pill ───────────────────────────
        val topRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }

        val idBadge = JLabel("#${pr.id}").apply {
            font = Font(font.family, Font.BOLD, 11)
            foreground = JBColor(Color(0x0969DA), Color(0x58A6FF))
            isOpaque = false
        }

        val title = JLabel(pr.title).apply {
            font = Font(font.family, Font.BOLD, 13)
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(idBadge)
            add(title)
        }

        val statePill = StatePill(pr.state)
        topRow.add(titleRow, BorderLayout.CENTER)
        topRow.add(statePill, BorderLayout.EAST)

        // ── bottom row: branches + author + date ─────────────────────────────
        val src  = pr.source.branch.name
        val dest = pr.destination.branch.name
        val branchText = if (src.isNotBlank() && dest.isNotBlank()) "$src  →  $dest" else ""

        val updatedText = pr.updatedOn.runCatching {
            DATE_OUT.format(DATE_IN.parse(this))
        }.getOrElse { pr.updatedOn.take(10) }

        val metaText = buildString {
            if (branchText.isNotBlank()) append(branchText)
            if (pr.author.displayName.isNotBlank()) append("   ·   👤 ${pr.author.displayName}")
            if (updatedText.isNotBlank()) append("   ·   🕒 $updatedText")
            if (pr.commentCount > 0) append("   ·   💬 ${pr.commentCount}")
        }

        val metaLabel = JLabel(metaText).apply {
            font = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
        }

        card.add(topRow,   BorderLayout.CENTER)
        card.add(metaLabel, BorderLayout.SOUTH)

        // ── separator line at the bottom ─────────────────────────────────────
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card, BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }

        return wrapper
    }
}

/** Rounded pill badge for PR state */
private class StatePill(state: String) : JComponent() {
    private val label = state.uppercase()
    private val bg: Color = when (label) {
        "OPEN"     -> JBColor(Color(0xDCFCE7), Color(0x1A3325))
        "MERGED"   -> JBColor(Color(0xF3E8FF), Color(0x2D1F42))
        "DECLINED" -> JBColor(Color(0xFFE4E6), Color(0x3B1219))
        else       -> JBColor(Color(0xF0F0F0), Color(0x2A2A2A))
    }
    private val fg: Color = when (label) {
        "OPEN"     -> JBColor(Color(0x166534), Color(0x3FB950))
        "MERGED"   -> JBColor(Color(0x6B21A8), Color(0xA371F7))
        "DECLINED" -> JBColor(Color(0x9F1239), Color(0xF85149))
        else       -> JBColor.GRAY
    }

    init {
        preferredSize = Dimension(72, 20)
        isOpaque = false
        toolTipText = label
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, height, height)
        g2.color = fg
        g2.font = Font(font.family, Font.BOLD, 10)
        val fm = g2.fontMetrics
        val tx = (width - fm.stringWidth(label)) / 2
        val ty = (height + fm.ascent - fm.descent) / 2
        g2.drawString(label, tx, ty)
    }
}
