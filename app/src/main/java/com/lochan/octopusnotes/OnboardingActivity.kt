@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.lochan.octopusnotes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private val currentPage = mutableIntStateOf(0)
    private val requestedPage = mutableIntStateOf(-1)

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (currentPage.intValue > 0) {
                requestedPage.intValue = currentPage.intValue - 1
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        setContentView(
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@OnboardingActivity)
                setViewTreeViewModelStoreOwner(this@OnboardingActivity)
                setViewTreeSavedStateRegistryOwner(this@OnboardingActivity)
                setContent {
                    AppTheme {
                        OnboardingScreen(
                            appName = BuildConfig.APP_DISPLAY_NAME,
                            currentPage = currentPage.intValue,
                            requestedPage = requestedPage.intValue,
                            onPageChanged = { currentPage.intValue = it },
                            onFinish = ::finishOnboarding
                        )
                    }
                }
            }
        )
    }

    private fun finishOnboarding() {
        getSharedPreferences("OctopusNotesPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_completed", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()

    val colors = if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
    else dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colors, content = content)
}

private data class OnboardingPageData(
    @DrawableRes val icon: Int,
    @DrawableRes val badgeIcon: Int? = null,
    val title: String,
    val subtitle: String? = null,
    val bullets: List<String> = emptyList(),

    val paragraph: String? = null,

    val useAppIcon: Boolean = false,

    val tools: List<ToolIntroData>? = null,

    val settings: List<SettingsRowData>? = null
)

private data class ToolIntroData(
    @DrawableRes val icon: Int,
    val name: String,
    val options: List<DockGroup>
)

private data class DockGroup(
    val controls: List<DockControl>,
    val description: String
)

private enum class DockPopup { LINE_STYLE, SIZE_SLIDER, HL_SHAPE }

private data class DockControl(
    val color: Color? = null,
    @DrawableRes val icon: Int? = null,
    val label: String? = null,

    val popup: DockPopup? = null,

    val sliderFrom: Float = 1f,
    val sliderTo: Float = 40f,
    val sliderInitial: Float = 10f
)

private data class SettingsRowData(
    @DrawableRes val icon: Int,
    val name: String,
    val description: String,
    val control: SettingsControl
)

private sealed class SettingsControl {

    class Toggle(val prefKey: String, val defaultValue: Boolean) : SettingsControl()

    class Choice(
        val prefKey: String,
        val labels: List<String>,
        val values: List<String>,
        val defaultValue: String
    ) : SettingsControl()

    object CanvasColor : SettingsControl()
}

