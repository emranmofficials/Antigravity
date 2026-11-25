package id.vern.antigravity.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.vern.antigravity.R
import id.vern.antigravity.ui.theme.AntigravityGreen
import id.vern.antigravity.ui.theme.AntigravityRed

enum class DiffLineType {
    UNCHANGED,
    ADDED,
    REMOVED
}

data class DiffLine(
    val type: DiffLineType,
    val oldLineNum: Int?,
    val newLineNum: Int?,
    val text: String,
    /** Character range inside [text] that differs from the paired line, when known. */
    val changedRange: IntRange? = null
)

const val DEFAULT_DIFF_MAX_LINES = 400
private const val MAX_LCS_LINES = 800
private const val MAX_LCS_CELLS = 640_000L
private const val MAX_WORD_HIGHLIGHT_CHARS = 3000

/**
 * Pure LCS line diff (JUnit-friendly). Large inputs fall back to a linear prefix/suffix diff
 * instead of allocating an O(n*m) table.
 */
fun computeDiff(
    oldText: String,
    newText: String,
    maxLines: Int = DEFAULT_DIFF_MAX_LINES
): List<DiffLine> {
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val withinBudget = maxLines > 0 &&
        oldLines.size.toLong() <= minOf(maxLines.toLong(), MAX_LCS_LINES.toLong()) &&
        newLines.size.toLong() <= minOf(maxLines.toLong(), MAX_LCS_LINES.toLong()) &&
        oldLines.size.toLong() * newLines.size <= MAX_LCS_CELLS
    val raw = if (withinBudget) computeLcsDiff(oldLines, newLines) else computePrefixSuffixDiff(oldLines, newLines)
    return annotateWordHighlights(raw)
}

