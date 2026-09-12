/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

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
     * Извлекает окружающий UTF-8 текст из сырого Protobuf-буфера,
     * отсекая бинарные байты разметки (< 0x20).
     */
    @Nullable
    private static String getEnclosingTextSpan(byte[] buffer, int matchStart, int matchLength) {
        int start = matchStart;
        int minStart = Math.max(0, matchStart - 1024);
        while (start > minStart) {
            byte b = buffer[start - 1];
            if (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\r') {
                break;
            }
            start--;
        }

        int end = matchStart + matchLength;
        int maxLen = Math.min(buffer.length, matchStart + matchLength + 1024);
        while (end < maxLen) {
            byte b = buffer[end];
            if (b >= 0 && b < 0x20 && b != '\t' && b != '\n' && b != '\r') {
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
     * Проверяет, не относится ли найденная строка к техническим URL, кодекам,
     * элементам верстки Litho или токенам отслеживания.
     */
    private static boolean isIgnoredTechnicalString(String span) {
        if (span.isEmpty()) return true;
        String s = span.toLowerCase(Locale.ROOT);

        // URL плеера, CDN и запросы
        if (s.startsWith("http://") || s.startsWith("https://")
                || s.contains("googlevideo.com") || s.contains(".ytimg.com")
                || s.contains("initplayback") || s.contains("youtube.com/")
                || s.contains("youtubei/v1/")) {
            return true;
        }

        // Аппаратные и программные кодеки
        if (s.startsWith("omx.") || s.startsWith("c2.") || s.contains(".decoder")) {
            return true;
        }

        // Теги и идентификаторы компонентов Litho
        if (s.startsWith("video_lockup") || s.startsWith("compact_video")
                || s.startsWith("shorts_") || s.startsWith("modern_type_shelf")
                || s.startsWith("expandable_metadata") || s.startsWith("thumbnail.")
                || s.startsWith("avatar.") || s.startsWith("overflow_button.")) {
            return true;
        }

        // Шрифты
        if (s.contains("youtubesans") || s.contains("sans-serif")) {
            return true;
        }

        // URL query параметры или Base64-токены без пробелов (например, key=val, a&b)
        if ((span.contains("=") || span.contains("&") || span.contains("?")) && !span.contains(" ")) {
            return true;
        }

        // Расширения файлов изображений
        if (s.endsWith(".jpg") || s.endsWith(".png") || s.endsWith(".webp")) {
            return true;
        }

        return false;
    }

    /**
     * Честная Unicode-проверка целого слова (работает одинаково для кириллицы и латиницы).
     */
    private static boolean isValidWholeWord(String span, String keyword) {
        String spanLower = span.toLowerCase(Locale.ROOT);
        String keywordLower = keyword.toLowerCase(Locale.ROOT);
        int kwLen = keywordLower.length();
        int idx = 0;

        while ((idx = spanLower.indexOf(keywordLower, idx)) != -1) {
            int cpBefore = idx > 0 ? span.codePointBefore(idx) : -1;
            int nextIdx = idx + kwLen;
            int cpAfter = nextIdx < span.length() ? span.codePointAt(nextIdx) : -1;

            boolean boundaryBefore = (cpBefore == -1 || (!Character.isLetterOrDigit(cpBefore) && cpBefore != '_'));
            boolean boundaryAfter = (cpAfter == -1 || (!Character.isLetterOrDigit(cpAfter) && cpAfter != '_'));

            if (boundaryBefore && boundaryAfter) {
                return true;
            }
            idx++;
        }

        return false;
    }

    /**
     * Валидация совпадения в буфере: отсеивает ложные срабатывания в бинарных данных.
     */
    private static boolean isMatchValid(byte[] buffer, int startIndex, int matchLength, String keyword, boolean isWholeWord) {
        String span = getEnclosingTextSpan(buffer, startIndex, matchLength);
        if (span == null || isIgnoredTechnicalString(span)) {
            return false;
        }

        if (!isWholeWord) {
            return span.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
        }

        return isValidWholeWord(span, keyword);
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
                            // Заменяем сломанную проверку на безопасную валидацию в реальном тексте
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
        recordHide(matchedGroup, buffer);
        return matchRef.value;
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
