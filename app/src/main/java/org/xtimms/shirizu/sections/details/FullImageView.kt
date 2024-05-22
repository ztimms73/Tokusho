package org.xtimms.shirizu.sections.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import kotlinx.coroutines.launch
import org.xtimms.shirizu.core.ShirizuAsyncImage
import org.xtimms.shirizu.core.components.BackIconButton
import org.xtimms.shirizu.core.components.ViewInBrowserButton
import org.xtimms.shirizu.ui.theme.ShirizuTheme

const val PICTURES_ARGUMENT = "{pictures}"
const val FULL_POSTER_DESTINATION = "full_poster/$PICTURES_ARGUMENT"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullImageView(
    pictures: Array<String>,
    navigateBack: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState { pictures.size }

    val uriHandler = LocalUriHandler.current
    fun openUrl(url: String) {
        uriHandler.openUri(url)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    BackIconButton(onClick = navigateBack)
                },
                actions = {
                    ViewInBrowserButton(
                        onClick = {
                            pictures.getOrNull(pagerState.currentPage)?.let { url ->
                                openUrl(url)
                            }
                        }
                    )
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .padding(it)
                .fillMaxSize()
        ) {
            HorizontalPager(
                modifier = Modifier.weight(1f),
                state = pagerState,
                pageSpacing = 16.dp,
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShirizuAsyncImage(
                        model = pictures[page],
                        contentDescription = "image$page",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Row(
                modifier = Modifier
                    .height(50.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                if (pictures.size > 1) {
                    pictures.forEachIndexed { index, _ ->
                        val color =
                            if (pagerState.currentPage == index)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primaryContainer
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(color)
                                .size(8.dp)
                                .clickable {
                                    coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                }
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FullPosterPreview() {
    ShirizuTheme {
        FullImageView(
            pictures = arrayOf("", ""),
            navigateBack = {}
        )
    }
}