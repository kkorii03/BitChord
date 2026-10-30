package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.ExploreCategoryFilter
import com.music.bitchord.data.ExploreCategoryNames
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.LARGE_CARD_CORNER
import com.music.bitchord.ui.components.LoadingTimeoutState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.rememberLoadingTimedOut
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.player.appLanguageTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    /**
     * Offers to hide a long-pressed tile. Null keeps the desktop build's grid
     * exactly as it was: no hide affordance, no dialog.
     */
    onHideCategory: ((MoodGenre) -> Unit)? = null,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    var pendingHide by remember { mutableStateOf<MoodGenre?>(null) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    val loadingTimedOut = rememberLoadingTimedOut(
        loading = state is UiState.Loading,
        tag = "explore",
        requestKey = loadAttempt,
    )
    val retry = {
        loadAttempt += 1
        onRetry()
    }
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Text(
                            text = stringResource(Res.string.explore),
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            // 14dp under the title, the same gap Library leaves
                            // above its first card (8dp title + 6dp Replay row).
                            modifier = Modifier.padding(
                                start = PAGE_GUTTER,
                                end = PAGE_GUTTER,
                                top = 8.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
                when (state) {
                    UiState.Loading -> if (loadingTimedOut) {
                        item { LoadingTimeoutState(onRetry = retry) }
                    } else {
                        item { ExploreSkeletonRows(columns) }
                    }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = retry)
                    }
                    is UiState.Success -> {
                        // Moods and genres read as one grid; the server's
                        // grouping only decides the order. (This is a
                        // LazyListScope, not a composable, so it cannot
                        // remember; the pass is cheap next to the rows.)
                        val rows = state.data.flatMap(MoodGenreSection::items)
                            .distinctBy { it.browseId to it.params }
                            .chunked(columns)
                        items(rows, key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                            MoodGenreRow(
                                row = row,
                                columns = columns,
                                onCategoryClick = onCategoryClick,
                                onCategoryLongClick = onHideCategory?.let {
                                    { item ->
                                        if (!ExploreCategoryFilter.isProtected(item)) pendingHide = item
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingHide?.let { item ->
        val displayTitle = ExploreCategoryNames.displayTitle(item.title, appLanguageTag())
        AlertDialog(
            onDismissRequest = { pendingHide = null },
            title = { Text(stringResource(Res.string.hide_explore_category)) },
            text = {
                Text(
                    stringResource(
                        Res.string.hide_explore_category_message,
                        displayTitle,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingHide = null
                        onHideCategory?.invoke(item)
                    },
                ) {
                    Text(stringResource(Res.string.hide_explore_category))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHide = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
    onCategoryLongClick: ((MoodGenre) -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEach { item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                onLongClick = onCategoryLongClick?.let { longClick -> { longClick(item) } },
                modifier = Modifier.weight(1f),
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * A category tile in its own solid colour with one Korean label. The phone
 * tiles never draw the server's cover: those artwork files have English titles
 * painted into them, so overlaying our label put two lines of text on one tile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    MoodGenreSleeve(
        item = item,
        modifier = modifier
            .aspectRatio(MOOD_CARD_ASPECT)
            .clip(MOOD_CARD_SHAPE)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        showArtwork = false,
    )
}

/**
 * A category tile in its own colour with the title at the foot — Apple Music's
 * browse tile. Phone callers pass [showArtwork] = false and get the colour plus
 * a single Korean label; the TV app keeps the photographic sleeve by leaving
 * the default alone.
 *
 * Only the artwork: the caller sizes, clips and makes it clickable, which is
 * how the TV app wraps the same sleeve in its own focus treatment.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    showArtwork: Boolean = true,
) {
    val base = remember(item.stripeColor, item.title) { moodBaseColor(item) }
    Box(modifier = modifier.background(base)) {
        if (showArtwork) {
            item.thumbnailUrl?.let { artwork ->
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The fade only at the foot, so the photograph keeps its own colour
            // across the rest of the tile.
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = .82f),
                        ),
                    ),
            )
        }
        Text(
            text = ExploreCategoryNames.displayTitle(item.title, appLanguageTag()),
            style = titleStyle,
            fontWeight = FontWeight.Bold,
            color = if (showArtwork) Color.White else readableOn(base),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Black or white, whichever stays legible on the tile's own colour. */
private fun readableOn(background: Color): Color =
    if (background.luminance() > 0.36f) Color.Black else Color.White

/** The category's own colour, or a muted stand-in when YouTube sent none. */
private fun moodBaseColor(item: MoodGenre): Color =
    item.stripeColor?.let { Color(it.toInt()) } ?: fallbackMoodColor(item.title)

/** For a button that came without YouTube's colour: muted, never neon. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(LARGE_CARD_CORNER)

/** Width over height; a two-column phone row lands at about 100dp tall. */
const val MOOD_CARD_ASPECT = 1.72f

/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

internal fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Drawn under the title, above the shelves — the charts' country picker. */
    header: @Composable () -> Unit = {},
) {
    var loadAttempt by remember { mutableIntStateOf(0) }
    val loadingTimedOut = rememberLoadingTimedOut(
        loading = state is UiState.Loading,
        tag = "mood-genre",
        requestKey = loadAttempt,
    )
    val retry = {
        loadAttempt += 1
        onRetry()
    }
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        item(key = "header") { header() }
        when (state) {
            UiState.Loading -> if (loadingTimedOut) {
                item { LoadingTimeoutState(onRetry = retry) }
            } else {
                feedSkeleton()
            }
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = retry)
            }
            // Indexed: two shelves may share a heading (charts do), and a
            // repeated key would take the whole list down.
            is UiState.Success -> itemsIndexed(state.data, key = { index, shelf -> "$index:${shelf.title}" }) { _, shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
