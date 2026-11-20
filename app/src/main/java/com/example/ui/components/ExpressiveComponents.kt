package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Computes Material 3 Expressive segmented group card corners:
 * outer corners 26.dp, inner adjacent corners 6.dp.
 */
fun expressiveGroupItemShape(index: Int, total: Int): Shape {
    val large = 26.dp
    val small = 6.dp
    return when {
        total <= 1 -> RoundedCornerShape(large)
        index == 0 -> RoundedCornerShape(
            topStart = large,
            topEnd = large,
            bottomStart = small,
            bottomEnd = small,
        )
        index == total - 1 -> RoundedCornerShape(
            topStart = small,
            topEnd = small,
            bottomStart = large,
            bottomEnd = large,
        )
        else -> RoundedCornerShape(small)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 12.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun ExpressiveSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    tag: String = "",
) {
    val iconScale by animateFloatAsState(
        targetValue = if (checked && enabled) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "switch_icon_scale",
    )
    val iconBgColor by animateColorAsState(
        targetValue = if (checked && enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "switch_icon_bg",
    )
    val iconTintColor by animateColorAsState(
        targetValue = if (checked && enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "switch_icon_tint",
    )

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(iconScale)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = iconTintColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
fun ExpressiveActionRow(
    title: String,
    subtitle: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    tag: String = "",
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun AppIconAvatar(
    packageName: String,
    label: String,
    loadIcon: (String) -> Drawable?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            loadIcon(packageName)?.toSafeImageBitmap(96)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = label,
            modifier = modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
    } else {
        val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            modifier = modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun Drawable.toSafeImageBitmap(sizePx: Int): ImageBitmap? {
    return runCatching {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        bmp.asImageBitmap()
    }.getOrNull()
}