@Composable
private fun OnboardingScreen(
    appName: String,
    currentPage: Int,
    requestedPage: Int,
    onPageChanged: (Int) -> Unit,
    onFinish: () -> Unit
) {
    val pages = rememberIntroPages(appName)
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = currentPage == pages.lastIndex

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
    }

    LaunchedEffect(requestedPage) {
        if (requestedPage >= 0 && requestedPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(requestedPage)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    0.5f to MaterialTheme.colorScheme.background,
                    1.0f to MaterialTheme.colorScheme.background
                )
            )
    ) {
        Scaffold(containerColor = Color.Transparent) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onFinish) {
                        Text(stringResource(R.string.intro_skip))
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { pageIndex ->

                    val distance = ((pagerState.currentPage - pageIndex) +
                        pagerState.currentPageOffsetFraction).absoluteValue
                    val progress = (1f - distance).coerceIn(0f, 1f)
                    OnboardingPage(
                        page = pages[pageIndex],
                        modifier = Modifier.graphicsLayer {
                            scaleX = 0.92f + 0.08f * progress
                            scaleY = 0.92f + 0.08f * progress
                            alpha = 0.45f + 0.55f * progress
                        }
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    pages.indices.forEach { index ->
                        val active = index == currentPage
                        val width by animateDpAsState(if (active) 26.dp else 8.dp, label = "dot$index")
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .height(8.dp)
                                .width(width)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (isLastPage) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = if (isLastPage) stringResource(R.string.intro_get_started)
                        else stringResource(R.string.intro_next),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberIntroPages(appName: String): List<OnboardingPageData> {
    val context = LocalContext.current
    return remember(appName) {
        listOf(
            OnboardingPageData(
                icon = R.drawable.ic_notes,
                title = context.getString(R.string.intro_welcome_title, appName),
                subtitle = context.getString(R.string.intro_welcome_subtitle, appName),
                paragraph = context.getString(R.string.intro_welcome_sentence),
                useAppIcon = true
            ),
            OnboardingPageData(
                icon = R.drawable.ic_pen,
                title = context.getString(R.string.intro_tools_title),
                subtitle = context.getString(R.string.intro_tools_subtitle),
                bullets = emptyList(),
                tools = listOf(
                    ToolIntroData(
                        icon = R.drawable.ic_pen,
                        name = context.getString(R.string.tool_pen),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_line_solid, popup = DockPopup.LINE_STYLE)
                                ),
                                description = context.getString(R.string.intro_tool_pen_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(color = Color(0xFF000000)),
                                    DockControl(color = Color(0xFF1565C0)),
                                    DockControl(color = Color(0xFFC62828)),
                                    DockControl(color = Color(0xFF2E7D32)),
                                    DockControl(color = Color(0xFFF9A825))
                                ),
                                description = context.getString(R.string.intro_tool_pen_options_2_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_stroke_thin, popup = DockPopup.SIZE_SLIDER),
                                    DockControl(icon = R.drawable.ic_stroke_medium, popup = DockPopup.SIZE_SLIDER),
                                    DockControl(icon = R.drawable.ic_stroke_thick, popup = DockPopup.SIZE_SLIDER)
                                ),
                                description = context.getString(R.string.intro_tool_pen_options_3_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_eraser,
                        name = context.getString(R.string.tool_eraser),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(label = context.getString(R.string.intro_tool_eraser_pixel)),
                                    DockControl(label = context.getString(R.string.intro_tool_eraser_stroke))
                                ),
                                description = context.getString(R.string.intro_tool_eraser_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_stroke_thin, popup = DockPopup.SIZE_SLIDER, sliderFrom = 5f, sliderTo = 120f, sliderInitial = 50f),
                                    DockControl(icon = R.drawable.ic_stroke_medium, popup = DockPopup.SIZE_SLIDER, sliderFrom = 5f, sliderTo = 120f, sliderInitial = 50f),
                                    DockControl(icon = R.drawable.ic_stroke_thick, popup = DockPopup.SIZE_SLIDER, sliderFrom = 5f, sliderTo = 120f, sliderInitial = 50f)
                                ),
                                description = context.getString(R.string.intro_tool_eraser_options_2_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_highlighter,
                        name = context.getString(R.string.intro_tools_highlighter),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_line_wavy, popup = DockPopup.HL_SHAPE)
                                ),
                                description = context.getString(R.string.intro_tool_highlighter_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(color = Color(0xFFFFEB00)),
                                    DockControl(color = Color(0xFF00E676)),
                                    DockControl(color = Color(0xFFFF4081)),
                                    DockControl(color = Color(0xFF40C4FF)),
                                    DockControl(color = Color(0xFFFF9100))
                                ),
                                description = context.getString(R.string.intro_tool_highlighter_options_2_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_stroke_thin, popup = DockPopup.SIZE_SLIDER, sliderFrom = 8f, sliderTo = 60f, sliderInitial = 30f),
                                    DockControl(icon = R.drawable.ic_stroke_medium, popup = DockPopup.SIZE_SLIDER, sliderFrom = 8f, sliderTo = 60f, sliderInitial = 30f),
                                    DockControl(icon = R.drawable.ic_stroke_thick, popup = DockPopup.SIZE_SLIDER, sliderFrom = 8f, sliderTo = 60f, sliderInitial = 30f)
                                ),
                                description = context.getString(R.string.intro_tool_highlighter_options_3_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_lasso,
                        name = context.getString(R.string.tool_lasso),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_lasso),
                                    DockControl(icon = R.drawable.ic_select_rect),
                                    DockControl(icon = R.drawable.ic_select_circle)
                                ),
                                description = context.getString(R.string.intro_tool_lasso_options_1_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_image,
                        name = context.getString(R.string.intro_tools_image),
                        options = listOf(
                            DockGroup(
                                controls = listOf(DockControl(icon = R.drawable.ic_camera)),
                                description = context.getString(R.string.intro_tool_image_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(DockControl(icon = R.drawable.ic_add)),
                                description = context.getString(R.string.intro_tool_image_options_2_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_shape_triangle,
                        name = context.getString(R.string.tool_shape),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_shape_rect),
                                    DockControl(icon = R.drawable.ic_shape_oval),
                                    DockControl(icon = R.drawable.ic_shape_triangle),
                                    DockControl(icon = R.drawable.ic_line_straight),
                                    DockControl(icon = R.drawable.ic_shape_arrow)
                                ),
                                description = context.getString(R.string.intro_tool_shape_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(DockControl(icon = R.drawable.ic_more_vert)),
                                description = context.getString(R.string.intro_tool_shape_options_2_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_table,
                        name = context.getString(R.string.tool_table),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_line_solid, popup = DockPopup.LINE_STYLE)
                                ),
                                description = context.getString(R.string.intro_tool_table_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(color = Color(0xFF000000)),
                                    DockControl(color = Color(0xFF1565C0)),
                                    DockControl(color = Color(0xFFC62828)),
                                    DockControl(color = Color(0xFF2E7D32)),
                                    DockControl(color = Color(0xFFF9A825))
                                ),
                                description = context.getString(R.string.intro_tool_table_options_2_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_stroke_thin, popup = DockPopup.SIZE_SLIDER),
                                    DockControl(icon = R.drawable.ic_stroke_medium, popup = DockPopup.SIZE_SLIDER),
                                    DockControl(icon = R.drawable.ic_stroke_thick, popup = DockPopup.SIZE_SLIDER)
                                ),
                                description = context.getString(R.string.intro_tool_table_options_3_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_text,
                        name = context.getString(R.string.tool_text),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(label = context.getString(R.string.text_size_small)),
                                    DockControl(label = context.getString(R.string.text_size_medium)),
                                    DockControl(label = context.getString(R.string.text_size_large))
                                ),
                                description = context.getString(R.string.intro_tool_text_options_1_desc)
                            )
                        )
                    ),
                    ToolIntroData(
                        icon = R.drawable.ic_laser,
                        name = context.getString(R.string.tool_laser),
                        options = listOf(
                            DockGroup(
                                controls = listOf(
                                    DockControl(color = Color(0xFF000000)),
                                    DockControl(color = Color(0xFF1565C0)),
                                    DockControl(color = Color(0xFFC62828)),
                                    DockControl(color = Color(0xFF2E7D32)),
                                    DockControl(color = Color(0xFFF9A825))
                                ),
                                description = context.getString(R.string.intro_tool_laser_options_1_desc)
                            ),
                            DockGroup(
                                controls = listOf(
                                    DockControl(icon = R.drawable.ic_stroke_thin, popup = DockPopup.SIZE_SLIDER),
                                    DockControl(icon = R.drawable.ic_stroke_medium, popup = DockPopup.SIZE_SLIDER),
                                    DockControl(icon = R.drawable.ic_stroke_thick, popup = DockPopup.SIZE_SLIDER)
                                ),
                                description = context.getString(R.string.intro_tool_laser_options_2_desc)
                            )
                        )
                    )
                ),
            ),
            OnboardingPageData(
                icon = R.drawable.ic_settings,
                title = context.getString(R.string.intro_editing_title),
                subtitle = context.getString(R.string.intro_editing_subtitle),
                bullets = emptyList(),
                settings = listOf(
                    SettingsRowData(
                        icon = R.drawable.ic_add,
                        name = context.getString(R.string.intro_editing_row_pages),
                        description = context.getString(R.string.intro_editing_row_pages_desc),
                        control = SettingsControl.Toggle(prefKey = "continuous_pages", defaultValue = false)
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_pen,
                        name = context.getString(R.string.intro_editing_row_options),
                        description = context.getString(R.string.intro_editing_row_options_desc),
                        control = SettingsControl.Toggle(prefKey = "TOOL_OPTIONS_AUTO_SHOW", defaultValue = true)
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_notes,
                        name = context.getString(R.string.intro_editing_row_tabs),
                        description = context.getString(R.string.intro_editing_row_tabs_desc),
                        control = SettingsControl.Toggle(prefKey = TabSession.PREFS_ENABLED, defaultValue = false)
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_color,
                        name = context.getString(R.string.intro_editing_row_canvas),
                        description = context.getString(R.string.intro_editing_row_canvas_desc),
                        control = SettingsControl.CanvasColor
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_eraser,
                        name = context.getString(R.string.intro_editing_row_scribble),
                        description = context.getString(R.string.intro_editing_row_scribble_desc),
                        control = SettingsControl.Choice(
                            prefKey = "scribble_erase_difficulty",
                            labels = listOf(
                                context.getString(R.string.intro_editing_scribble_easy),
                                context.getString(R.string.intro_editing_scribble_hard)
                            ),
                            values = listOf("EASY", "HARD"),
                            defaultValue = "EASY"
                        )
                    )
                )
            ),
            OnboardingPageData(
                icon = R.drawable.ic_notes,
                title = context.getString(R.string.intro_home_title),
                subtitle = context.getString(R.string.intro_home_subtitle),
                bullets = emptyList(),
                settings = listOf(
                    SettingsRowData(
                        icon = R.drawable.ic_grid,
                        name = context.getString(R.string.intro_home_row_layout),
                        description = context.getString(R.string.intro_home_row_layout_desc),
                        control = SettingsControl.Choice(
                            prefKey = "home_view_mode",
                            labels = listOf(
                                context.getString(R.string.intro_home_layout_grid),
                                context.getString(R.string.intro_home_layout_list)
                            ),
                            values = listOf("GRID", "LIST"),
                            defaultValue = "GRID"
                        )
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_notes,
                        name = context.getString(R.string.intro_home_row_notebook_thumb),
                        description = context.getString(R.string.intro_home_row_notebook_thumb_desc),
                        control = SettingsControl.Choice(
                            prefKey = "thumb_notebook",
                            labels = listOf(
                                context.getString(R.string.intro_home_thumb_first),
                                context.getString(R.string.intro_home_thumb_last),
                                context.getString(R.string.intro_home_thumb_last_used)
                            ),
                            values = listOf("FIRST", "LAST", "LAST_USED"),
                            defaultValue = "LAST"
                        )
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_pdf,
                        name = context.getString(R.string.intro_home_row_pdf_thumb),
                        description = context.getString(R.string.intro_home_row_pdf_thumb_desc),
                        control = SettingsControl.Choice(
                            prefKey = "thumb_pdf",
                            labels = listOf(
                                context.getString(R.string.intro_home_thumb_first),
                                context.getString(R.string.intro_home_thumb_last)
                            ),
                            values = listOf("FIRST", "LAST"),
                            defaultValue = "FIRST"
                        )
                    ),
                    SettingsRowData(
                        icon = R.drawable.ic_text,
                        name = context.getString(R.string.intro_home_row_marquee),
                        description = context.getString(R.string.intro_home_row_marquee_desc),
                        control = SettingsControl.Toggle(prefKey = "marquee_text", defaultValue = false)
                    )
                )
            ),

            OnboardingPageData(
                icon = R.drawable.ic_folder,
                title = context.getString(R.string.intro_data_title),
                subtitle = context.getString(R.string.intro_data_subtitle),
                bullets = emptyList(),
                settings = listOf(
                    SettingsRowData(
                        icon = R.drawable.ic_folder,
                        name = context.getString(R.string.intro_data_row_sync),
                        description = context.getString(R.string.intro_data_row_sync_desc),
                        control = SettingsControl.Toggle(prefKey = "sync_folder_enabled", defaultValue = false)
                    )
                )
            )
        )
    }
}

