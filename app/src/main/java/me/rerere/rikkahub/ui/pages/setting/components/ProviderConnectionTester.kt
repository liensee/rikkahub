package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import java.net.URL
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Connect
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject

@Composable
fun ProviderConnectionTester(
    internalProvider: ProviderSetting,
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()

    IconButton(onClick = { showTestDialog = true }) {
        Icon(HugeIcons.Connect, null)
    }

    if (showTestDialog) {
        var model by remember(internalProvider) {
            mutableStateOf(internalProvider.models.firstOrNull { it.type == ModelType.CHAT })
        }
        var nonStreamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var toolsState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        var streamingText by remember { mutableStateOf("") }

        fun resetStates() {
            nonStreamingState = UiState.Idle
            streamingState = UiState.Idle
            toolsState = UiState.Idle
            streamingText = ""
        }

        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = {
                Text(stringResource(R.string.setting_provider_page_test_connection))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModelSelector(
                        modelId = model?.id,
                        providers = listOf(internalProvider),
                        type = ModelType.CHAT,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        model = it
                    }

                    TestResultItem(
                        label = "非流式",
                        state = nonStreamingState,
                        resultText = (nonStreamingState as? UiState.Success)?.data ?: ""
                    )

                    TestResultItem(
                        label = "流式",
                        state = streamingState,
                        resultText = streamingText
                    )

                    TestResultItem(
                        label = "工具调用",
                        state = toolsState,
                        resultText = (toolsState as? UiState.Success)?.data ?: ""
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (model == null) return@TextButton
                        val provider = providerManager.getProviderByType(internalProvider)
                        resetStates()
                        scope.launch {
                            launch {
                                runCatching {
                                    nonStreamingState = UiState.Loading
                                    val chunk = provider.generateText(
                                        providerSetting = internalProvider,
                                        messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("hello")),
                                        params = TextGenerationParams(
                                            model = model!!,
                                            customHeaders = model!!.customHeaders,
                                            customBody = model!!.customBodies
                                        )
                                    )
                                    val text = chunk.choices.firstOrNull()?.message?.parts
                                        ?.filterIsInstance<UIMessagePart.Text>()
                                        ?.joinToString("") { it.text } ?: ""
                                    nonStreamingState = UiState.Success(text)
                                }.onFailure { nonStreamingState = UiState.Error(it) }
                            }
                            launch {
                                runCatching {
                                    streamingState = UiState.Loading
                                    val flow = provider.streamText(
                                        providerSetting = internalProvider,
                                        messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("hello")),
                                        params = TextGenerationParams(
                                            model = model!!,
                                            customHeaders = model!!.customHeaders,
                                            customBody = model!!.customBodies
                                        )
                                    )
                                    flow.collect { chunk ->
                                        chunk.choices.firstOrNull()?.delta?.parts
                                            ?.filterIsInstance<UIMessagePart.Text>()
                                            ?.forEach { streamingText += it.text }
                                    }
                                    streamingState = UiState.Success("")
                                }.onFailure { streamingState = UiState.Error(it) }
                            }
                            launch {
                                runCatching {
                                    toolsState = UiState.Loading
                                    val testTool = Tool(
                                        name = "get_current_time",
                                        description = "Get the current date and time.",
                                        execute = { emptyList() }
                                    )
                                    val chunk = provider.generateText(
                                        providerSetting = internalProvider,
                                        messages = listOf(UIMessage.system("You are a helpful assistant"), UIMessage.user("Use the get_current_time tool.")),
                                        params = TextGenerationParams(
                                            model = model!!,
                                            tools = listOf(testTool),
                                            customHeaders = model!!.customHeaders,
                                            customBody = model!!.customBodies
                                        )
                                    )
                                    val message = chunk.choices.firstOrNull()?.message
                                    val toolCall = message?.parts
                                        ?.filterIsInstance<UIMessagePart.Tool>()
                                        ?.firstOrNull()
                                    val result = if (toolCall != null) {
                                        "调用: ${toolCall.toolName}  入参: ${toolCall.input}"
                                    } else {
                                        val text = message?.parts
                                            ?.filterIsInstance<UIMessagePart.Text>()
                                            ?.joinToString("") { it.text } ?: ""
                                        "未调用工具，响应: $text"
                                    }
                                    toolsState = UiState.Success(result)
                                }.onFailure { toolsState = UiState.Error(it) }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_test))
                }
            }
        )
    }
}

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String
) {
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        when (state) {
            is UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))
            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showErrorSheet = true }
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        val stackTrace = remember(state.error) {
            state.error.stackTraceToString()
        }
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6
                )
                Text(
                    text = stackTrace,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 本地 Provider 连接状态指示器
 *  - 绿色: 可达 (health check 200)
 *  - 红色: 不可达
 *  - 灰色: 检测中/不适用
 *
 * 仅对 baseUrl 包含 127.0.0.1 或 localhost 的 OpenAI Provider 显示
 */
@Composable
fun ProviderStatusDot(provider: ProviderSetting, modifier: Modifier = Modifier) {
    if (provider !is ProviderSetting.OpenAI) return
    val baseUrl = provider.baseUrl
    if (!baseUrl.contains("127.0.0.1") && !baseUrl.contains("localhost")) return

    var isOnline by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(baseUrl) {
        val client = HttpClient(OkHttp)
        while (true) {
            try {
                // 从 baseUrl 推导 health 端点: http://host:port/health
                val healthUrl = baseUrl.trimEnd('/')
                    .replace("/v1", "")
                    .replace("/api", "") + "/health"
                val response = client.get(healthUrl)
                isOnline = response.status.value == 200
            } catch (_: Exception) {
                isOnline = false
            }
            delay(30_000)
        }
    }

    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
                when (isOnline) {
                    true -> Color(0xFF4CAF50)   // Green
                    false -> Color(0xFFF44336)  // Red
                    null -> Color(0xFF9E9E9E)   // Gray
                }
            )
    )
}
