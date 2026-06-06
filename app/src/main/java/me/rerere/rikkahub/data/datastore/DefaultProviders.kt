package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * 默认提供商列表 — 本地模型直连模式
 *
 * 修改说明：移除了所有云端默认提供商（OpenAI、Claude、Google等），
 * 直接连接 llama.cpp（127.0.0.1:8080），去掉中间 bridge 代理，
 * 减少链路损耗、提高响应速度。
 *
 * 云端提供商仍可通过 UI 中的「添加提供商」手动添加。
 * RikkaHub 原生支持 OpenAI、Google、Claude 三种类型。
 */

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"),
        name = "本地模型",
        baseUrl = "http://127.0.0.1:8080/v1",
        apiKey = "not-needed",
        enabled = true,
        builtIn = true,
        models = listOf(
            Model(
                id = DEFAULT_AUTO_MODEL_ID,
                modelId = "auto",
                displayName = "Auto（自动检测）",
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(),
            )
        ),
        description = {
            // 空描述，UI 中显示默认文本
        },
        shortDescription = {
            // 空描述
        },
    ),
)
