package app.octokan.sdk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

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

/** The chat screen. Embed it anywhere in your Compose UI, or use `Octokan.open(context)`. */
@Composable
fun OctokanChat(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val model: OctokanChatViewModel = viewModel()
    DisposableEffect(Unit) {
        model.start()
        onDispose { }
    }

    val ink = MaterialTheme.colorScheme.onSurface
    val soft = MaterialTheme.colorScheme.surfaceVariant
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(model.messages.size) {
        if (model.messages.isNotEmpty()) list.animateScrollToItem(model.messages.lastIndex + 1)
    }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(model.accent))
            Spacer(Modifier.size(10.dp))
            Text(model.title, color = ink, fontSize = 17.sp, style = TextStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (onClose != null) TextButton(onClick = onClose) { Text("Close", color = ink) }
        }
        Divider()

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    model.welcome,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(soft).padding(12.dp),
                )
            }
            items(model.messages, key = { it.id }) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromVisitor) Arrangement.End else Arrangement.Start) {
                    Text(
                        m.body,
                        color = if (m.fromVisitor) Color.White else ink,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .widthIn(max = 290.dp)
                            .alpha(if (m.pending) 0.6f else 1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (m.fromVisitor) model.accent else soft)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        model.error?.let {
            Text(it, color = Color(0xFFB42318), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(soft).padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (draft.isEmpty()) Text("Write a message…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(2000) },
                    maxLines = 5,
                    textStyle = TextStyle(color = ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(model.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val canSend = draft.isNotBlank()
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(model.accent).alpha(if (canSend) 1f else 0.4f)
                    .then(if (canSend) Modifier.clickableNoRipple { model.send(draft); draft = "" } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text("↑", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
