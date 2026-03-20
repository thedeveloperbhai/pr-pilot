package com.vitiquest.peerreview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.vitiquest.peerreview.bitbucket.PullRequest
import java.awt.*
import java.time.format.DateTimeFormatter
import javax.swing.*

// =============================================================================
// PR Card Renderer
// =============================================================================

internal class PRCardRenderer(
    private val fileCountCache: Map<Int, Int>
) : ListCellRenderer<PullRequest> {
    private val DATE_IN  = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val DATE_OUT = DateTimeFormatter.ofPattern("MMM dd, yyyy")

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>, value: PullRequest?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val pr = value ?: return JLabel()
        val card = JPanel(BorderLayout(0, 2)).apply {
            border     = JBUI.Borders.empty(8, 12, 8, 12)
            background = if (isSelected) list.selectionBackground else list.background
        }
        val topRow  = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val idBadge = JLabel("#${pr.id}").apply {
            font       = Font(font.family, Font.BOLD, 11)
            foreground = JBColor(Color(0x0969DA), Color(0x58A6FF))
        }
        val titleLbl = JLabel(pr.title).apply {
            font       = Font(font.family, Font.BOLD, 13)
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false; add(idBadge); add(titleLbl)
        }
        topRow.add(titleRow, BorderLayout.CENTER)
        topRow.add(StatePill(pr.state), BorderLayout.EAST)

        val updatedText = pr.updatedOn.runCatching {
            DATE_OUT.format(DATE_IN.parse(this))
        }.getOrElse { pr.updatedOn.take(10) }

        val meta = buildString {
            val b = pr.source.branch.name; val d = pr.destination.branch.name
            if (b.isNotBlank() && d.isNotBlank()) append("$b  →  $d")
            if (pr.author.displayName.isNotBlank()) append("   ·   👤 ${pr.author.displayName}")
            if (updatedText.isNotBlank())           append("   ·   🕒 $updatedText")
            if (pr.commentCount > 0)                append("   ·   💬 ${pr.commentCount}")
            fileCountCache[pr.id]?.let { n ->
                append("   ·   📄 $n file${if (n == 1) "" else "s"}")
            }
        }
        val metaLbl = JLabel(meta).apply {
            font = Font(font.family, Font.PLAIN, 11); foreground = JBColor.GRAY
        }
        card.add(topRow,   BorderLayout.CENTER)
        card.add(metaLbl,  BorderLayout.SOUTH)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card,        BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }
}

// =============================================================================
// File Entry Renderer — filename + status on row 1, package/dir on row 2
// =============================================================================

internal class FileEntryRenderer : ListCellRenderer<FileDiffEntry> {

    override fun getListCellRendererComponent(
        list: JList<out FileDiffEntry>, value: FileDiffEntry?, index: Int,
        isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val entry = value ?: return JLabel()

        val bg = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground

        val ext      = entry.newPath.substringAfterLast('.', "")
        val fileIcon = FileTypeManager.getInstance().getFileTypeByExtension(ext).icon
            ?: AllIcons.FileTypes.Unknown

        val (statusIcon, statusColor, statusBg) = when (entry.statusTag) {
            "ADDED"   -> Triple(AllIcons.General.Add,     JBColor(Color(0x166534), Color(0x3FB950)), JBColor(Color(0xDCFCE7), Color(0x1A3325)))
            "DELETED" -> Triple(AllIcons.General.Remove,  JBColor(Color(0x9F1239), Color(0xF85149)), JBColor(Color(0xFFE4E6), Color(0x3B1219)))
            "RENAMED" -> Triple(AllIcons.Actions.Forward, JBColor(Color(0x0550AE), Color(0x58A6FF)), JBColor(Color(0xDEF0FF), Color(0x0D2847)))
            else      -> Triple(AllIcons.Actions.Edit,    JBColor(Color(0x953800), Color(0xD29922)), JBColor(Color(0xFFF8E7), Color(0x2E1F00)))
        }

        val displayPath = entry.displayLabel
        val fileName    = displayPath.substringAfterLast('/').substringAfterLast('\\')
        val dirPart     = displayPath.removeSuffix(fileName).trimEnd('/', '\\')

        val fileNameLabel = JLabel(fileName).apply {
            font        = Font(font.family, Font.BOLD, 13)
            foreground  = fg
            icon        = fileIcon
            iconTextGap = 6
        }

        val statusPill = object : JLabel(entry.statusTag) {
            init {
                icon        = statusIcon
                iconTextGap = 4
                font        = Font(font.family, Font.BOLD, 10)
                foreground  = statusColor
                border      = JBUI.Borders.empty(2, 7, 2, 7)
                isOpaque    = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = statusBg
                g2.fillRoundRect(0, 0, width, height, height, height)
                super.paintComponent(g)
            }
        }

        val topRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            add(fileNameLabel, BorderLayout.CENTER)
            val pillWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false; add(statusPill)
            }
            add(pillWrap, BorderLayout.EAST)
        }

        val dirLabel = JLabel(if (dirPart.isNotBlank()) dirPart else "· root").apply {
            font       = Font(font.family, Font.PLAIN, 11)
            foreground = JBColor.GRAY
            border     = JBUI.Borders.empty(1, 22, 0, 0)
        }

        val card = JPanel(BorderLayout(0, 3)).apply {
            border     = JBUI.Borders.empty(8, 12, 8, 12)
            background = bg
            isOpaque   = true
            add(topRow,   BorderLayout.CENTER)
            add(dirLabel, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card,         BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }
}

// =============================================================================
// State Pill — reused by PRCardRenderer and the breadcrumb bar
// =============================================================================

internal class StatePill(state: String) : JComponent() {
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
    init { preferredSize = Dimension(72, 20); isOpaque = false; toolTipText = label }
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bg; g2.fillRoundRect(0, 0, width, height, height, height)
        g2.color = fg; g2.font = Font(font.family, Font.BOLD, 10)
        val fm = g2.fontMetrics
        g2.drawString(label, (width - fm.stringWidth(label)) / 2, (height + fm.ascent - fm.descent) / 2)
    }
}