@Composable
private fun OnboardingPage(page: OnboardingPageData, modifier: Modifier = Modifier) {

    if (page.tools != null) {
        ToolsPage(page, modifier)
        return
    }

    if (page.settings != null) {
        SettingsPage(page, modifier)
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        if (page.useAppIcon) {
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(RoundedCornerShape(36.dp))
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else {

            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(page.icon),
                        contentDescription = null,
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                page.badgeIcon?.let { badge ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(badge),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        page.subtitle?.let { subtitle ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(28.dp))

        val paragraph = page.paragraph
        if (paragraph != null) {
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                page.bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsPage(page: OnboardingPageData, modifier: Modifier = Modifier) {
    val tools = page.tools ?: return

    var activeToolIndex by remember { mutableIntStateOf(0) }
    var activeGroupIndex by remember { mutableIntStateOf(0) }

    var dockIconOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val tool = tools[activeToolIndex]
    val group = tool.options[activeGroupIndex]

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,

        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(4.dp))

        AnimatedContent(
            targetState = tool.name,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + slideInVertically { -it / 6 }) togetherWith
                    (fadeOut(animationSpec = tween(120)) + slideOutVertically { it / 6 })
            },
            label = "toolsTitle"
        ) { toolName ->
            Text(
                text = HighlightedToolTitle(toolName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        page.subtitle?.let { subtitle ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        ToolbarPill(
            tools = tools,
            activeIndex = activeToolIndex,
            onSelect = { index ->
                activeToolIndex = index
                activeGroupIndex = 0
            }
        )

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = activeToolIndex,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + slideInHorizontally { it / 6 }) togetherWith
                    (fadeOut(animationSpec = tween(120)) + slideOutHorizontally { -it / 6 })
            },
            label = "optionsDock"
        ) { index ->
            OptionsPill(
                groups = tools[index].options,
                activeGroup = activeGroupIndex,
                onSelect = { activeGroupIndex = it },
                toolIndex = index,
                iconOverrides = dockIconOverrides,
                onIconOverride = { key, icon -> dockIconOverrides = dockIconOverrides + (key to icon) }
            )
        }

        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = group,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) + slideInVertically { -it / 6 }) togetherWith
                    (fadeOut(animationSpec = tween(120)) + slideOutVertically { it / 6 })
            },
            label = "optionDescription"
        ) { targetGroup ->
            OptionDescription(targetGroup.description)
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarIconButton(
                    icon = R.drawable.ic_undo,
                    contentDescription = stringResource(R.string.undo)
                )
                ToolbarIconButton(
                    icon = R.drawable.ic_redo,
                    contentDescription = stringResource(R.string.redo)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.intro_tools_undo_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingsPage(page: OnboardingPageData, modifier: Modifier = Modifier) {
    val rows = page.settings ?: return
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(CanvasColor.PREFS_NAME, Context.MODE_PRIVATE)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,

        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(4.dp))

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        page.subtitle?.let { subtitle ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        SettingsCard(rows = rows, prefs = prefs, context = context)

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.intro_settings_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingsCard(
    rows: List<SettingsRowData>,
    prefs: SharedPreferences,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 6.dp)
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) SettingsRowDivider()
            SettingsRowView(row = row, prefs = prefs, context = context)
        }
    }
}

