package com.fongmi.android.tv.ai.subtitle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Translation provider allowlist for the AutoBuild-170-style settings screen.
 *
 * Keep only actively supported choices here. Legacy/unknown stored values resolve
 * to GOOGLE so a stale preference can never reach a removed provider's dispatcher.
 */
public enum AiSubtitleTranslationProvider {
    SOURCE_ONLY("source_only", "僅顯示辨識原文", false, false),
    BING("bing", "Bing 翻譯", true, false),
    DEEPL("deepl", "DeepL 翻譯", true, false),
    GOOGLE("google", "Google 翻譯", true, false),
    MTRAN("mtran", "MTranServer", true, true),
    OPENAI("openai", "大型語言模型（OpenAI 相容）", true, true);

    private final String id;
    private final String label;
    private final boolean translates;
    private final boolean needsUserConfiguration;

    AiSubtitleTranslationProvider(String id, String label, boolean translates, boolean needsUserConfiguration) {
        this.id = id;
        this.label = label;
        this.translates = translates;
        this.needsUserConfiguration = needsUserConfiguration;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public boolean translates() {
        return translates;
    }

    public boolean needsUserConfiguration() {
        return needsUserConfiguration;
    }

    public static AiSubtitleTranslationProvider fromStoredValue(String value) {
        if (value != null) {
            String key = value.trim().toLowerCase(Locale.ROOT);
            for (AiSubtitleTranslationProvider provider : values()) {
                if (provider.id.equals(key) || provider.name().toLowerCase(Locale.ROOT).equals(key)) {
                    return provider;
                }
            }
        }
        // Safe default for empty, legacy or unsupported saved preferences.
        return GOOGLE;
    }

    public static List<AiSubtitleTranslationProvider> selectorOptions() {
        List<AiSubtitleTranslationProvider> result = new ArrayList<>();
        Collections.addAll(result, values());
        return Collections.unmodifiableList(result);
    }

    public static List<AiSubtitleTranslationProvider> translationBackendsOnly() {
        List<AiSubtitleTranslationProvider> result = new ArrayList<>();
        for (AiSubtitleTranslationProvider provider : values()) {
            if (provider.translates) result.add(provider);
        }
        return Collections.unmodifiableList(result);
    }
}
