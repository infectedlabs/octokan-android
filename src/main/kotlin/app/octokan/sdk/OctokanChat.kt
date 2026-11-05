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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.URL
import java.text.SimpleDateFormat
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            if (m.pending) "sending" else if (seenAt != null && seenAt >= m.atMillis) "seen" else "sent"
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
    val L = remember { OctokanStrings.forLanguage(Locale.getDefault().language) }
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
            Avatar(model.accent, initial, 40, 17, model.logo)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(model.title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (model.typing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(L.getValue("typing"), color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Dots(Green)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF22C55E)))
                        Text(L.getValue("hint"), color = Muted, fontSize = 12.sp)
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
                            listOf("🐞" to L.getValue("report") to L.getValue("p_report"), "💡" to L.getValue("idea") to L.getValue("p_idea"), "❓" to L.getValue("question") to L.getValue("p_question")).forEach { (head, prefill) ->
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
                            Avatar(model.accent, initial, 28, 12, model.logo)
                            Box(Modifier.clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)).background(Soft).padding(horizontal = 16.dp, vertical = 14.dp)) { Dots(Muted) }
                        }
                    }
                    is ChatRow.Msg -> Pop { Bubble(row, model.accent, initial, model.logo) }
                }
            }
        }

        model.error?.let {
            Text(it, color = Color(0xFFB42318), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Divider(color = Color(0xFFEEF0F4))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Soft).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (draft.isEmpty()) Text(L.getValue("ph"), color = Color(0xFF9CA3AF), fontSize = 16.sp)
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
private fun Avatar(color: Color, initial: String, size: Int, textSize: Int, logo: String? = null) {
    // the owner's chat image when there is one, otherwise the first letter of the title
    val bmp by produceState<ImageBitmap?>(null, logo) {
        value = if (logo == null) null else withContext(Dispatchers.IO) { runCatching { URL(logo).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        val image = bmp
        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(initial, color = Color.White, fontSize = textSize.sp, fontWeight = FontWeight.ExtraBold)
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
private fun Bubble(row: ChatRow.Msg, accent: Color, initial: String, logo: String?) {
    val L = remember { OctokanStrings.forLanguage(Locale.getDefault().language) }
    val m = row.m
    val mine = m.fromVisitor
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine && row.ends) 6.dp else 18.dp, if (!mine && row.ends) 6.dp else 18.dp)
    Column(Modifier.fillMaxWidth().padding(top = if (row.starts) 12.dp else 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            if (!mine) {
                if (row.ends) Avatar(accent, initial, 28, 12, logo) else Spacer(Modifier.size(28.dp))
            }
            Box(Modifier.widthIn(max = 290.dp).alpha(if (m.pending) 0.65f else 1f).clip(shape).background(if (mine) accent else Soft).padding(horizontal = 14.dp, vertical = 9.dp)) {
                MessageContent(m, mine)
            }
        }
        if (row.ends || row.status != null) {
            val seen = row.status == "seen"
            val label = when (row.status) { "seen" -> "✓✓ " + L.getValue("seen"); "sent" -> "✓ " + L.getValue("sent"); "sending" -> L.getValue("sending"); else -> null }
            Text(
                clock(m.atMillis) + (label?.let { " · $it" } ?: ""),
                color = if (seen) Green else Color(0xFF9CA3AF),
                fontSize = 10.sp,
                fontWeight = if (seen) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 3.dp, start = if (mine) 0.dp else 36.dp),
            )
        }
    }
}

private fun bytesLabel(n: Long): String = if (n > 1_048_576) "%.1f MB".format(n / 1_048_576.0) else "${maxOf(1L, n / 1024)} KB"

/** A message's content: its text, or a photo, a voice note or a file that opens when tapped. */
@Composable
private fun MessageContent(m: OctokanMessage, mine: Boolean) {
    val color = if (mine) Color.White else Ink
    val a = m.attachment
    if (a == null || m.kind == "text") {
        Text(m.body, color = color, fontSize = 16.sp)
        return
    }
    val context = LocalContext.current
    val open = { a.url?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }; Unit }
    val caption = if (m.body in setOf("📷 Photo", "🎤 Voice message", "📎 ${a.name}")) "" else m.body
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (m.kind == "image" && a.url != null) {
            val bmp by produceState<ImageBitmap?>(null, a.url) {
                value = withContext(Dispatchers.IO) { runCatching { URL(a.url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull() }
            }
            Box(Modifier.size(220.dp, 160.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.08f)).clickable { open() }) {
                bmp?.let { Image(it, contentDescription = a.name ?: "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        } else {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(if (mine) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.06f)).clickable { open() }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (m.kind == "audio") "🎤" else "📎", fontSize = 20.sp)
                Column {
                    Text(if (m.kind == "audio") "Voice message" else a.name ?: "File", color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(if (m.kind == "audio") "Tap to play" else a.size?.let { bytesLabel(it) } ?: "Tap to open", color = color.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
        }
        if (caption.isNotEmpty()) Text(caption, color = color, fontSize = 16.sp)
    }
}

/**
 * A red badge with the number of unread replies from support, or nothing when there are none. Place it over your own
 * chat icon, and call `Octokan.watchUnread()` once so the count stays fresh while the chat is closed.
 */
@Composable
fun OctokanUnreadBadge(modifier: Modifier = Modifier) {
    val n by Octokan.unread.collectAsState()
    if (n <= 0) return
    Box(modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape).background(Color(0xFFFF3B24)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(if (n > 9) "9+" else "$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
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