@Composable
private fun SettingsRowView(
    row: SettingsRowData,
    prefs: SharedPreferences,
    context: Context
) {
    val control = row.control
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(row.icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (control is SettingsControl.Toggle || control is SettingsControl.CanvasColor) {
                SettingsRowControl(control = control, prefs = prefs, context = context)
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = row.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (control is SettingsControl.Choice) {
            Spacer(Modifier.height(10.dp))
            SettingsRowControl(control = control, prefs = prefs, context = context)
        }
    }
}

@Composable
private fun SettingsRowControl(
    control: SettingsControl,
    prefs: SharedPreferences,
    context: Context
) {
    when (control) {
        is SettingsControl.Toggle -> {
            var checked by remember {
                mutableStateOf(prefs.getBoolean(control.prefKey, control.defaultValue))
            }
            Switch(
                checked = checked,
                onCheckedChange = { on ->
                    checked = on
                    prefs.edit().putBoolean(control.prefKey, on).apply()
                }
            )
        }
        is SettingsControl.Choice -> {
            var selected by remember {
                mutableStateOf(prefs.getString(control.prefKey, control.defaultValue))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                control.labels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = selected == control.values[index],
                        onClick = {
                            selected = control.values[index]
                            prefs.edit().putString(control.prefKey, control.values[index]).apply()
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
        SettingsControl.CanvasColor -> {
            var color by remember { mutableStateOf(CanvasColor.current(context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable {
                            val activity = context as? Activity
                            if (activity != null) {
                                ColorPickerDialog.show(activity, color, allowEyedropper = false) { picked ->
                                    color = picked
                                    CanvasColor.prefs(context).edit()
                                        .putInt(CanvasColor.PREFS_KEY, picked).apply()
                                }
                            }
                        }
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    CanvasColor.reset(context)
                    color = CanvasColor.current(context)
                }) {
                    Text(stringResource(R.string.intro_editing_row_canvas_default))
                }
            }
        }
    }
}

@Composable
private fun SettingsRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun HighlightedToolTitle(toolName: String): AnnotatedString {
    val formatted = stringResource(R.string.intro_tools_title_with_tool, toolName)
    val nameStart = formatted.indexOf(toolName)
    return buildAnnotatedString {
        append(formatted)
        if (nameStart >= 0) {
            addStyle(
                SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold),
                start = nameStart,
                end = nameStart + toolName.length
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolbarPill(tools: List<ToolIntroData>, activeIndex: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tools.forEachIndexed { index, tool ->
            val active = index == activeIndex

            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                label = "toolBackground$index"
            )
            val tint by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "toolTint$index"
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(background)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(tool.icon),
                    contentDescription = tool.name,
                    modifier = Modifier.size(26.dp),
                    tint = tint
                )
            }
        }
    }
}

@Composable
private fun OptionsPill(
    groups: List<DockGroup>,
    activeGroup: Int,
    onSelect: (Int) -> Unit,
    toolIndex: Int,
    iconOverrides: Map<String, Int>,
    onIconOverride: (String, Int) -> Unit
) {

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        groups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) DockDivider()
            group.controls.forEach { control ->
                DockControlView(
                    control = control,
                    selected = groupIndex == activeGroup,
                    onClick = { onSelect(groupIndex) },
                    iconOverride = iconOverrides["$toolIndex:$groupIndex"],
                    onPopupPicked = { icon -> onIconOverride("$toolIndex:$groupIndex", icon) }
                )
            }
        }
    }
}

