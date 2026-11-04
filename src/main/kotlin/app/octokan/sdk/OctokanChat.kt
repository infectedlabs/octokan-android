package app.octokan.sdk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF6B7280)
private val Soft = Color(0xFFF1F2F6)
private val Green = Color(0xFF18794E)

/** Full-screen chat shown by `Octokan.open(context)`. */
class OctokanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OctokanChat(onClose = { finish() }, modifier = Modifier.statusBarsPadding())
            }
        }
    }
}

private sealed class ChatRow(val key: String) {
    class Day(val label: String, id: String) : ChatRow("day-$id")
    class Msg(val m: OctokanMessage, val starts: Boolean, val ends: Boolean, val status: String?) : ChatRow(m.id)
    object Typing : ChatRow("typing")
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

private fun dayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ms, now) -> "Today"
        sameDay(ms, now - 86_400_000L) -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(ms))
    }
}

/** Messages in order, with day dividers, bubble grouping and the delivery status of the last message. */
private fun buildRows(messages: List<OctokanMessage>, seenAt: Long?, typing: Boolean): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    val gap = 5 * 60_000L
    val lastMine = messages.indexOfLast { it.fromVisitor }
    messages.forEachIndexed { i, m ->
        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        val newDay = prev == null || !sameDay(prev.atMillis, m.atMillis)
        if (newDay) rows.add(ChatRow.Day(dayLabel(m.atMillis), m.id))
        val starts = newDay || prev!!.fromVisitor != m.fromVisitor || m.atMillis - prev.atMillis > gap
        val ends = next == null || next.fromVisitor != m.fromVisitor || !sameDay(next.atMillis, m.atMillis) || next.atMillis - m.atMillis > gap
        val status = if (i == lastMine) {
            if (m.pending) "Sending…" else if (seenAt != null && seenAt >= m.atMillis) "✓✓ Seen" else "✓ Sent"
        } else null
        rows.add(ChatRow.Msg(m, starts, ends, status))
    }
    if (typing) rows.add(ChatRow.Typing)
    return rows
}

/** The chat screen. Embed it anywhere in your Compose UI, or use `Octokan.open(context)`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OctokanChat(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val model: OctokanChatViewModel = viewModel()
    DisposableEffect(Unit) {
        model.start()
        onDispose { }
    }

    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val rows = remember(model.messages, model.seenAtMillis, model.typing) { buildRows(model.messages, model.seenAtMillis, model.typing) }
    val initial = (model.title.firstOrNull() ?: 'S').uppercaseChar().toString()

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) list.animateScrollToItem(rows.size) // +1 for the welcome header
    }

    Column(modifier.fillMaxSize().background(Color.White).imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(model.accent, initial, 40, 17)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (model.typing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("typing", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Dots(Green)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                        Text("We usually reply within minutes", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            if (onClose != null) TextButton(onClick = onClose) { Text("Close", color = Muted) }
        }
        Divider(color = Color(0xFFEEF0F4))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(model.accent), contentAlignment = Alignment.Center) { Text("👋", fontSize = 26.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text(model.welcome, color = Color(0xFF374151), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
                    if (model.messages.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("🐞" to "Report a problem" to "Something isn't working: ", "💡" to "Share an idea" to "I have an idea: ", "❓" to "Ask a question" to "I have a question: ").forEach { (head, prefill) ->
                                val (emoji, label) = head
                                Row(
                                    Modifier.clip(RoundedCornerShape(999.dp)).background(Soft).clickable {
                                        draft = prefill
                                        runCatching { focus.requestFocus() }
                                    }.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(emoji)
                                    Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.Day -> DayDivider(row.label)
                    is ChatRow.Typing -> Pop {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(model.accent, initial, 28, 12)
                            Box(Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).background(Soft).padding(horizontal = 16.dp, vertical = 14.dp)) { Dots(Muted) }
                        }
                    }
                    is ChatRow.Msg -> Pop { Bubble(row, model.accent, initial) }
                }
            }
        }

        model.error?.let {
            Text(it, color = Color(0xFFB42318), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Divider(color = Color(0xFFEEF0F4))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Soft).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (draft.isEmpty()) Text("Write a message…", color = Color(0xFF9CA3AF), fontSize = 16.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it.take(2000)
                        if (draft.isNotBlank()) model.notifyTyping()
                    },
                    maxLines = 5,
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(model.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            val canSend = draft.isNotBlank()
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(model.accent).alpha(if (canSend) 1f else 0.35f)
                    .then(if (canSend) Modifier.clickableNoRipple { model.send(draft); draft = "" } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Avatar(color: Color, initial: String, size: Int, textSize: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontSize = textSize.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Divider(Modifier.weight(1f), color = Color(0xFFE5E7EB))
        Text(label.uppercase(), color = Color(0xFF9CA3AF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Divider(Modifier.weight(1f), color = Color(0xFFE5E7EB))
    }
}

@Composable
private fun Bubble(row: ChatRow.Msg, accent: Color, initial: String) {
    val m = row.m
    val mine = m.fromVisitor
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine && row.ends) 6.dp else 18.dp, if (!mine && row.ends) 6.dp else 18.dp)
    Column(Modifier.fillMaxWidth().padding(top = if (row.starts) 12.dp else 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                if (row.ends) Avatar(accent, initial, 28, 12) else Spacer(Modifier.size(28.dp))
            }
            Text(
                m.body,
                color = if (mine) Color.White else Ink,
                fontSize = 16.sp,
                modifier = Modifier.widthIn(max = 290.dp).alpha(if (m.pending) 0.65f else 1f).clip(shape).background(if (mine) accent else Soft).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        if (row.ends || row.status != null) {
            val seen = row.status == "✓✓ Seen"
            Text(
                clock(m.atMillis) + (row.status?.let { " · $it" } ?: ""),
                color = if (seen) Green else Color(0xFF9CA3AF),
                fontSize = 10.sp,
                fontWeight = if (seen) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 3.dp, start = if (mine) 0.dp else 36.dp),
            )
        }
    }
}

/** Springs its content in the first time it appears. */
@Composable
private fun Pop(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(visible = shown, enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 } + scaleIn(tween(260), initialScale = 0.94f)) { content() }
}

/** Three dots that bounce one after another. */
@Composable
private fun Dots(color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(14.dp)) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(450, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.offset(y = (-4 * lift).dp).size(7.dp).clip(CircleShape).background(color.copy(alpha = 0.35f + 0.65f * lift)))
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
