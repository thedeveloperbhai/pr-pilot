package com.vitiquest.peerreview.ui

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vitiquest.peerreview.ai.AiReviewResult
import com.vitiquest.peerreview.ai.InlineComment
import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.bitbucket.PullRequestService
import com.vitiquest.peerreview.jira.JiraIntegrationService
import com.vitiquest.peerreview.jira.ReviewOutcome
import com.vitiquest.peerreview.settings.GitProvider
import com.vitiquest.peerreview.utils.RepoInfo
import java.awt.*
import javax.swing.*
import javax.swing.Timer

/**
 * Builds and shows the AI review dialog (summary tab + inline-comments tab).
 * All panel-level side-effects are injected as lambdas to keep this class
 * decoupled from [PRToolWindowPanel].
 */
class AiSummaryDialog(
    private val parent: Component,
    private val project: Project,
    private val service: PullRequestService,
    private val jiraService: JiraIntegrationService,
    private val onRegenerate: (PullRequest, RepoInfo) -> Unit,
    private val onSetStatus: (String) -> Unit,
    private val onSetBusy: (Boolean) -> Unit,
    private val onNotify: (String, NotificationType) -> Unit,
    private val onLoadPullRequests: () -> Unit,
    private val runInBackground: (() -> Unit) -> Unit,
    private val invokeLater: (() -> Unit) -> Unit
) {

    fun show(pr: PullRequest, result: AiReviewResult, info: RepoInfo) {
        val summary  = result.summary
        val comments = result.inlineComments

        // ── Theme colours ─────────────────────────────────────────────────────
        val uiFont  = UIUtil.getLabelFont()
        val bgColor = UIUtil.getPanelBackground()
        val fgColor = UIUtil.getLabelForeground()
        val isDark  = !JBColor.isBright()

        val cardBg  = if (isDark) JBColor(Color(0x3C3F41), Color(0x3C3F41))
                      else        JBColor(Color(0xFFFFFF), Color(0xFFFFFF))
        val codeBg  = if (isDark) "#2B2B2B" else "#F5F5F5"
        val fg      = "#${fgColor.toHex()}"
        val bg      = "#${bgColor.toHex()}"
        val fontPt  = uiFont.size
        val fontFam = uiFont.family

        // ── CSS for HTMLEditorKit ─────────────────────────────────────────────
        val css = buildString {
            append("body { font-family: $fontFam; font-size: ${fontPt}pt; color: $fg; background-color: $bg; margin: 16px 20px; }")
            append("h1 { font-size: ${(fontPt * 1.7).toInt()}pt; color: $fg; margin-top: 0px; margin-bottom: 8px; }")
            append("h2 { font-size: ${(fontPt * 1.35).toInt()}pt; color: $fg; margin-top: 18px; margin-bottom: 4px; border-bottom: 1px solid #888; padding-bottom: 2px; }")
            append("h3 { font-size: ${(fontPt * 1.1).toInt()}pt; color: $fg; margin-top: 12px; margin-bottom: 2px; }")
            append("p  { margin-top: 5px; margin-bottom: 5px; line-height: 1.5; }")
            append("code { font-family: monospace; font-size: ${fontPt - 1}pt; background-color: $codeBg; color: $fg; padding-left: 3px; padding-right: 3px; }")
            append("pre  { font-family: monospace; font-size: ${fontPt - 1}pt; background-color: $codeBg; color: $fg; padding: 10px; margin-top: 8px; margin-bottom: 8px; }")
            append("table { border-collapse: collapse; width: 100%; margin-top: 10px; margin-bottom: 10px; }")
            append("th { font-weight: bold; background-color: $codeBg; color: $fg; padding: 6px 10px; border: 1px solid #555; }")
            append("td { color: $fg; padding: 5px 10px; border: 1px solid #555; }")
            append("ul { margin-left: 22px; margin-top: 4px; margin-bottom: 4px; }")
            append("ol { margin-left: 22px; margin-top: 4px; margin-bottom: 4px; }")
            append("li { margin-bottom: 3px; line-height: 1.5; }")
            append("strong { font-weight: bold; }")
            append("em { font-style: italic; }")
            append("blockquote { color: #888888; margin-left: 16px; border-left: 3px solid #888; padding-left: 8px; }")
            append("hr { border: none; border-top: 1px solid #555; margin-top: 12px; margin-bottom: 12px; }")
        }

        // ── Tab 1: Summary ────────────────────────────────────────────────────
        val kit = javax.swing.text.html.HTMLEditorKit()
        kit.styleSheet.addRule(css)
        val doc = kit.createDefaultDocument() as javax.swing.text.html.HTMLDocument

        val textPane = JTextPane().apply {
            editorKit  = kit
            document   = doc
            isEditable = false
            background = bgColor
            border     = JBUI.Borders.empty()
        }
        textPane.text = "<html><body>${MarkdownUtils.toHtml(summary)}</body></html>"
        textPane.caretPosition = 0

        val summaryScroll = JBScrollPane(textPane).apply {
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }

        // ── Tab 2: Inline Comments ─────────────────────────────────────────────
        val commentCheckboxes = mutableListOf<JCheckBox>()
        val commentTextAreas  = mutableListOf<JTextArea>()

        val inlinePanel = JPanel().apply {
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgColor
            border     = JBUI.Borders.empty(12, 16, 12, 16)
        }

        if (comments.isEmpty()) {
            val emptyBox = JPanel(BorderLayout()).apply {
                isOpaque = false
                border   = JBUI.Borders.empty(40, 0)
            }
            val emptyLbl = JBLabel("✅  No inline comments — the AI found no specific line-level issues.").apply {
                foreground          = JBColor.GRAY
                font                = Font(font.family, Font.ITALIC, fontPt)
                horizontalAlignment = SwingConstants.CENTER
            }
            emptyBox.add(emptyLbl, BorderLayout.CENTER)
            inlinePanel.add(emptyBox)
        } else {
            for ((_, ic) in comments.withIndex()) {
                inlinePanel.add(buildCommentCard(ic, isDark, bgColor, fgColor, cardBg, fontPt, commentCheckboxes, commentTextAreas))
                inlinePanel.add(Box.createVerticalStrut(4))
            }
        }

        val inlineScroll = JBScrollPane(inlinePanel).apply {
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border     = null
            background = bgColor
        }

        // ── Tabs ──────────────────────────────────────────────────────────────
        val countLabel = if (comments.isEmpty()) "Inline Comments" else "Inline Comments  (${comments.size})"
        val tabbedPane = JTabbedPane().apply {
            addTab("📋  Summary",     summaryScroll)
            addTab("💬  $countLabel", inlineScroll)
            background = bgColor
            font       = Font(font.family, Font.PLAIN, fontPt)
        }
        tabbedPane.preferredSize = Dimension(1020, 700)

        // ── Bottom action bar ─────────────────────────────────────────────────
        val isGH         = info.provider == GitProvider.GITHUB
        val isOpen       = pr.state.uppercase() == "OPEN"
        val declineLabel = if (isGH) "Close PR" else "Decline PR"

        val regenBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText         = "Regenerate AI summary (clears cache)"
            isBorderPainted     = false
            isContentAreaFilled = false
            preferredSize       = Dimension(28, 28)
        }
        val copyBtn = JButton("Copy Markdown").apply {
            font = Font(font.family, Font.PLAIN, 11)
        }
        val postInlineBtn = JButton("💬  Post Inline Comments").apply {
            font        = Font(font.family, Font.BOLD, 12)
            foreground  = JBColor(Color(0x0550AE), Color(0x58A6FF))
            isVisible   = isOpen && comments.isNotEmpty()
            toolTipText = "Post checked inline comments to PR #${pr.id}"
        }
        val reqChangesBtn = JButton("🔁  Request Changes").apply {
            font        = Font(font.family, Font.BOLD, 12)
            foreground  = JBColor(Color(0xD97706), Color(0xF5C518))
            isVisible   = isOpen
            toolTipText = "Post inline comments and request changes from the PR author"
        }
        val approveBtn = JButton("✅  Approve").apply {
            font        = Font(font.family, Font.BOLD, 12)
            foreground  = JBColor(Color(0x16A34A), Color(0x4CAF50))
            isVisible   = isOpen
            toolTipText = "Post AI summary as comment, then approve this PR"
        }
        val mergeBtn = JButton("🔀  Merge").apply {
            font        = Font(font.family, Font.BOLD, 12)
            foreground  = JBColor(Color(0x0550AE), Color(0x58A6FF))
            isVisible   = isOpen
            toolTipText = "Post AI summary as comment, then merge this PR"
        }
        val declineBtn = JButton("❌  $declineLabel").apply {
            font        = Font(font.family, Font.BOLD, 12)
            foreground  = JBColor(Color(0x9F1239), Color(0xF85149))
            isVisible   = isOpen
            toolTipText = "Post AI summary as comment, then ${declineLabel.lowercase()} this PR"
        }
        val closeDialogBtn = JButton("Close").apply {
            font = Font(font.family, Font.PLAIN, 12)
        }

        val leftBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 6)).apply {
            isOpaque = false
            add(regenBtn); add(copyBtn)
        }
        val rightBar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
            isOpaque = false
            if (isOpen) {
                if (comments.isNotEmpty()) add(postInlineBtn)
                add(reqChangesBtn)
                add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 26) })
                add(approveBtn); add(mergeBtn); add(declineBtn)
                add(JSeparator(JSeparator.VERTICAL).apply { preferredSize = Dimension(1, 26) })
            }
            add(closeDialogBtn)
        }

        val southBar = JPanel(BorderLayout()).apply {
            border     = javax.swing.border.MatteBorder(1, 0, 0, 0, JBColor.border())
            isOpaque   = true
            background = bgColor
            add(leftBar,  BorderLayout.WEST)
            add(rightBar, BorderLayout.EAST)
        }

        // ── Dialog ────────────────────────────────────────────────────────────
        val dialog = JDialog(
            SwingUtilities.getWindowAncestor(parent),
            "🤖  AI Review — PR #${pr.id}: ${pr.title}",
            java.awt.Dialog.ModalityType.APPLICATION_MODAL
        ).apply {
            contentPane = JPanel(BorderLayout()).apply {
                add(tabbedPane, BorderLayout.CENTER)
                add(southBar,   BorderLayout.SOUTH)
            }
            pack()
            setLocationRelativeTo(parent)
            minimumSize = Dimension(780, 540)
            isResizable = true
        }

        // ── Helper: collect selected (possibly edited) inline comments ─────────
        fun selectedInlineComments(): List<InlineComment> = comments.indices
            .filter { i -> commentCheckboxes.getOrNull(i)?.isSelected == true }
            .map    { i ->
                val original   = comments[i]
                val editedText = commentTextAreas.getOrNull(i)?.text?.trim() ?: original.comment
                original.copy(comment = editedText.ifBlank { original.comment })
            }

        // ── Wire button actions ───────────────────────────────────────────────

        copyBtn.addActionListener {
            copyToClipboard(summary)
            copyBtn.text = "✓ Copied!"
            Timer(1500) { copyBtn.text = "Copy Markdown" }.also { t -> t.isRepeats = false; t.start() }
        }

        regenBtn.addActionListener {
            dialog.dispose()
            onRegenerate(pr, info)
        }

        closeDialogBtn.addActionListener { dialog.dispose() }

        postInlineBtn.addActionListener {
            val selected = selectedInlineComments()
            if (selected.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "No inline comments selected. Check at least one comment.", "Nothing to Post", JOptionPane.INFORMATION_MESSAGE)
                return@addActionListener
            }
            val ok = JOptionPane.showConfirmDialog(
                dialog,
                "Post ${selected.size} inline comment(s) on PR #${pr.id}?",
                "Confirm Post",
                JOptionPane.OK_CANCEL_OPTION
            )
            if (ok != JOptionPane.OK_OPTION) return@addActionListener
            dialog.dispose()
            onSetStatus("Posting ${selected.size} inline comment(s) on PR #${pr.id}…")
            onSetBusy(true)
            runInBackground {
                try {
                    service.postInlineComments(info.owner, info.repoSlug, pr.id, selected)
                    val jiraMsg = runCatching {
                        jiraService.syncReviewOutcome(pr, ReviewOutcome.CHANGES_REQUESTED, summary).userMessage()
                    }.getOrElse { "JIRA sync failed: ${it.message}" }
                    invokeLater {
                        onSetBusy(false)
                        onNotify(buildString {
                            append("Posted ${selected.size} inline comment(s) on PR #${pr.id}.")
                            if (!jiraMsg.isNullOrBlank()) append(" $jiraMsg")
                        }, NotificationType.INFORMATION)
                        onSetStatus("Inline comments posted.")
                    }
                } catch (e: Exception) {
                    invokeLater {
                        onSetBusy(false)
                        onSetStatus("Failed to post inline comments.")
                        Messages.showErrorDialog(project, e.message ?: "Unknown error", "Post Inline Comments Failed")
                    }
                }
            }
        }

        reqChangesBtn.addActionListener {
            val selected = selectedInlineComments()
            val confirmMsg = buildString {
                append("Request changes on PR #${pr.id}?")
                if (selected.isNotEmpty()) append("\n\nThis will post ${selected.size} inline comment(s) and\nmark the PR as 'Changes Requested'.")
                else append("\n\nNo inline comments are selected — only a top-level 'Changes Requested' comment will be posted.")
            }
            val ok = JOptionPane.showConfirmDialog(dialog, confirmMsg, "Confirm Request Changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
            if (ok != JOptionPane.OK_OPTION) return@addActionListener
            dialog.dispose()
            onSetStatus("Requesting changes on PR #${pr.id}…")
            onSetBusy(true)
            runInBackground {
                try {
                    service.requestChanges(info.owner, info.repoSlug, pr.id, summary, selected)
                    val jiraMsg = runCatching {
                        jiraService.syncReviewOutcome(pr, ReviewOutcome.CHANGES_REQUESTED, summary).userMessage()
                    }.getOrElse { "JIRA sync failed: ${it.message}" }
                    invokeLater {
                        onSetBusy(false)
                        onNotify(buildString {
                            append("Changes requested on PR #${pr.id}.")
                            if (!jiraMsg.isNullOrBlank()) append(" $jiraMsg")
                        }, NotificationType.WARNING)
                        onLoadPullRequests()
                    }
                } catch (e: Exception) {
                    invokeLater {
                        onSetBusy(false)
                        onSetStatus("Request changes failed.")
                        Messages.showErrorDialog(project, e.message ?: "Unknown error", "Request Changes Failed")
                    }
                }
            }
        }

        fun executeWithOptionalComment(actionLabel: String, reviewOutcome: ReviewOutcome, action: () -> Unit) {
            val choice = JOptionPane.showOptionDialog(
                dialog,
                "Post AI summary as a comment on PR #${pr.id} before ${actionLabel.lowercase()}ing?",
                "Confirm $actionLabel",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                arrayOf("Post & $actionLabel", "$actionLabel only", "Cancel"),
                "Post & $actionLabel"
            )
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return
            dialog.dispose()
            onSetStatus("${actionLabel}ing PR #${pr.id}…")
            runInBackground {
                try {
                    if (choice == 0) service.postComment(info.owner, info.repoSlug, pr.id, summary)
                    action()
                    val jiraMsg = runCatching {
                        jiraService.syncReviewOutcome(
                            pr      = pr,
                            outcome = reviewOutcome,
                            summary = if (reviewOutcome == ReviewOutcome.DECLINED) summary else null
                        ).userMessage()
                    }.getOrElse { "JIRA sync failed: ${it.message}" }
                    invokeLater {
                        onNotify(buildString {
                            append("PR #${pr.id} ${actionLabel.lowercase()}d.")
                            if (!jiraMsg.isNullOrBlank()) append(" $jiraMsg")
                        }, NotificationType.INFORMATION)
                        onLoadPullRequests()
                    }
                } catch (e: Exception) {
                    invokeLater {
                        onSetStatus("$actionLabel failed.")
                        Messages.showErrorDialog(project, e.message ?: "Unknown error", "$actionLabel PR #${pr.id} Failed")
                    }
                }
            }
        }

        approveBtn.addActionListener  { executeWithOptionalComment("Approve", ReviewOutcome.APPROVED)  { service.approvePullRequest(info.owner,  info.repoSlug, pr.id) } }
        mergeBtn.addActionListener    { executeWithOptionalComment("Merge",   ReviewOutcome.MERGED)    { service.mergePullRequest(info.owner,    info.repoSlug, pr.id) } }
        declineBtn.addActionListener  { executeWithOptionalComment(if (isGH) "Close" else "Decline", ReviewOutcome.DECLINED) { service.declinePullRequest(info.owner, info.repoSlug, pr.id) } }

        dialog.isVisible = true
    }

    // ── Inline-comment card builder ───────────────────────────────────────────

    private fun buildCommentCard(
        ic: InlineComment,
        isDark: Boolean,
        bgColor: Color,
        fgColor: Color,
        cardBg: Color,
        fontPt: Int,
        checkboxList: MutableList<JCheckBox>,
        textAreaList: MutableList<JTextArea>
    ): JComponent {
        val sev         = ic.severity.lowercase()
        val sevLabelStr = when (sev) { "critical" -> "🔴  CRITICAL"; "warning" -> "🟡  WARNING"; else -> "🟢  SUGGESTION" }
        val sevColor    = when (sev) {
            "critical" -> JBColor(Color(0xC0392B), Color(0xFF6B6B))
            "warning"  -> JBColor(Color(0xB45309), Color(0xF5C518))
            else       -> JBColor(Color(0x166534), Color(0x4CAF50))
        }
        val sevBg       = when (sev) {
            "critical" -> JBColor(Color(0xFFF0EE), Color(0x3D1A1A))
            "warning"  -> JBColor(Color(0xFFFBEB), Color(0x2E2000))
            else       -> JBColor(Color(0xF0FFF4), Color(0x0D2E18))
        }
        val accentColor = when (sev) {
            "critical" -> if (isDark) Color(0xFF6B6B) else Color(0xC0392B)
            "warning"  -> if (isDark) Color(0xF5C518) else Color(0xB45309)
            else       -> if (isDark) Color(0x4CAF50) else Color(0x166534)
        }

        val sevPill = object : JLabel(sevLabelStr) {
            init {
                font       = Font(font.family, Font.BOLD, fontPt - 2)
                foreground = sevColor
                border     = JBUI.Borders.empty(2, 8, 2, 8)
                isOpaque   = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = sevBg; g2.fillRoundRect(0, 0, width, height, 10, 10)
                super.paintComponent(g)
            }
        }

        val fileLabel = JBLabel(ic.file).apply {
            font       = Font(Font.MONOSPACED, Font.PLAIN, fontPt - 1)
            foreground = fgColor
        }

        val lineBadge = object : JLabel("  :${ic.line}  ") {
            init {
                font       = Font(Font.MONOSPACED, Font.BOLD, fontPt - 2)
                foreground = JBColor(Color(0x0550AE), Color(0x58A6FF))
                border     = JBUI.Borders.empty(1, 4)
                isOpaque   = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(0xDEF0FF), Color(0x0D2847))
                g2.fillRoundRect(0, 0, width, height, 8, 8)
                super.paintComponent(g)
            }
        }

        val checkbox = JCheckBox("Post this comment").apply {
            isSelected  = true
            background  = cardBg
            font        = Font(font.family, Font.PLAIN, fontPt - 2)
            foreground  = JBColor.GRAY
            toolTipText = "Uncheck to exclude from posting"
        }
        checkboxList.add(checkbox)

        val textArea = JTextArea(ic.comment).apply {
            lineWrap      = true
            wrapStyleWord = true
            background    = if (isDark) Color(0x2B2B2B) else Color(0xF8F8F8)
            foreground    = fgColor
            font          = Font(font.family, Font.PLAIN, fontPt)
            border        = JBUI.Borders.empty(8, 10, 8, 10)
            rows          = 3
        }
        textAreaList.add(textArea)

        val textAreaScroll = JBScrollPane(textArea).apply {
            verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = javax.swing.border.LineBorder(JBColor.border(), 1, true)
        }

        val fileRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false; add(fileLabel); add(lineBadge)
        }
        val headerRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(sevPill,  BorderLayout.WEST)
            add(checkbox, BorderLayout.EAST)
        }
        val innerContent = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border   = JBUI.Borders.empty(10, 14, 12, 14)
            add(headerRow, BorderLayout.NORTH)
            add(fileRow,   BorderLayout.CENTER)
        }
        val cardContent = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(innerContent, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border   = JBUI.Borders.empty(0, 14, 12, 14)
                add(textAreaScroll, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }

        return object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = cardBg;     g2.fillRoundRect(0, 0, width, height, 8, 8)
                g2.color = accentColor; g2.fillRoundRect(0, 0, 4, height, 4, 4)
            }
        }.apply {
            isOpaque = false
            border   = JBUI.Borders.empty(0, 0, 12, 0)
            add(cardContent, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String) {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    private fun java.awt.Color.toHex(): String =
        "%02X%02X%02X".format(red, green, blue)
}