@Composable
private fun DockControlView(
    control: DockControl,
    selected: Boolean,
    onClick: () -> Unit,
    iconOverride: Int? = null,
    onPopupPicked: (Int) -> Unit = {}
) {

    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val hostView = LocalView.current

    fun handleClick() {
        onClick()
        val popup = control.popup ?: return
        val bounds = anchorBounds ?: return
        when (popup) {
            DockPopup.LINE_STYLE -> showLineStylePopup(hostView, bounds, onPopupPicked)
            DockPopup.SIZE_SLIDER -> showSizeSliderPopup(
                hostView, bounds, control.sliderFrom, control.sliderTo, control.sliderInitial
            )
            DockPopup.HL_SHAPE -> showHighlighterShapePopup(hostView, bounds, onPopupPicked)
        }
    }

    val interaction = Modifier
        .onGloballyPositioned { anchorBounds = it.boundsInWindow() }
        .clickable { handleClick() }

    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "dockBackground"
    )
    control.color?.let { color ->
        val borderColor by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
            label = "dockBorder"
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = borderColor,
                    shape = CircleShape
                )
                .then(interaction)
        )
        return
    }
    control.label?.let { label ->
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(background)
                .then(interaction)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(background)
            .then(interaction),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconOverride ?: control.icon!!),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DockDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(28.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun OptionDescription(description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun showDockPopup(
    anchor: View,
    bounds: Rect,
    layoutRes: Int,
    widthDp: Int,
    configure: (View, PopupWindow) -> Unit
) {
    val context = anchor.context
    val density = context.resources.displayMetrics.density
    val view = LayoutInflater.from(context).inflate(layoutRes, null)
    val widthPx = (widthDp * density).toInt()
    val popup = PopupWindow(view, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    popup.elevation = 20f
    popup.setBackgroundDrawable(null)
    configure(view, popup)

    view.measure(
        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val root = anchor.rootView
    val gap = (8 * density).toInt()
    val x = bounds.left.toInt().coerceIn(gap, (root.width - view.measuredWidth - gap).coerceAtLeast(gap))
    var y = bounds.bottom.toInt() + gap
    if (y + view.measuredHeight > root.height - gap) {
        y = (bounds.top.toInt() - view.measuredHeight - gap).coerceAtLeast(gap)
    }
    popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
}

private fun showLineStylePopup(anchor: View, bounds: Rect, onPicked: (Int) -> Unit) {
    showDockPopup(anchor, bounds, R.layout.popup_line_style, 280) { view, popup ->
        fun choose(icon: Int) {
            onPicked(icon)
            popup.dismiss()
        }
        view.findViewById<View>(R.id.styleSolid).setOnClickListener { choose(R.drawable.ic_line_solid) }
        view.findViewById<View>(R.id.styleDotted).setOnClickListener { choose(R.drawable.ic_line_dotted) }
        view.findViewById<View>(R.id.styleDashed).setOnClickListener { choose(R.drawable.ic_line_dashed) }
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.stabilizationSlider)
        val value = view.findViewById<TextView>(R.id.stabilizationValue)
        value.text = "Level ${slider.value.toInt()}"
        slider.addOnChangeListener { _, v, _ -> value.text = "Level ${v.toInt()}" }

        val root = view as? LinearLayout ?: return@showDockPopup
        val captions = LinearLayout(view.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        addIconCaptions(
            captions,
            listOf(
                view.context.getString(R.string.intro_line_style_solid),
                view.context.getString(R.string.intro_line_style_dotted),
                view.context.getString(R.string.intro_line_style_dashed)
            )
        )
        root.addView(captions, 1)
        root.appendExplanation(view.context.getString(R.string.intro_popup_line_styles))
        root.appendExplanation(view.context.getString(R.string.intro_popup_stabilization))
    }
}

private fun showSizeSliderPopup(
    anchor: View,
    bounds: Rect,
    from: Float,
    to: Float,
    initial: Float
) {
    showDockPopup(anchor, bounds, R.layout.popup_thickness, 300) { view, _ ->
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.thicknessSlider)
        val value = view.findViewById<TextView>(R.id.thicknessValue)
        slider.valueFrom = from
        slider.valueTo = to
        slider.value = initial.coerceIn(from, to)
        value.text = slider.value.toInt().toString()
        slider.addOnChangeListener { _, v, _ -> value.text = v.toInt().toString() }
        view.findViewById<ImageButton>(R.id.thicknessMinus).setOnClickListener {
            slider.value = (slider.value - 1f).coerceAtLeast(slider.valueFrom)
        }
        view.findViewById<ImageButton>(R.id.thicknessPlus).setOnClickListener {
            slider.value = (slider.value + 1f).coerceAtMost(slider.valueTo)
        }
        (view as? LinearLayout)?.appendExplanation(view.context.getString(R.string.intro_popup_thickness))
    }
}

private fun showHighlighterShapePopup(anchor: View, bounds: Rect, onPicked: (Int) -> Unit) {
    showDockPopup(anchor, bounds, R.layout.popup_highlighter_line, 280) { view, popup ->
        fun choose(icon: Int) {
            onPicked(icon)
            popup.dismiss()
        }
        view.findViewById<View>(R.id.hlShapeFree).setOnClickListener { choose(R.drawable.ic_line_wavy) }
        view.findViewById<View>(R.id.hlShapeStraight).setOnClickListener { choose(R.drawable.ic_line_straight) }
        val normal = view.findViewById<View>(R.id.hlModeNormal)
        val text = view.findViewById<View>(R.id.hlModeText)
        fun selectMode(selected: View, other: View) {
            selected.isSelected = true
            other.isSelected = false
        }
        normal.setOnClickListener { selectMode(normal, text) }
        text.setOnClickListener { selectMode(text, normal) }
        normal.isSelected = true
        (view as? LinearLayout)?.let { root ->
            root.appendExplanation(view.context.getString(R.string.intro_popup_hl_shape))
            root.appendExplanation(view.context.getString(R.string.intro_popup_hl_mode))
        }
    }
}

private fun themeAttrColor(view: View, attr: Int): Int =
    com.google.android.material.color.MaterialColors.getColor(view, attr, android.graphics.Color.GRAY)

private fun addIconCaptions(row: LinearLayout, labels: List<String>, widthDp: Int = 48) {
    val density = row.context.resources.displayMetrics.density
    labels.forEach { label ->
        row.addView(TextView(row.context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(themeAttrColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(
                (widthDp * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }
}

private fun LinearLayout.appendExplanation(text: String) {
    val density = context.resources.displayMetrics.density
    val pad = (10 * density).toInt()
    addView(View(context).apply {
        setBackgroundColor(themeAttrColor(this, com.google.android.material.R.attr.colorOutlineVariant))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = pad; bottomMargin = pad }
    })
    addView(TextView(context).apply {
        this.text = text
        textSize = 12f
        setLineSpacing(0f, 1.15f)
        setTextColor(themeAttrColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    })
}
