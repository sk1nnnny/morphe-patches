/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.TrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Allows hiding home feed and search results based on video title keywords and/or channel names.
 */
@SuppressWarnings({"unused", "unchecked"})
public final class KeywordContentFilter extends BufferPhraseFilter {

    private static final int MINIMUM_KEYWORD_LENGTH = 3;
    private static final String DEBUG_TAG = "KeywordFilter";
    private static volatile long lastToastTimestamp = 0;

    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            "comment_thread.eml"
    );

    private volatile String lastKeywordPhrasesParsed;
    private volatile ByteTrieSearch bufferSearch;

    private static boolean phraseUsesWholeWordSyntax(String phrase) {
        return phrase.startsWith("\"") && phrase.endsWith("\"");
    }

    private static String stripWholeWordSyntax(String phrase) {
        return phrase.substring(1, phrase.length() - 1);
    }

    /**
     * Извлекает читаемую UTF-8 строку из Protobuf-буфера.
     * Прерывается на любых управляющих байтах и тегах Protobuf (< 32 или 127).
     */
    @Nullable
    private static String getEnclosingTextSpan(byte[] buffer, int matchStart, int matchLength) {
        int start = matchStart;
        int minStart = Math.max(0, matchStart - 500);
        while (start > minStart) {
            int ub = buffer[start - 1] & 0xFF;
            if (ub < 32 || ub == 127) {
                break;
            }
            start--;
        }

        int end = matchStart + matchLength;
        int maxLen = Math.min(buffer.length, matchStart + matchLength + 500);
        while (end < maxLen) {
            int ub = buffer[end] & 0xFF;
            if (ub < 32 || ub == 127) {
                break;
            }
            end++;
        }

        if (end <= start) return null;
        try {
            return new String(buffer, start, end - start, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Фильтрует системные URL, аппаратные кодеки, разметку Litho и Base64-токены.
     */
    private static boolean isIgnoredTechnicalString(String span) {
        if (span.isEmpty()) return true;
        String s = span.toLowerCase(Locale.ROOT);

        // URL плеера, CDN и запросы
        if (s.startsWith("http://") || s.startsWith("https://")
                || s.contains("googlevideo.com") || s.contains(".ytimg.com")
                || s.contains("initplayback") || s.contains("youtube.com/")
                || s.contains("youtubei/v1/") || s.contains("ggpht.com")) {
            return true;
        }

        // Кодеки
        if (s.startsWith("omx.") || s.startsWith("c2.") || s.contains(".decoder")) {
            return true;
        }

        // Компоненты верстки и внутренние теги Litho
        if (s.startsWith("video_lockup") || s.startsWith("compact_video")
                || s.startsWith("shorts_") || s.startsWith("modern_type_shelf")
                || s.startsWith("expandable_metadata") || s.startsWith("thumbnail.")
                || s.startsWith("avatar.") || s.startsWith("overflow_button.")
                || s.contains(".eml")) {
            return true;
        }

        // Шрифты
        if (s.contains("youtubesans") || s.contains("sans-serif")) {
            return true;
        }

        // Изображения
        if (s.endsWith(".jpg") || s.endsWith(".png") || s.endsWith(".webp")) {
            return true;
        }

        // URL-параметры или токены без пробелов (key=val, a&b, id+hash)
        if ((span.contains("=") || span.contains("&") || span.contains("%") || span.contains("+")) && !span.contains(" ")) {
            return true;
        }

        return false;
    }

    /**
     * Проверяет, является ли символ естественным разделителем слов в человеческом тексте.
     * Символы URL и кода (_, /, &, =, +, %, цифры) разделителями НЕ считаются.
     */
    private static boolean isNaturalWordDelimiter(int cp) {
        if (Character.isWhitespace(cp)) {
            return true;
        }
        return switch (cp) {
            case '.', ',', '!', '?', ':', ';', '"', '\'', '(', ')', '[', ']', '{', '}',
                 '«', '»', '—', '–', '-', '…' -> true;
            default -> false;
        };
    }

    /**
     * Проверяет совпадение как отдельного самостоятельного слова в тексте.
     */
    private static boolean isCleanWordMatch(String span, String keyword) {
        String spanLower = span.toLowerCase(Locale.ROOT);
        String keywordLower = keyword.toLowerCase(Locale.ROOT);
        int kwLen = keywordLower.length();

        int idx = 0;
        while ((idx = spanLower.indexOf(keywordLower, idx)) != -1) {
            int cpBefore = idx > 0 ? span.codePointBefore(idx) : -1;
            int nextIdx = idx + kwLen;
            int cpAfter = nextIdx < span.length() ? span.codePointAt(nextIdx) : -1;

            boolean beforeOk = (cpBefore == -1 || isNaturalWordDelimiter(cpBefore));
            boolean afterOk = (cpAfter == -1 || isNaturalWordDelimiter(cpAfter));

            if (beforeOk && afterOk) {
                return true;
            }
            idx++;
        }
        return false;
    }

    /**
     * Комплексная валидация совпадения в буфере.
     */
    private static boolean isMatchValid(byte[] buffer, int startIndex, int matchLength, String keyword, boolean isWholeWord) {
        String span = getEnclosingTextSpan(buffer, startIndex, matchLength);
        if (span == null || isIgnoredTechnicalString(span)) {
            return false;
        }

        if (!isWholeWord) {
            return span.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
        }

        return isCleanWordMatch(span, keyword);
    }

    private static int indexOfBytes(byte[] data, byte[] pattern, int fromIndex) {
        final int dl = data.length;
        final int pl = pattern.length;
        if (pl == 0) return -1;
        for (int i = Math.max(0, fromIndex), end = dl - pl; i <= end; i++) {
            if (data[i] != pattern[0]) continue;
            int j = 1;
            while (j < pl && data[i + j] == pattern[j]) j++;
            if (j == pl) return i;
        }
        return -1;
    }

    private static String extractSnippetForDebug(byte[] buffer, @Nullable String keyword) {
        if (keyword == null) return "unknown";
        byte[] kwBytes = keyword.getBytes(StandardCharsets.UTF_8);
        int idx = indexOfBytes(buffer, kwBytes, 0);
        if (idx == -1) return keyword;
        String span = getEnclosingTextSpan(buffer, idx, kwBytes.length);
        if (span == null || span.isEmpty()) return keyword;
        span = span.trim().replaceAll("\\s+", " ");
        if (span.length() > 60) {
            return span.substring(0, 60) + "...";
        }
        return span;
    }

    private synchronized void parseKeywords() {
        String rawKeywords = Settings.HIDE_KEYWORD_CONTENT_PHRASES.get();

        //noinspection StringEquality
        if (rawKeywords == lastKeywordPhrasesParsed) {
            Logger.printDebug(() -> "Using previously initialized search");
            return;
        }

        ByteTrieSearch search = new ByteTrieSearch();
        String[] split = rawKeywords.split("\n");
        if (split.length != 0) {
            Map<String, Boolean> keywords = new LinkedHashMap<>(10 * split.length);

            for (String phrase : split) {
                phrase = phrase.strip();
                if (phrase.isEmpty()) continue;

                final boolean wholeWordMatching;
                if (phraseUsesWholeWordSyntax(phrase)) {
                    if (phrase.length() == 2) {
                        continue;
                    }
                    phrase = stripWholeWordSyntax(phrase);
                    wholeWordMatching = true;
                } else if (phrase.length() < MINIMUM_KEYWORD_LENGTH && !isLanguageWithNoSpaces(phrase)) {
                    Utils.showToastLong(str("morphe_hide_keyword_toast_invalid_length", phrase, MINIMUM_KEYWORD_LENGTH));
                    continue;
                } else {
                    wholeWordMatching = false;
                }

                Locale defaultLocale = Locale.getDefault();
                String[] phraseVariations = {
                        phrase,
                        phrase.toLowerCase(Locale.ROOT),
                        phrase.toLowerCase(defaultLocale),
                        titleCaseFirstWordOnly(phrase),
                        capitalizeAllFirstLetters(phrase),
                        phrase.toUpperCase(Locale.ROOT),
                        phrase.toUpperCase(defaultLocale)
                };

                if (phrasesWillHideAllVideos(phraseVariations, wholeWordMatching)) {
                    String toastMessage = (!wholeWordMatching && !phrasesWillHideAllVideos(phraseVariations, true))
                            ? "morphe_hide_keyword_toast_invalid_common_whole_word_required"
                            : "morphe_hide_keyword_toast_invalid_common";
                    Utils.showToastLong(str(toastMessage, phrase));
                    continue;
                }

                for (String variation : phraseVariations) {
                    Boolean existing = keywords.get(variation);
                    if (existing == null) {
                        keywords.put(variation, wholeWordMatching);
                    } else if (existing != wholeWordMatching) {
                        Utils.showToastLong(str("morphe_hide_keyword_toast_invalid_conflicting", phrase));
                        break;
                    }
                }
            }

            for (Map.Entry<String, Boolean> entry : keywords.entrySet()) {
                String keyword = entry.getKey();
                final boolean isWholeWord = entry.getValue();

                TrieSearch.TriePatternMatchedCallback<byte[]> callback =
                        (textSearched, startIndex, matchLength, callbackParameter) -> {
                            if (!isMatchValid(textSearched, startIndex, matchLength, keyword, isWholeWord)) {
                                return false;
                            }

                            Logger.printDebug(() -> (isWholeWord ? "Matched whole keyword: '"
                                    : "Matched keyword: '") + keyword + "'");
                            //noinspection unchecked
                            ((MutableReference<String>) callbackParameter).value = keyword;
                            return true;
                        };
                byte[] stringBytes = keyword.getBytes(StandardCharsets.UTF_8);
                search.addPattern(stringBytes, callback);
            }

            Logger.printDebug(() -> "Search using: (" + search.getEstimatedMemorySize() + " KB) keywords: " + keywords.keySet());
        }

        bufferSearch = search;
        lastKeywordPhrasesParsed = rawKeywords;
    }

    public KeywordContentFilter() {
        super();
        addPathCallbacks(commentsFilter);
    }

    @Override
    protected void reparseIfNeeded() {
        //noinspection StringEquality
        if (Settings.HIDE_KEYWORD_CONTENT_PHRASES.get() != lastKeywordPhrasesParsed) {
            parseKeywords();
        }
    }

    @Override
    protected boolean isActiveForFeedContext() {
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return Settings.HIDE_KEYWORD_CONTENT_HOME.get();
        }

        if (NavigationBar.isSearchBarActive()) {
            return Settings.HIDE_KEYWORD_CONTENT_SEARCH.get();
        }

        final boolean hideHome = Settings.HIDE_KEYWORD_CONTENT_HOME.get();
        final boolean hideSubscriptions = Settings.HIDE_KEYWORD_CONTENT_SUBSCRIPTIONS.get();
        if (!hideHome && !hideSubscriptions) {
            return false;
        }

        NavigationBar.NavigationButton selectedNavButton = NavigationBar.NavigationButton.getSelectedNavigationButton();
        if (selectedNavButton == null) {
            return hideHome;
        }

        return switch (selectedNavButton) {
            case HOME -> hideHome;
            case SUBSCRIPTIONS -> hideSubscriptions;
            default -> false;
        };
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup) {
        ByteTrieSearch search = bufferSearch;
        if (search == null) return null;
        MutableReference<String> matchRef = new MutableReference<>();
        if (!search.matches(buffer, matchRef)) return null;

        String keyword = matchRef.value;
        recordHide(matchedGroup, buffer);

        // DEBUG: логирование в Logcat и показ тоста на экране
        String snippet = extractSnippetForDebug(buffer, keyword);
        Log.e(DEBUG_TAG, "FILTERED! keyword='" + keyword + "', in text: '" + snippet + "'");

        long now = System.currentTimeMillis();
        if (now - lastToastTimestamp > 1500) {
            lastToastTimestamp = now;
            Utils.showToastLong("Скрыто [" + keyword + "]: " + snippet);
        }

        return keyword;
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showToastLong(str("morphe_hide_keyword_toast_invalid_broad", matched));
    }

    private void recordHide(StringFilterGroup matchedGroup, byte[] buffer) {
        Source source = detectSource(matchedGroup);
        String videoId = getVideoIdForSource(source, buffer);
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup) {
        if (matchedGroup == commentsFilter) return Source.COMMENTS;
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationBar.NavigationButton nav = NavigationBar.NavigationButton.getSelectedNavigationButton();
        return nav == NavigationBar.NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
    }

    @Nullable
    private static String getVideoIdForSource(Source source, byte[] buffer) {
        if (source == Source.COMMENTS) {
            String id = VideoInformation.getVideoId();
            return id.isEmpty() ? null : id;
        }
        return extractVideoIdFromBuffer(buffer);
    }

    private static LongSetting allTimeCounterFor(Source source) {
        return switch (source) {
            case HOME -> Settings.KEYWORD_HIDE_COUNT_HOME;
            case SUBSCRIPTIONS -> Settings.KEYWORD_HIDE_COUNT_SUBSCRIPTIONS;
            case SEARCH -> Settings.KEYWORD_HIDE_COUNT_SEARCH;
            case COMMENTS -> Settings.KEYWORD_HIDE_COUNT_COMMENTS;
        };
    }

    public static int hidesInLast24Hours() {
        return sharedTracker.totalSize(System.currentTimeMillis());
    }

    public static int hidesInLast24Hours(Source source) {
        return sharedTracker.sourceSize(source, System.currentTimeMillis());
    }

    public static void resetHidesTracker() {
        sharedTracker.reset();
    }

    private static final BufferHideStatsTracker sharedTracker =
            new BufferHideStatsTracker(Settings.KEYWORD_HIDES_24H);
}
