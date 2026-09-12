/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.components;

import static java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
import static java.lang.Character.UnicodeBlock.HIRAGANA;
import static java.lang.Character.UnicodeBlock.KATAKANA;
import static java.lang.Character.UnicodeBlock.KHMER;
import static java.lang.Character.UnicodeBlock.LAO;
import static java.lang.Character.UnicodeBlock.MYANMAR;
import static java.lang.Character.UnicodeBlock.THAI;
import static java.lang.Character.UnicodeBlock.TIBETAN;

import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;

/**
 * Abstract base for Litho filters that scan the protocol buffer for phrase matches
 * inside feed/search video cards. Owns the shared path filter groups, exception
 * trie, broad-filter detection, and UTF-8 utilities. Subclasses supply the
 * per-invocation phrase-matching logic and per-tab activation gating.
 */
@SuppressWarnings("unused")
public abstract class BufferPhraseFilter extends Filter {

    private static final String DEBUG_TAG = "KeywordFilter";
    private static volatile long lastToastTime = 0;

    /**
     * Strings found in the buffer for every video. Full strings should be specified.
     */
    public static final String[] STRINGS_IN_EVERY_BUFFER = {
            // Video playback data.
            "googlevideo.com/initplayback?source=youtube",
            "ANDROID",
            "https://i.ytimg.com/vi/",
            "mqdefault.jpg",
            "hqdefault.jpg",
            "sddefault.jpg",
            "hq720.jpg",
            "webp",
            "_custom_",
            // Video decoders.
            "OMX.ffmpeg.vp9.decoder",
            "OMX.Intel.sw_vd.vp9",
            "OMX.MTK.VIDEO.DECODER.SW.VP9",
            "OMX.google.vp9.decoder",
            "OMX.google.av1.decoder",
            "OMX.sprd.av1.decoder",
            "c2.android.av1.decoder",
            "c2.android.av1-dav1d.decoder",
            "c2.android.vp9.decoder",
            "c2.mtk.sw.vp9.decoder",
            // Analytics.
            "searchR",
            "browse-feed",
            "FEwhat_to_watch",
            "FEsubscriptions",
            "search_vwc_description_transition_key",
            "g-high-recZ",
            // Text and litho components.
            "expandable_metadata.e",
            "thumbnail.e",
            "avatar.e",
            "overflow_button.e",
            "shorts-lockup-image",
            "shorts-lockup.overlay-metadata.secondary-text",
            "YouTubeSans-SemiBold",
            "sans-serif"
    };

    private static final float ALL_VIDEOS_FILTERED_THRESHOLD = 0.95f;
    private static final float ALL_VIDEOS_FILTERED_SAMPLE_SIZE = 50;
    private static final long ALL_VIDEOS_FILTERED_BACKOFF_MILLISECONDS = 60 * 1000;
    private static final int UTF8_MAX_BYTE_COUNT = 4;

    protected final StringFilterGroup startsWithFilter = new StringFilterGroup(
            null,
            "home_video_with_context.e",
            "search_video_with_context.e",
            "video_with_context.e",
            "related_video_with_context.e",
            "video_lockup_with_attachment.e",
            "compact_video.e",
            "inline_shorts",
            "shorts_video_cell",
            "shorts_pivot_item.e"
    );

    @SuppressWarnings("FieldCanBeLocal")
    protected final StringFilterGroup containsFilter = new StringFilterGroup(
            null,
            "modern_type_shelf_header_content.e",
            "shorts_lockup_cell.e",
            "video_card.e"
    );

    private final StringTrieSearch exceptions = new StringTrieSearch(
            "metadata.e",
            "thumbnail.e",
            "avatar.e",
            "overflow_button.e"
    );

    private volatile float filteredVideosPercentage;
    private volatile long timeToResumeFiltering;

    protected BufferPhraseFilter(StringFilterGroup... extraPathCallbacks) {
        StringFilterGroup[] all = new StringFilterGroup[2 + extraPathCallbacks.length];
        int idx = 0;
        all[idx++] = startsWithFilter;
        all[idx++] = containsFilter;
        System.arraycopy(extraPathCallbacks, 0, all, 2, extraPathCallbacks.length);
        addPathCallbacks(all);
    }

    protected abstract void reparseIfNeeded();
    protected abstract boolean isActiveForFeedContext();