/** Pure helper: highlights the differing word span of a replaced line pair. */
fun wordDiffRanges(oldLine: String, newLine: String): Pair<IntRange?, IntRange?> {
    if (oldLine.length > MAX_WORD_HIGHLIGHT_CHARS || newLine.length > MAX_WORD_HIGHLIGHT_CHARS) {
        return null to null
    }
    val maxPrefix = minOf(oldLine.length, newLine.length)
    var start = 0
    while (start < maxPrefix && oldLine[start] == newLine[start]) start++
    var oldEnd = oldLine.length
    var newEnd = newLine.length
    while (oldEnd > start && newEnd > start && oldLine[oldEnd - 1] == newLine[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return expandToWord(text = oldLine, from = start, until = oldEnd) to
        expandToWord(text = newLine, from = start, until = newEnd)
}

private fun expandToWord(text: String, from: Int, until: Int): IntRange? {
    if (until <= from) return null
    var start = from
    var end = until
    while (start > 0 && !isWordBoundary(text[start - 1])) start--
    while (end < text.length && !isWordBoundary(text[end])) end++
    return if (end > start) start until end else null
}

private fun isWordBoundary(char: Char): Boolean = char.isWhitespace() || char == ',' || char == '.' ||
    char == ';' || char == ':' || char == '(' || char == ')' || char == '{' || char == '}' ||
    char == '[' || char == ']' || char == '"' || char == '\''

private fun computeLcsDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
    val n = oldLines.size
    val m = newLines.size
    val width = m + 1
    val lcs = IntArray((n + 1) * width)
    for (i in n - 1 downTo 0) {
        val row = i * width
        val nextRow = (i + 1) * width
        for (j in m - 1 downTo 0) {
            lcs[row + j] = if (oldLines[i] == newLines[j]) {
                lcs[nextRow + j + 1] + 1
            } else {
                maxOf(lcs[nextRow + j], lcs[row + j + 1])
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        if (oldLines[i] == newLines[j]) {
            result.add(DiffLine(DiffLineType.UNCHANGED, i + 1, j + 1, oldLines[i]))
            i++
            j++
        } else if (lcs[(i + 1) * width + j] >= lcs[i * width + j + 1]) {
            result.add(DiffLine(DiffLineType.REMOVED, i + 1, null, oldLines[i]))
            i++
        } else {
            result.add(DiffLine(DiffLineType.ADDED, null, j + 1, newLines[j]))
            j++
        }
    }
    while (i < n) {
        result.add(DiffLine(DiffLineType.REMOVED, i + 1, null, oldLines[i]))
        i++
    }
    while (j < m) {
        result.add(DiffLine(DiffLineType.ADDED, null, j + 1, newLines[j]))
        j++
    }
    return result
}

/** Linear fallback for oversized files: keep common head/tail, replace the middle as a block. */
private fun computePrefixSuffixDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    var prefix = 0
    val commonPrefix = minOf(oldLines.size, newLines.size)
    while (prefix < commonPrefix && oldLines[prefix] == newLines[prefix]) {
        result.add(DiffLine(DiffLineType.UNCHANGED, prefix + 1, prefix + 1, oldLines[prefix]))
        prefix++
    }
    var oldEnd = oldLines.size
    var newEnd = newLines.size
    while (oldEnd > prefix && newEnd > prefix && oldLines[oldEnd - 1] == newLines[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    for (i in prefix until oldEnd) {
        result.add(DiffLine(DiffLineType.REMOVED, i + 1, null, oldLines[i]))
    }
    for (j in prefix until newEnd) {
        result.add(DiffLine(DiffLineType.ADDED, null, j + 1, newLines[j]))
    }
    for (k in oldEnd until oldLines.size) {
        val offset = k - oldEnd
        result.add(DiffLine(DiffLineType.UNCHANGED, k + 1, newEnd + offset + 1, oldLines[k]))
    }
    return result
}

private fun annotateWordHighlights(lines: List<DiffLine>): List<DiffLine> {
    val result = lines.toMutableList()
    var idx = 0
    while (idx < result.size) {
        if (result[idx].type != DiffLineType.REMOVED) {
            idx++
            continue
        }
        var removedEnd = idx
        while (removedEnd < result.size && result[removedEnd].type == DiffLineType.REMOVED) removedEnd++
        var addedEnd = removedEnd
        while (addedEnd < result.size && result[addedEnd].type == DiffLineType.ADDED) addedEnd++
        val pairCount = minOf(removedEnd - idx, addedEnd - removedEnd)
        for (k in 0 until pairCount) {
            val removed = result[idx + k]
            val added = result[removedEnd + k]
            val (removedRange, addedRange) = wordDiffRanges(removed.text, added.text)
            if (removedRange != null || addedRange != null) {
                result[idx + k] = removed.copy(changedRange = removedRange)
                result[removedEnd + k] = added.copy(changedRange = addedRange)
            }
        }
        idx = if (addedEnd > idx) addedEnd else removedEnd
    }
    return result
}

/** Pure helper for git unified-diff output (headers/hunks kept as context lines). */
fun parseUnifiedDiff(diff: String, maxLines: Int = DEFAULT_DIFF_MAX_LINES): List<DiffLine> {
    val result = mutableListOf<DiffLine>()
    val hunkRegex = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    var oldNum = 1
    var newNum = 1
    for (line in diff.lines()) {
        when {
            line.startsWith("@@") -> {
                val match = hunkRegex.find(line)
                if (match != null) {
                    oldNum = match.groupValues[1].toIntOrNull() ?: 1
                    newNum = match.groupValues[3].toIntOrNull() ?: 1
                }
                result.add(DiffLine(DiffLineType.UNCHANGED, null, null, line))
            }
            line.startsWith("+++") || line.startsWith("---") ||
                line.startsWith("diff --git") || line.startsWith("index ") ||
                line.startsWith("\\") -> {
                result.add(DiffLine(DiffLineType.UNCHANGED, null, null, line))
            }
            line.startsWith("+") -> {
                result.add(DiffLine(DiffLineType.ADDED, null, newNum, line.drop(1)))
                newNum++
            }
            line.startsWith("-") -> {
                result.add(DiffLine(DiffLineType.REMOVED, oldNum, null, line.drop(1)))
                oldNum++
            }
            line.startsWith(" ") -> {
                result.add(DiffLine(DiffLineType.UNCHANGED, oldNum, newNum, line.drop(1)))
                oldNum++
                newNum++
            }
            else -> result.add(DiffLine(DiffLineType.UNCHANGED, oldNum, newNum, line))
        }
        if (result.size >= maxLines) break
    }
    return annotateWordHighlights(result)
}

@Composable
fun DiffViewer(
    fileName: String,
    originalText: String,
    modifiedText: String,
    onApplyChanges: () -> Unit,
    onDiscardChanges: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val diffLines = remember(originalText, modifiedText) {
        computeDiff(originalText, modifiedText)
    }

    val additions = diffLines.count { it.type == DiffLineType.ADDED }
    val deletions = diffLines.count { it.type == DiffLineType.REMOVED }

    val hScroll = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Diff: $fileName",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "+$additions", color = AntigravityGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "-$deletions", color = AntigravityRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = onDiscardChanges,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.diff_btn_discard), fontSize = 12.sp)
                    }

                    Button(
                        onClick = onApplyChanges,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AntigravityGreen),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.diff_btn_apply), fontSize = 12.sp)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .horizontalScroll(hScroll)
        ) {
            items(diffLines) { line ->
                val bg = when (line.type) {
                    DiffLineType.ADDED -> Color(0xFF1B3C2B)
                    DiffLineType.REMOVED -> Color(0xFF3D1E24)
                    DiffLineType.UNCHANGED -> Color.Transparent
                }

                val textColor = when (line.type) {
                    DiffLineType.ADDED -> Color(0xFF7EE787)
                    DiffLineType.REMOVED -> Color(0xFFFFA198)
                    DiffLineType.UNCHANGED -> Color(0xFFC9D1D9)
                }

                val marker = when (line.type) {
                    DiffLineType.ADDED -> "+"
                    DiffLineType.REMOVED -> "-"
                    DiffLineType.UNCHANGED -> " "
                }

                val annotated = remember(line) {
                    buildAnnotatedString {
                        val range = line.changedRange
                        if (range != null && range.first >= 0 && range.last < line.text.length && range.last >= range.first) {
                            append(line.text.substring(0, range.first))
                            withStyle(
                                SpanStyle(
                                    background = if (line.type == DiffLineType.ADDED) Color(0xFF2E7148) else Color(0xFF8C3A44),
                                    fontWeight = FontWeight.Bold
                                )
                            ) {
                                append(line.text.substring(range.first, range.last + 1))
                            }
                            append(line.text.substring(range.last + 1))
                        } else {
                            append(line.text)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Text(
                        text = line.oldLineNum?.toString() ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF6E7681),
                        modifier = Modifier.width(36.dp)
                    )
                    Text(
                        text = line.newLineNum?.toString() ?: "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF6E7681),
                        modifier = Modifier.width(36.dp)
                    )
                    Text(
                        text = marker,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier.width(16.dp)
                    )
                    Text(
                        text = annotated,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = textColor
                    )
                }
            }
        }
    }
}
