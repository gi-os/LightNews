package com.gios.lightnews.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightnews.ui.theme.Dim
import com.gios.lightnews.ui.theme.RuleGrey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun barColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Black,
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)

@Composable
fun Rule(modifier: Modifier = Modifier) =
    HorizontalDivider(modifier = modifier, color = RuleGrey, thickness = 1.dp)

@Composable
fun EmptyState(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = Dim,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Box(Modifier.padding(top = 22.dp)) { WideButton(action, onAction) }
        }
    }
}

/** Inverted block, because an outline at this size disappears on the matte panel. */
@Composable
fun WideButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Color.White, modifier = modifier.clickable(onClick = onClick)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 15.dp),
        )
    }
}

/** Unread marker. A filled dot survives greyscale; bold type on Akkurat barely does. */
@Composable
fun UnreadDot(unread: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (unread) Surface(color = Color.White, shape = CircleShape, content = {}, modifier = Modifier.size(8.dp))
    }
}

/** Settings row: label and sub-label on the left, current value on the right. */
@Composable
fun MenuRow(
    label: String,
    sub: String? = null,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
fun ListRow(
    sender: String,
    subject: String,
    age: String,
    unread: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 10.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 2.dp)) { UnreadDot(unread) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sender,
                    style = MaterialTheme.typography.labelSmall,
                    color = Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "  $age",
                    style = MaterialTheme.typography.labelSmall,
                    color = Dim,
                    maxLines = 1,
                )
            }
            Text(
                subject,
                style = MaterialTheme.typography.bodyLarge,
                color = if (unread) Color.White else Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