    @Nullable
    protected abstract String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup);

    protected void onHideConfirmed(String matched) {
        // Default no-op.
    }

    @Override
    public final boolean isFiltered(ContextInterface contextInterface,
                                    String identifier,
                                    String accessibility,
                                    String path,
                                    byte[] buffer,
                                    BufferAsciiStrings asciiStrings,
                                    StringFilterGroup matchedGroup,
                                    FilterContentType contentType,
                                    int contentIndex) {
        if (contentIndex != 0 && matchedGroup == startsWithFilter) {
            return false;
        }

        reparseIfNeeded();

        if (isBaseFeedGroup(matchedGroup) && !isActiveForFeedContextGuarded()) {
            return false;
        }

        if (exceptions.matches(path)) {
            return false;
        }

        String matched = matchBuffer(buffer, matchedGroup);
        if (matched != null) {
            updateStats(true, matched);
            onHideConfirmed(matched);

            // DEBUG: гарантированный вывод в Logcat и Toast на экран
            Log.e(DEBUG_TAG, "!!! ВИДЕО СКРЫТО фильтром: [" + matched + "]");
            long now = System.currentTimeMillis();
            if (now - lastToastTime > 1500) {
                lastToastTime = now;
                Utils.showToastLong("Скрыто: [" + matched + "]");
            }

            return true;
        }

        updateStats(false, null);
        return false;
    }

    private boolean isBaseFeedGroup(StringFilterGroup group) {
        return group == startsWithFilter || group == containsFilter;
    }

    private boolean isActiveForFeedContextGuarded() {
        if (timeToResumeFiltering != 0) {
            if (System.currentTimeMillis() < timeToResumeFiltering) {
                return false;
            }
            timeToResumeFiltering = 0;
            filteredVideosPercentage = 0;
            Logger.printDebug(() -> "Resuming filtering: " + getClass().getSimpleName());
        }
        return isActiveForFeedContext();
    }

    private void updateStats(boolean videoWasHidden, @Nullable String matched) {
        float updatedAverage = filteredVideosPercentage
                * ((ALL_VIDEOS_FILTERED_SAMPLE_SIZE - 1) / ALL_VIDEOS_FILTERED_SAMPLE_SIZE);
        if (videoWasHidden) {
            updatedAverage += 1 / ALL_VIDEOS_FILTERED_SAMPLE_SIZE;
        }

        if (updatedAverage <= ALL_VIDEOS_FILTERED_THRESHOLD) {
            filteredVideosPercentage = updatedAverage;
            return;
        }

        timeToResumeFiltering = System.currentTimeMillis() + ALL_VIDEOS_FILTERED_BACKOFF_MILLISECONDS;
        Logger.printDebug(() -> "Temporarily turning off filtering due to excessively broad match: " + matched);
        onBroadFilterDetected(matched);
    }

    protected void onBroadFilterDetected(@Nullable String matched) {
        // Default no-op.
    }

    public static boolean isLanguageWithNoSpaces(String text) {
        for (int i = 0, length = text.length(); i < length;) {
            final int codePoint = text.codePointAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            if (block == CJK_UNIFIED_IDEOGRAPHS
                    || block == HIRAGANA
                    || block == KATAKANA
                    || block == THAI
                    || block == LAO
                    || block == MYANMAR
                    || block == KHMER
                    || block == TIBETAN) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public static String titleCaseFirstWordOnly(String sentence) {
        if (sentence.isEmpty()) return sentence;
        final int firstCodePoint = sentence.codePointAt(0);
        return new StringBuilder()
                .appendCodePoint(Character.toTitleCase(firstCodePoint))
                .append(sentence, Character.charCount(firstCodePoint), sentence.length())
                .toString();
    }

    public static String capitalizeAllFirstLetters(String sentence) {
        if (sentence.isEmpty()) return sentence;
        final int delimiter = ' ';
        int[] codePoints = sentence.codePoints().toArray();
        boolean capitalizeNext = true;
        for (int i = 0, length = codePoints.length; i < length; i++) {
            final int codePoint = codePoints[i];
            if (codePoint == delimiter) {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                codePoints[i] = Character.toUpperCase(codePoint);
                capitalizeNext = false;
            }
        }
        return new String(codePoints, 0, codePoints.length);
    }

    public static boolean phrasesWillHideAllVideos(String[] phrases, boolean matchWholeWords) {
        for (String phrase : phrases) {
            for (String commonString : STRINGS_IN_EVERY_BUFFER) {
                if (matchWholeWords) {
                    byte[] commonStringBytes = commonString.getBytes(StandardCharsets.UTF_8);
                    int matchIndex = 0;
                    while (true) {
                        matchIndex = commonString.indexOf(phrase, matchIndex);
                        if (matchIndex < 0) break;
                        if (keywordMatchIsWholeWord(commonStringBytes, matchIndex, phrase.length())) {
                            return true;
                        }
                        matchIndex++;
                    }
                } else if (Utils.containsAny(commonString, phrases)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Границей слова могут быть ТОЛЬКО пробелы и естественная пунктуация предложения.
     * Бинарные байты, цифры и символы URL (_, /, &, =, +, %) границами слова НЕ являются.
     */
    private static boolean isWordBoundary(@Nullable Integer cp) {
        if (cp == null) {
            return false;
        }
        int c = cp;
        if (Character.isWhitespace(c)) {
            return true;
        }
        return switch (c) {
            case '.', ',', '!', '?', ':', ';', '"', '\'', '(', ')', '[', ']', '{', '}',
                 '«', '»', '—', '–', '-', '…' -> true;
            default -> false;
        };
    }

    /**
     * Проверяет, что ключевое слово является самостоятельным отдельным словом в человеческом тексте.
     */
    public static boolean keywordMatchIsWholeWord(byte[] text, int keywordStartIndex, int keywordLength) {
        final Integer codePointBefore = getUtf8CodePointBefore(text, keywordStartIndex);
        if (!isWordBoundary(codePointBefore)) {
            return false;
        }

        final Integer codePointAfter = getUtf8CodePointAt(text, keywordStartIndex + keywordLength);
        if (!isWordBoundary(codePointAfter)) {
            return false;
        }

        return true;
    }

    @Nullable
    public static Integer getUtf8CodePointBefore(byte[] data, int index) {
        int characterByteCount = 0;
        while (--index >= 0 && ++characterByteCount <= UTF8_MAX_BYTE_COUNT) {
            if (isValidUtf8(data, index, characterByteCount)) {
                return decodeUtf8ToCodePoint(data, index, characterByteCount);
            }
        }
        return null;
    }

    @Nullable
    public static Integer getUtf8CodePointAt(byte[] data, int index) {
        int characterByteCount = 0;
        final int dataLength = data.length;
        while (index + characterByteCount < dataLength && ++characterByteCount <= UTF8_MAX_BYTE_COUNT) {
            if (isValidUtf8(data, index, characterByteCount)) {
                return decodeUtf8ToCodePoint(data, index, characterByteCount);
            }
        }
        return null;
    }

    public static boolean isValidUtf8(byte[] data, int startIndex, int numberOfBytes) {
        switch (numberOfBytes) {
            case 1 -> {
                return (data[startIndex] & 0x80) == 0;
            }
            case 2 -> {
                return (data[startIndex] & 0xE0) == 0xC0
                        && (data[startIndex + 1] & 0xC0) == 0x80;
            }
            case 3 -> {
                return (data[startIndex] & 0xF0) == 0xE0
                        && (data[startIndex + 1] & 0xC0) == 0x80
                        && (data[startIndex + 2] & 0xC0) == 0x80;
            }
            case 4 -> {
                return (data[startIndex] & 0xF8) == 0xF0
                        && (data[startIndex + 1] & 0xC0) == 0x80
                        && (data[startIndex + 2] & 0xC0) == 0x80
                        && (data[startIndex + 3] & 0xC0) == 0x80;
            }
        }
        throw new IllegalArgumentException("numberOfBytes: " + numberOfBytes);
    }

    public static int decodeUtf8ToCodePoint(byte[] data, int startIndex, int numberOfBytes) {
        switch (numberOfBytes) {
            case 1 -> {
                return data[startIndex];
            }
            case 2 -> {
                return ((data[startIndex] & 0x1F) << 6) |
                        (data[startIndex + 1] & 0x3F);
            }
            case 3 -> {
                return ((data[startIndex] & 0x0F) << 12) |
                        ((data[startIndex + 1] & 0x3F) << 6) |
                        (data[startIndex + 2] & 0x3F);
            }
            case 4 -> {
                return ((data[startIndex] & 0x07) << 18) |
                        ((data[startIndex + 1] & 0x3F) << 12) |
                        ((data[startIndex + 2] & 0x3F) << 6) |
                        (data[startIndex + 3] & 0x3F);
            }
        }
        throw new IllegalArgumentException("numberOfBytes: " + numberOfBytes);
    }

    public enum Source {
        HOME, SUBSCRIPTIONS, SEARCH, COMMENTS;
        public static final Source[] VALUES = values();
    }

    public static final int VIDEO_ID_LENGTH = 11;
    public static final byte[] THUMBNAIL_URL_PREFIX =
            "https://i.ytimg.com/vi/".getBytes(StandardCharsets.US_ASCII);

    @Nullable
    public static String extractVideoIdFromBuffer(byte[] buffer) {
        final byte[] prefix = THUMBNAIL_URL_PREFIX;
        final int prefixLen = prefix.length;
        outer:
        for (int i = 0, max = buffer.length - prefixLen - VIDEO_ID_LENGTH; i <= max; i++) {
            for (int j = 0; j < prefixLen; j++) {
                if (buffer[i + j] != prefix[j]) continue outer;
            }
            int start = i + prefixLen;
            for (int k = 0; k < VIDEO_ID_LENGTH; k++) {
                if (!isVideoIdChar(buffer[start + k])) continue outer;
            }
            return new String(buffer, start, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static boolean isVideoIdChar(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_';
    }

    public static final class MutableReference<T> {
        public T value;
    }
}
