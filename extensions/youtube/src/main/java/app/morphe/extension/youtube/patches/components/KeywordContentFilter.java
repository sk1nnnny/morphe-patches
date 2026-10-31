/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original first edition code:
 * https://github.com/ReVanced/revanced-integrations/pull/584
 * https://github.com/ReVanced/revanced-integrations/commit/0cbad9820577c476f1f29b6ac77611b38afbb950
 * https://github.com/ReVanced/revanced-integrations/commit/1ee99aa6f0b4af15eeca25c7e21e8a0f5e9d189a
 * https://github.com/ReVanced/revanced-integrations/commit/c3bfa77d62b15dedfed8f697583f2f0805f0c2c1
 * https://github.com/ReVanced/revanced-integrations/commit/75fa5797f70123f68d4676201503cf35dcef46dc
 * https://github.com/ReVanced/revanced-integrations/commit/3a3ceec4b596354dcccbf3516ef1634bd8819b90
 * https://github.com/ReVanced/revanced-integrations/commit/cda1f3160c12d239df1183799ead39526cbac20f
 * https://github.com/ReVanced/revanced-integrations/commit/d8d2a852d3879060bd95cc43d66c7cf195e82b43
 * https://github.com/ReVanced/revanced-integrations/commit/2f2eeea5a722b6b7053eb2825d16fa37938b4e9e
 * https://github.com/ReVanced/revanced-integrations/commit/5314dd90d16dc8565331c4cddce114956d85a173
 * https://github.com/MorpheApp/morphe-patches/commit/f5371ca998c019609c2b5558b3408ab1fec065c8
 * https://github.com/MorpheApp/morphe-patches/commit/017eac71a3f9542b8ad6221e3600797d6b97fae4
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.patches.TextComponentPatch.newSpanUsingStylingOfAnotherSpan;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Allows hiding or dimming (in debug mode) home feed, subscriptions, search results, and comments
 * based on video title keywords, channel names/handles, or full regular expressions, and optionally
 * scans opened video subtitles to display matched phrases natively in the video description.
 */
@SuppressWarnings({"unused", "unchecked"})
public final class KeywordContentFilter extends BufferPhraseFilter {

    /**
     * Minimum keyword/phrase length to prevent excessively broad content filtering.
     * Only applies when not using whole word syntax or explicit regex.
     */
    private static final int MINIMUM_KEYWORD_LENGTH = 3;

    /**
     * Protobuf field numbers in YouTube's Litho elements.
     */
    private static final int TEXT_CONTENT_FIELD_NUMBER = 1;
    private static final int STYLE_RUNS_FIELD_NUMBER = 3;
    private static final int COMMAND_RUNS_FIELD_NUMBER = 4;
    private static final int ATTACHMENT_RUNS_FIELD_NUMBER = 5;
    private static final int INNERTUBE_COMMAND_FIELD_NUMBER = 169495254;
    private static final int BROWSE_ENDPOINT_FIELD_NUMBER = 48687626;
    private static final int LOCKUP_METADATA_VIEW_MODEL_FIELD_NUMBER = 8178;
    private static final int LOCKUP_METADATA_TITLE_FIELD_NUMBER = 1;
    private static final int LOCKUP_METADATA_CONTENT_FIELD_NUMBER = 3;
    private static final int BUTTON_VIEW_MODEL_FIELD_NUMBER = 461054335;
    private static final int TOGGLE_BUTTON_VIEW_MODEL_FIELD_NUMBER = 479644279;
    private static final int LIKE_BUTTON_VIEW_MODEL_FIELD_NUMBER = 484585818;
    private static final int ADD_TO_PLAYLIST_BUTTON_VIEW_MODEL_FIELD_NUMBER = 484598023;
    private static final int RENDERER_CONTEXT_FIELD_NUMBER = 997;
    private static final int LOGGING_DIRECTIVES_FIELD_NUMBER = 999;

    private static final String COMMENT_THREAD_PATH = "comment_thread.eml";

    /** Litho component identifier pattern, such as 'video_lockup_with_attachment.eml-fe|c0c4a49b6544b5fb'. */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[^\\s|]+\\.[^\\s|]+\\|[0-9a-f]{1,16}$");

    /** YouTube channel ID pattern (UC followed by 22 base64-url chars). */
    private static final Pattern CHANNEL_ID_PATTERN =
            Pattern.compile("^UC[A-Za-z0-9_-]{22}$");

    /** Base64 entity key pattern. */
    private static final Pattern ENTITY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{14,}(?:%3D|=)*$");

    /** Short duration badge text such as 4:05 or 1:02:33. */
    private static final Pattern DURATION_BADGE_PATTERN =
            Pattern.compile("^(?:[^\\p{L}\\p{N}])?\\d{1,2}(?:[:.]\\d{2}){1,2}$");

    /** Regex metacharacters that trigger regex compilation when the Regex setting is enabled. */
    private static final Pattern REGEX_META_PATTERN =
            Pattern.compile("[\\\\()\\[\\]|^$*+?{}]");

    /** Technical single-word ASCII tokens that appear in buffers and are not user-visible titles. */
    private static final Set<String> TECHNICAL_SINGLE_WORDS = Set.of(
            "ANDROID",
            "webp",
            "searchR",
            "browse-feed",
            "FEwhat_to_watch",
            "FEsubscriptions",
            "FEstorefront",
            "FEhistory",
            "FElibrary",
            "g-high-recZ",
            "shortswatch",
            "watch",
            "browse",
            "search",
            "next",
            "player",
            "default",
            "mqdefault",
            "hqdefault",
            "sddefault",
            "hq720",
            "oardefault",
            "frame0",
            "oar1",
            "oar2",
            "oar3",
            "sans-serif",
            "sans-serif-medium",
            "Roboto"
    );

    /** Localized UI action labels inside comments that should never be treated as comment body text. */
    private static final Set<String> COMMENT_UI_LABELS = Set.of(
            "ответить",
            "перевести",
            "перевести на русский",
            "показать оригинал",
            "показать ответы",
            "скрыть ответы",
            "читать дальше",
            "свернуть",
            "ещё",
            "еще",
            "нравится",
            "не нравится",
            "действия",
            "поделиться",
            "пожаловаться",
            "закреплено",
            "(изменено)",
            "изменено",
            "reply",
            "translate",
            "view replies",
            "hide replies",
            "read more",
            "show less"
    );

    private static final Set<String> COMMON_BUFFER_STRINGS;
    static {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, STRINGS_IN_EVERY_BUFFER);
        COMMON_BUFFER_STRINGS = Collections.unmodifiableSet(set);
    }

    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            COMMENT_THREAD_PATH
    );

    /**
     * The button that shows the replies of a comment ("Показать ответы") is outside comment_thread.eml
     * and shares comment/reply IDs with its parent comment_thread.eml.
     */
    private final StringFilterGroup commentRepliesFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            "repl"
    );

    /** State of settings when rules were last parsed. */
    private volatile String lastKeywordPhrasesParsed;
    private volatile boolean lastUseAndOperator;
    private volatile boolean lastUseRegex;
    private volatile boolean lastCaseSensitive;
    private static final AtomicInteger rulesVersionCounter = new AtomicInteger(1);
    private volatile int currentRulesVersion = 1;

    /** Compiled keyword rules in user-specified order. */
    private volatile List<KeywordRule> parsedRules = Collections.emptyList();

    /** Active singleton instance so static hooks can access rules. */
    @Nullable
    private static volatile KeywordContentFilter instance;

    // region Comment Thread & Replies Decision Tracking (prevents orphaned "Show replies" buttons)

    private static final int MIN_COMMENT_ID_LENGTH = 24;
    private static final int MAX_IDS_PER_COMMENT = 40;
    private static final int MAX_COMMENT_IDS = 4000;

    private static final class CommentDecision {
        final String path;
        final int rulesVersion;
        @Nullable
        final String matchedWord;
        final boolean hidden;
        boolean hideReplies;

        CommentDecision(String path, int rulesVersion, @Nullable String matchedWord, boolean hidden) {
            this.path = path;
            this.rulesVersion = rulesVersion;
            this.matchedWord = matchedWord;
            this.hidden = hidden;
            this.hideReplies = hidden;
        }
    }

    private static final CommentDecision AMBIGUOUS_COMMENT_ID =
            new CommentDecision("", 0, null, false);
    private static final ThreadLocal<CommentDecision> lastCommentDecision = new ThreadLocal<>();
    private static final Map<String, CommentDecision> commentDecisionsById =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_COMMENT_IDS));

    // endregion

    /**
     * Details of a match for Debug Mode and Match History Log.
     */
    public record MatchLogEntry(
            long timestampMs,
            Source source,
            String matchedWord,
            String matchedText,
            String ruleDisplay,
            @Nullable String videoId,
            boolean debugMode
    ) {}

    private static final int MAX_MATCH_LOG_ENTRIES = 60;
    private static final Deque<MatchLogEntry> recentMatchLogs = new ArrayDeque<>(MAX_MATCH_LOG_ENTRIES);
    private static final Set<String> recentLoggedKeys = new HashSet<>();

    /**
     * Thread-safe LRU cache mapping the exact matched text to its {@link MatchDetails}.
     */
    private record MatchDetails(String matchedWord, String matchedText, String ruleDisplay) {}

    private static final int MAX_DEBUG_CACHE_SIZE = 1000;
    private static final Map<String, MatchDetails> debugMatchedTextMap =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_DEBUG_CACHE_SIZE, true));

    /** Root ComponentHosts that currently have a DebugCardOverlay attached. Accessed on main thread. */
    private static final Map<ComponentHost, DebugCardOverlay> debugOverlays = new WeakHashMap<>();
    private static final Map<ComponentHost, Boolean> pendingHostsToUpdate =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean hostsUpdateScheduled;

    /**
     * Represents a single condition within a line (or the entire line when '&' is not used).
     */
    private static final class Clause {
        final String rawClause;
        final boolean isRegex;
        final boolean isWholeWord;
        final boolean caseSensitive;
        @Nullable
        final Pattern regexPattern;
        final String literalPhrase;

        Clause(String rawClause, boolean isRegex, boolean isWholeWord, boolean caseSensitive,
               @Nullable Pattern regexPattern, String literalPhrase) {
            this.rawClause = rawClause;
            this.isRegex = isRegex;
            this.isWholeWord = isWholeWord;
            this.caseSensitive = caseSensitive;
            this.regexPattern = regexPattern;
            this.literalPhrase = literalPhrase;
        }

        @Nullable
        String findFirstMatchedWord(String text) {
            if (text.isEmpty()) return null;
            if (isRegex && regexPattern != null) {
                Matcher m = regexPattern.matcher(text);
                while (m.find()) {
                    String g = m.group();
                    if (!g.isEmpty()) {
                        return g;
                    }
                }
                return null;
            }
            int[] range = findLiteralRange(text, 0);
            if (range != null) {
                return text.substring(range[0], range[1]);
            }
            return null;
        }

        void collectMatchRanges(String text, List<int[]> outRanges) {
            if (text.isEmpty()) return;
            if (isRegex && regexPattern != null) {
                Matcher m = regexPattern.matcher(text);
                while (m.find()) {
                    if (m.end() > m.start()) {
                        outRanges.add(new int[]{m.start(), m.end()});
                    }
                }
                return;
            }
            int from = 0;
            while (from < text.length()) {
                int[] range = findLiteralRange(text, from);
                if (range == null) break;
                outRanges.add(range);
                from = Math.max(from + 1, range[1]);
            }
        }

        @Nullable
        private int[] findLiteralRange(String text, int startFrom) {
            final int phraseLen = literalPhrase.length();
            final int textLen = text.length();
            if (phraseLen == 0 || startFrom + phraseLen > textLen) {
                return null;
            }
            int from = startFrom;
            while (from + phraseLen <= textLen) {
                int idx = indexOfRegion(text, literalPhrase, from, caseSensitive);
                if (idx < 0) {
                    return null;
                }
                if (!isWholeWord || keywordMatchIsWholeWord(text, idx, phraseLen)) {
                    return new int[]{idx, idx + phraseLen};
                }
                from = idx + 1;
            }
            return null;
        }

        private static int indexOfRegion(String haystack, String needle, int fromIndex, boolean caseSensitive) {
            if (caseSensitive) {
                return haystack.indexOf(needle, fromIndex);
            }
            final int needleLen = needle.length();
            final int max = haystack.length() - needleLen;
            for (int i = Math.max(0, fromIndex); i <= max; i++) {
                if (haystack.regionMatches(true, i, needle, 0, needleLen)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Represents a complete single-line rule (consisting of 1 or more AND-ed {@link Clause}s).
     */
    private static final class KeywordRule {
        final String displayRule;
        final List<Clause> clauses;

        KeywordRule(String displayRule, List<Clause> clauses) {
            this.displayRule = displayRule;
            this.clauses = clauses;
        }

        @Nullable
        List<MatchDetails> matchAcrossTexts(List<String> texts) {
            List<MatchDetails> matchedDetails = new ArrayList<>(clauses.size());
            for (int i = 0, cSize = clauses.size(); i < cSize; i++) {
                Clause clause = clauses.get(i);
                MatchDetails found = null;
                for (int j = 0, tSize = texts.size(); j < tSize; j++) {
                    String t = texts.get(j);
                    String word = clause.findFirstMatchedWord(t);
                    if (word != null) {
                        found = new MatchDetails(word, t, displayRule);
                        break;
                    }
                }
                if (found == null) {
                    return null;
                }
                matchedDetails.add(found);
            }
            return matchedDetails;
        }

        void collectMatchRangesInText(String text, boolean allowPartialAnd, List<int[]> outRanges) {
            if (clauses.size() == 1) {
                clauses.get(0).collectMatchRanges(text, outRanges);
                return;
            }
            List<int[]> temp = new ArrayList<>();
            int clausesMatched = 0;
            for (int i = 0, cSize = clauses.size(); i < cSize; i++) {
                int before = temp.size();
                clauses.get(i).collectMatchRanges(text, temp);
                if (temp.size() > before) {
                    clausesMatched++;
                }
            }
            if (clausesMatched == clauses.size() || (allowPartialAnd && clausesMatched > 0)) {
                outRanges.addAll(temp);
            }
        }
    }

    private record DebugRuleSpan(String matchedWord, String matchedText, String ruleDisplay) {}

    private static boolean phraseUsesWholeWordSyntax(String phrase) {
        return phrase.length() >= 2 && phrase.startsWith("\"") && phrase.endsWith("\"");
    }

    private static String stripWholeWordSyntax(String phrase) {
        return phrase.substring(1, phrase.length() - 1);
    }

    /**
     * Rewrites regex character class escapes (\w, \W, \b, \B) into Unicode-aware equivalents
     * so Cyrillic and other non-ASCII scripts work properly on Android.
     */
    public static String toUnicodeRegex(String pattern) {
        final String wordChars = "\\p{L}\\p{M}\\p{N}_";
        final String wordClass = "[" + wordChars + "]";
        final String nonWordClass = "[^" + wordChars + "]";
        final String wordBoundary = "(?:(?<=" + wordClass + ")(?!" + wordClass + ")|(?<!" + wordClass + ")(?=" + wordClass + "))";
        final String nonWordBoundary = "(?:(?<=" + wordClass + ")(?=" + wordClass + ")|(?<!" + wordClass + ")(?!" + wordClass + "))";

        StringBuilder out = new StringBuilder(pattern.length() + 32);
        int inCharClass = 0;
        final int n = pattern.length();
        int i = 0;
        while (i < n) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 < n) {
                    char nxt = pattern.charAt(i + 1);
                    if (nxt == 'w') {
                        out.append(inCharClass > 0 ? wordChars : wordClass);
                        i += 2;
                        continue;
                    } else if (nxt == 'W' && inCharClass == 0) {
                        out.append(nonWordClass);
                        i += 2;
                        continue;
                    } else if (nxt == 'b' && inCharClass == 0) {
                        out.append(wordBoundary);
                        i += 2;
                        continue;
                    } else if (nxt == 'B' && inCharClass == 0) {
                        out.append(nonWordBoundary);
                        i += 2;
                        continue;
                    } else {
                        out.append(c).append(nxt);
                        i += 2;
                        continue;
                    }
                } else {
                    out.append(c);
                    i++;
                    continue;
                }
            }
            if (c == '[') {
                inCharClass++;
            } else if (c == ']' && inCharClass > 0) {
                inCharClass--;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    @Nullable
    private static Clause parseSingleClause(String rawClause, boolean useRegexSetting, boolean caseSensitiveSetting) {
        String trimmed = rawClause.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (useRegexSetting && (trimmed.startsWith("/") || trimmed.regionMatches(true, 0, "regex:", 0, 6)
                || trimmed.regionMatches(true, 0, "re:", 0, 3))) {
            String regexBody = null;
            boolean forceCaseSensitive = caseSensitiveSetting;
            boolean forceCaseInsensitive = !caseSensitiveSetting;

            if (trimmed.startsWith("/")) {
                int lastSlash = trimmed.lastIndexOf('/');
                if (lastSlash > 0) {
                    regexBody = trimmed.substring(1, lastSlash);
                    String flagsStr = trimmed.substring(lastSlash + 1).toLowerCase(Locale.ROOT);
                    if (flagsStr.contains("i")) {
                        forceCaseInsensitive = true;
                        forceCaseSensitive = false;
                    }
                    if (flagsStr.contains("c") || flagsStr.contains("s")) {
                        forceCaseSensitive = true;
                        forceCaseInsensitive = false;
                    }
                }
            } else if (trimmed.regionMatches(true, 0, "regex:", 0, 6)) {
                regexBody = trimmed.substring(6).trim();
            } else if (trimmed.regionMatches(true, 0, "re:", 0, 3)) {
                regexBody = trimmed.substring(3).trim();
            }

            if (regexBody != null && !regexBody.isEmpty()) {
                try {
                    int flags = Pattern.UNICODE_CASE | Pattern.DOTALL | Pattern.MULTILINE;
                    if (forceCaseInsensitive && !forceCaseSensitive) {
                        flags |= Pattern.CASE_INSENSITIVE;
                    }
                    Pattern pattern = Pattern.compile(toUnicodeRegex(regexBody), flags);
                    return new Clause(trimmed, true, false, forceCaseSensitive, pattern, regexBody);
                } catch (PatternSyntaxException ex) {
                    Logger.printDebug(() -> "Invalid regex keyword pattern: " + trimmed, ex);
                    Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_regex", trimmed));
                    return null;
                }
            }
        }

        if (phraseUsesWholeWordSyntax(trimmed)) {
            String stripped = stripWholeWordSyntax(trimmed);
            if (stripped.isEmpty()) {
                return null;
            }
            return new Clause(trimmed, false, true, caseSensitiveSetting, null, stripped);
        }

        if (useRegexSetting && REGEX_META_PATTERN.matcher(trimmed).find()) {
            try {
                int flags = Pattern.UNICODE_CASE | Pattern.DOTALL | Pattern.MULTILINE;
                if (!caseSensitiveSetting) {
                    flags |= Pattern.CASE_INSENSITIVE;
                }
                Pattern pattern = Pattern.compile(toUnicodeRegex(trimmed), flags);
                return new Clause(trimmed, true, false, caseSensitiveSetting, pattern, trimmed);
            } catch (PatternSyntaxException ex) {
                Logger.printDebug(() -> "Invalid regex keyword pattern: " + trimmed, ex);
                Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_regex", trimmed));
                return null;
            }
        }

        if (trimmed.length() < MINIMUM_KEYWORD_LENGTH && !isLanguageWithNoSpaces(trimmed)) {
            Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_length", trimmed, MINIMUM_KEYWORD_LENGTH));
            return null;
        }

        return new Clause(trimmed, false, false, caseSensitiveSetting, null, trimmed);
    }

    private synchronized void parseKeywords() {
        String rawKeywords = Settings.HIDE_KEYWORD_CONTENT_PHRASES.get();
        boolean useAnd = Settings.HIDE_KEYWORD_CONTENT_USE_AND_OPERATOR.get();
        boolean useRegex = Settings.HIDE_KEYWORD_CONTENT_USE_REGEX.get();
        boolean caseSensitive = Settings.HIDE_KEYWORD_CONTENT_CASE_SENSITIVE.get();

        //noinspection StringEquality
        if (rawKeywords == lastKeywordPhrasesParsed
                && useAnd == lastUseAndOperator
                && useRegex == lastUseRegex
                && caseSensitive == lastCaseSensitive) {
            return;
        }

        List<KeywordRule> rules = new ArrayList<>();
        Map<String, String> seenCanonicalRules = new LinkedHashMap<>();

        String[] lines = rawKeywords.split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            String[] rawClauses = useAnd ? splitByTopLevelAmpersand(line) : new String[]{line};
            List<Clause> parsedClauses = new ArrayList<>(rawClauses.length);
            boolean lineValid = true;

            for (String rawClause : rawClauses) {
                Clause clause = parseSingleClause(rawClause, useRegex, caseSensitive);
                if (clause == null) {
                    lineValid = false;
                    break;
                }
                parsedClauses.add(clause);
            }

            if (!lineValid || parsedClauses.isEmpty()) {
                continue;
            }

            StringBuilder canonicalKey = new StringBuilder();
            for (int i = 0; i < parsedClauses.size(); i++) {
                if (i > 0) canonicalKey.append(" & ");
                Clause c = parsedClauses.get(i);
                canonicalKey.append(c.isRegex ? "R:" : (c.isWholeWord ? "W:" : "S:"))
                        .append(c.caseSensitive ? "CS:" : "CI:")
                        .append(c.caseSensitive ? c.literalPhrase : c.literalPhrase.toLowerCase(Locale.ROOT));
            }
            String key = canonicalKey.toString();
            if (seenCanonicalRules.containsKey(key)) {
                continue;
            }
            seenCanonicalRules.put(key, line);
            rules.add(new KeywordRule(line, parsedClauses));
        }

        debugMatchedTextMap.clear();
        commentDecisionsById.clear();
        subtitleScanCache.clear();
        parsedRules = Collections.unmodifiableList(rules);
        currentRulesVersion = rulesVersionCounter.incrementAndGet();
        lastKeywordPhrasesParsed = rawKeywords;
        lastUseAndOperator = useAnd;
        lastUseRegex = useRegex;
        lastCaseSensitive = caseSensitive;
    }

    private static String[] splitByTopLevelAmpersand(String line) {
        if (line.indexOf('&') < 0) {
            return new String[]{line};
        }
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        boolean inSlashRegex = false;
        int parenDepth = 0;
        int bracketDepth = 0;
        int start = 0;

        for (int i = 0, len = line.length(); i < len; i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < len) {
                i++;
                continue;
            }
            if (c == '"' && !inSlashRegex && bracketDepth == 0) {
                inQuotes = !inQuotes;
            } else if (c == '/' && !inQuotes && bracketDepth == 0 && (i == start || inSlashRegex)) {
                inSlashRegex = !inSlashRegex;
            } else if (!inQuotes && !inSlashRegex) {
                if (c == '[') bracketDepth++;
                else if (c == ']' && bracketDepth > 0) bracketDepth--;
                else if (c == '(' && bracketDepth == 0) parenDepth++;
                else if (c == ')' && bracketDepth == 0 && parenDepth > 0) parenDepth--;
                else if (c == '&' && bracketDepth == 0 && parenDepth == 0) {
                    parts.add(line.substring(start, i));
                    start = i + 1;
                }
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }

    public KeywordContentFilter() {
        super();
        instance = this;
        addPathCallbacks(commentsFilter, commentRepliesFilter);
    }

    @Override
    protected void reparseIfNeeded() {
        //noinspection StringEquality
        if (Settings.HIDE_KEYWORD_CONTENT_PHRASES.get() != lastKeywordPhrasesParsed
                || Settings.HIDE_KEYWORD_CONTENT_USE_AND_OPERATOR.get() != lastUseAndOperator
                || Settings.HIDE_KEYWORD_CONTENT_USE_REGEX.get() != lastUseRegex
                || Settings.HIDE_KEYWORD_CONTENT_CASE_SENSITIVE.get() != lastCaseSensitive) {
            parseKeywords();
        }
    }

    @Override
    protected boolean isDebugModeEnabled() {
        return Settings.HIDE_KEYWORD_CONTENT_DEBUG.get();
    }

    @Override
    protected boolean isActiveForFeedContext(ContextInterface contextInterface) {
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return Settings.HIDE_KEYWORD_CONTENT_HOME.get();
        }
        if (NavigationBar.isSearchBarActive()) {
            return Settings.HIDE_KEYWORD_CONTENT_SEARCH.get();
        }
        if (!NavigationBar.isBackButtonVisible()) {
            NavigationBar.NavigationButton selectedNavButton =
                    NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
            if (selectedNavButton == null) {
                return Settings.HIDE_KEYWORD_CONTENT_HOME.get();
            }
            return switch (selectedNavButton) {
                case HOME -> Settings.HIDE_KEYWORD_CONTENT_HOME.get();
                case SUBSCRIPTIONS -> Settings.HIDE_KEYWORD_CONTENT_SUBSCRIPTIONS.get();
                default -> false;
            };
        }
        return false;
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup,
                                 ContextInterface contextInterface) {
        return matchBuffer(buffer, "", "", matchedGroup, contextInterface);
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, String accessibility, CharSequence path,
                                 StringFilterGroup matchedGroup, ContextInterface contextInterface) {
        List<KeywordRule> rules = parsedRules;
        if (rules.isEmpty()) {
            return null;
        }

        final int rulesVer = currentRulesVersion;
        final boolean debug = isDebugModeEnabled();

        // 1. Handle the "Show replies" ("Показать ответы") button of comments:
        // Never scan the replies button's own text; only hide it if its parent comment_thread was hidden.
        if (matchedGroup == commentRepliesFilter) {
            if (debug || Utils.contains(path, COMMENT_THREAD_PATH)) {
                return null;
            }
            return isCommentRepliesHidden(buffer, rulesVer, lastCommentDecision.get()) ? "" : null;
        }

        // 2. Handle comment threads (comment_thread.eml):
        // Only evaluate the outermost comment_thread.eml once; inner components reuse the decision.
        if (matchedGroup == commentsFilter) {
            CommentDecision last = lastCommentDecision.get();
            if (last != null && last.rulesVersion == rulesVer
                    && path.length() > last.path.length() && Utils.startsWith(path, last.path)) {
                return last.matchedWord;
            }
            if (buffer == null || buffer.length == 0) {
                return null;
            }

            String activeVideoId = VideoInformation.getVideoId();
            String videoId = activeVideoId.isEmpty() ? null : activeVideoId;
            List<String> commentTexts = extractCommentTexts(buffer, videoId);
            String matchedWord = evaluateRulesAndRecord(commentTexts, rules, Source.COMMENTS, videoId, debug);

            CommentDecision decision = new CommentDecision(
                    path.toString(),
                    rulesVer,
                    matchedWord,
                    matchedWord != null && !debug
            );
            lastCommentDecision.set(decision);
            rememberCommentIds(buffer, decision);
            return matchedWord;
        }

        // 3. Standard feed / subscriptions / search video cards:
        String videoId = extractVideoIdFromBuffer(buffer);
        List<String> searchableTexts = extractSearchableTexts(buffer, accessibility, videoId);
        if (searchableTexts.isEmpty()) {
            return null;
        }

        Source source = detectSource(matchedGroup, contextInterface);
        return evaluateRulesAndRecord(searchableTexts, rules, source, videoId, debug);
    }

    @Nullable
    private String evaluateRulesAndRecord(List<String> searchableTexts, List<KeywordRule> rules,
                                          Source source, @Nullable String videoId, boolean debug) {
        if (searchableTexts.isEmpty()) {
            return null;
        }
        for (int i = 0, size = rules.size(); i < size; i++) {
            KeywordRule rule = rules.get(i);
            List<MatchDetails> matchedClauses = rule.matchAcrossTexts(searchableTexts);
            if (matchedClauses != null && !matchedClauses.isEmpty()) {
                MatchDetails primary = matchedClauses.get(0);
                for (int j = 0, cSize = matchedClauses.size(); j < cSize; j++) {
                    MatchDetails detail = matchedClauses.get(j);
                    debugMatchedTextMap.put(detail.matchedText(), detail);
                }
                recordMatchLog(source, primary.matchedWord(), primary.matchedText(),
                        rule.displayRule, videoId, debug);
                if (!debug) {
                    recordHide(source, videoId);
                }
                return primary.matchedWord();
            }
        }
        return null;
    }

    private static boolean isCommentRepliesHidden(byte[] buffer, int rulesVer,
                                                  @Nullable CommentDecision last) {
        CommentDecision comment = findCommentOfReplies(buffer, rulesVer);
        if (comment == null) {
            if (last == null || last.rulesVersion != rulesVer || !last.hideReplies) {
                return false;
            }
            comment = last;
        } else if (!comment.hidden) {
            return false;
        }
        comment.hideReplies = false;
        return true;
    }

    @Nullable
    private static CommentDecision findCommentOfReplies(byte[] buffer, int rulesVer) {
        CommentDecision hiddenComment = null;
        for (String id : findCommentIds(buffer)) {
            CommentDecision decision = commentDecisionsById.get(id);
            if (decision == null || decision == AMBIGUOUS_COMMENT_ID || decision.rulesVersion != rulesVer) {
                continue;
            }
            if (!decision.hidden) {
                return decision;
            }
            hiddenComment = decision;
        }
        return hiddenComment;
    }

    private static void rememberCommentIds(byte[] buffer, CommentDecision decision) {
        List<String> ids = findCommentIds(buffer);
        synchronized (commentDecisionsById) {
            for (String id : ids) {
                CommentDecision existing = commentDecisionsById.get(id);
                if (existing == AMBIGUOUS_COMMENT_ID) {
                    continue;
                }
                if (existing == null || existing.rulesVersion != decision.rulesVersion
                        || existing.hidden == decision.hidden) {
                    commentDecisionsById.put(id, decision);
                } else {
                    commentDecisionsById.put(id, AMBIGUOUS_COMMENT_ID);
                }
            }
        }
    }

    private static List<String> findCommentIds(byte[] buffer) {
        List<String> ids = new ArrayList<>();
        int start = -1;
        for (int i = 0, length = buffer.length; i <= length; i++) {
            if (i < length && isCommentIdChar(buffer[i])) {
                if (start < 0) start = i;
                continue;
            }
            if (start >= 0) {
                if (i - start >= MIN_COMMENT_ID_LENGTH) {
                    ids.add(new String(buffer, start, i - start, StandardCharsets.US_ASCII));
                    if (ids.size() >= MAX_IDS_PER_COMMENT) break;
                }
                start = -1;
            }
        }
        return ids;
    }

    private static boolean isCommentIdChar(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_';
    }

    private static void recordMatchLog(Source source, String matchedWord, String matchedText,
                                       String ruleDisplay, @Nullable String videoId, boolean debugMode) {
        Logger.printInfo(() -> "[KeywordFilter] " + (debugMode ? "DIMMED" : "HIDDEN")
                + " | Word: '" + matchedWord + "'"
                + " | Text: '" + matchedText + "'"
                + " | Source: " + source);

        String dedupKey = source + "|" + matchedText + "|" + matchedWord;
        synchronized (recentMatchLogs) {
            if (!recentLoggedKeys.add(dedupKey)) {
                return;
            }
            if (recentMatchLogs.size() >= MAX_MATCH_LOG_ENTRIES) {
                MatchLogEntry removed = recentMatchLogs.removeLast();
                String removedKey = removed.source() + "|" + removed.matchedText() + "|" + removed.matchedWord();
                recentLoggedKeys.remove(removedKey);
            }
            recentMatchLogs.addFirst(new MatchLogEntry(
                    System.currentTimeMillis(),
                    source,
                    matchedWord,
                    matchedText,
                    ruleDisplay,
                    videoId,
                    debugMode
            ));
        }
    }

    public static List<MatchLogEntry> getRecentMatchLogs() {
        synchronized (recentMatchLogs) {
            return new ArrayList<>(recentMatchLogs);
        }
    }

    public static void clearRecentMatchLogs() {
        synchronized (recentMatchLogs) {
            recentMatchLogs.clear();
            recentLoggedKeys.clear();
        }
    }

    /**
     * Extracts strictly the visible AttributedString texts from a comment thread buffer
     * (author @handle and comment body text), ignoring all binary fields, entity keys,
     * tracking tokens, and UI button labels.
     */
    public static List<String> extractCommentTexts(byte[] buffer, @Nullable String videoId) {
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (buffer == null || buffer.length == 0) {
            return results;
        }
        List<ProtoNode> root = ProtoNode.parse(buffer);
        if (root != null) {
            collectAttributedCommentTexts(root, videoId, results, seen);
        }
        return results;
    }

    private static void collectAttributedCommentTexts(List<ProtoNode> nodes, @Nullable String videoId,
                                                      List<String> out, Set<String> seen) {
        for (int i = 0, size = nodes.size(); i < size; i++) {
            ProtoNode node = nodes.get(i);
            final int fn = node.getFieldNumber();
            if (fn == INNERTUBE_COMMAND_FIELD_NUMBER
                    || fn == BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fn == TOGGLE_BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fn == LIKE_BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fn == ADD_TO_PLAYLIST_BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fn == RENDERER_CONTEXT_FIELD_NUMBER
                    || fn == LOGGING_DIRECTIVES_FIELD_NUMBER) {
                continue;
            }

            if (fn == TEXT_CONTENT_FIELD_NUMBER && isAttributedStringParent(node.getParent())) {
                if (node.isText()) {
                    addFilteredCommentText(node.getText(), videoId, out, seen);
                    continue;
                }
                String multiLine = decodeGenuineMultiLineText(node);
                if (multiLine != null) {
                    addFilteredCommentText(multiLine, videoId, out, seen);
                    continue;
                }
            }

            if (node.children != null) {
                collectAttributedCommentTexts(node.children, videoId, out, seen);
            }
        }
    }

    /**
     * Checks whether {@code parent} is a {@code youtube.elements.AttributedString} message:
     * has Field 1 (content), has Field 3 (style_runs) or Field 4/5 (command/attachment runs),
     * has only small field numbers (<= 12), and has only a single Field 1.
     */
    private static boolean isAttributedStringParent(@Nullable ProtoNode parent) {
        if (parent == null || parent.children == null || parent.children.size() < 2) {
            return false;
        }
        int field1Count = 0;
        boolean hasStyleOrRunField = false;
        for (int i = 0, size = parent.children.size(); i < size; i++) {
            ProtoNode child = parent.children.get(i);
            int fn = child.getFieldNumber();
            if (fn > 12) {
                return false;
            }
            if (fn == TEXT_CONTENT_FIELD_NUMBER) {
                field1Count++;
                if (field1Count > 1) return false;
            } else if (fn == STYLE_RUNS_FIELD_NUMBER
                    || fn == COMMAND_RUNS_FIELD_NUMBER
                    || fn == ATTACHMENT_RUNS_FIELD_NUMBER) {
                hasStyleOrRunField = true;
            }
        }
        return field1Count == 1 && hasStyleOrRunField;
    }

    @Nullable
    private static String decodeGenuineMultiLineText(ProtoNode node) {
        String decoded = node.decodeText();
        if (decoded == null || decoded.isEmpty()) {
            return null;
        }
        char first = decoded.charAt(0);
        if (first == '\n' || first == '\r' || first == '\t') {
            return null; // Protobuf message starting with Field 1 tag (0x0A, 0x0D, 0x09).
        }
        if (decoded.indexOf('\n') <= 0 || decoded.indexOf('\r') >= 0 || decoded.indexOf('\t') >= 0) {
            return null;
        }
        return decoded;
    }

    private static void addFilteredCommentText(@Nullable String rawText, @Nullable String videoId,
                                               List<String> out, Set<String> seen) {
        String cleaned = normalizeAndFilterUserText(rawText, videoId);
        if (cleaned == null) {
            return;
        }
        if (COMMENT_UI_LABELS.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return;
        }
        if (seen.add(cleaned)) {
            out.add(cleaned);
        }
    }

    /**
     * Extracts human-readable visible content texts (video title, channel name/metadata row,
     * @handle) from feed/search video card buffers.
     */
    public static List<String> extractSearchableTexts(byte[] buffer, @Nullable String accessibility,
                                                      @Nullable String videoId) {
        List<String> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (buffer != null && buffer.length > 0) {
            List<ProtoNode> root = ProtoNode.parse(buffer);
            if (root != null) {
                List<ProtoNode> lockupMetadataNodes = new ArrayList<>();
                findNodesByFieldNumber(root, LOCKUP_METADATA_VIEW_MODEL_FIELD_NUMBER, lockupMetadataNodes);

                if (!lockupMetadataNodes.isEmpty()) {
                    for (int i = 0, size = lockupMetadataNodes.size(); i < size; i++) {
                        ProtoNode metadataNode = lockupMetadataNodes.get(i);
                        if (metadataNode.children == null) continue;
                        for (int j = 0, cSize = metadataNode.children.size(); j < cSize; j++) {
                            ProtoNode child = metadataNode.children.get(j);
                            int fn = child.getFieldNumber();
                            if (fn == LOCKUP_METADATA_TITLE_FIELD_NUMBER
                                    || fn == LOCKUP_METADATA_CONTENT_FIELD_NUMBER) {
                                if (child.isText()) {
                                    addFilteredUserText(child.getText(), videoId, results, seen);
                                } else if (child.children != null) {
                                    collectReadableProtoTexts(child.children, videoId, results, seen);
                                }
                            }
                        }
                    }
                    collectChannelHandlesRecursive(root, videoId, results, seen);
                } else {
                    collectAttributedCommentTexts(root, videoId, results, seen);
                    if (results.isEmpty()) {
                        collectReadableProtoTexts(root, videoId, results, seen);
                    }
                }
            }
        }

        if (results.isEmpty() && accessibility != null && !accessibility.isEmpty()) {
            int pipeIndex = accessibility.indexOf('|');
            String a11yText = pipeIndex >= 0 ? accessibility.substring(pipeIndex + 1) : accessibility;
            addFilteredUserText(a11yText, videoId, results, seen);
        }

        return results;
    }

    private static void findNodesByFieldNumber(List<ProtoNode> nodes, int targetField, List<ProtoNode> out) {
        for (int i = 0, size = nodes.size(); i < size; i++) {
            ProtoNode node = nodes.get(i);
            if (node.getFieldNumber() == targetField) {
                out.add(node);
            } else if (node.children != null && node.getFieldNumber() != INNERTUBE_COMMAND_FIELD_NUMBER) {
                findNodesByFieldNumber(node.children, targetField, out);
            }
        }
    }

    private static void collectChannelHandlesRecursive(List<ProtoNode> nodes, @Nullable String videoId,
                                                       List<String> out, Set<String> seen) {
        for (int i = 0, size = nodes.size(); i < size; i++) {
            ProtoNode node = nodes.get(i);
            if (node.isText()) {
                String t = node.getText().trim();
                if (t.startsWith("/@") && t.length() > 2 && t.indexOf('/', 2) < 0) {
                    addFilteredUserText(t.substring(1), videoId, out, seen);
                }
            } else if (node.children != null) {
                collectChannelHandlesRecursive(node.children, videoId, out, seen);
            }
        }
    }

    private static void collectReadableProtoTexts(List<ProtoNode> nodes, @Nullable String videoId,
                                                  List<String> out, Set<String> seen) {
        for (int i = 0, size = nodes.size(); i < size; i++) {
            ProtoNode node = nodes.get(i);
            final int fieldNumber = node.getFieldNumber();

            if (fieldNumber == INNERTUBE_COMMAND_FIELD_NUMBER) {
                if (node.children != null) {
                    collectChannelHandlesFromCommand(node.children, videoId, out, seen);
                }
                continue;
            }
            if (fieldNumber == BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fieldNumber == TOGGLE_BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fieldNumber == LIKE_BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fieldNumber == ADD_TO_PLAYLIST_BUTTON_VIEW_MODEL_FIELD_NUMBER
                    || fieldNumber == RENDERER_CONTEXT_FIELD_NUMBER
                    || fieldNumber == LOGGING_DIRECTIVES_FIELD_NUMBER) {
                continue;
            }

            if (node.isText()) {
                addFilteredUserText(node.getText(), videoId, out, seen);
                continue;
            }

            if (node.children != null) {
                collectReadableProtoTexts(node.children, videoId, out, seen);
            } else if (fieldNumber == TEXT_CONTENT_FIELD_NUMBER) {
                String multiLine = decodeGenuineMultiLineText(node);
                if (multiLine != null) {
                    addFilteredUserText(multiLine, videoId, out, seen);
                }
            }
        }
    }

    private static void collectChannelHandlesFromCommand(List<ProtoNode> nodes, @Nullable String videoId,
                                                         List<String> out, Set<String> seen) {
        for (int i = 0, size = nodes.size(); i < size; i++) {
            ProtoNode node = nodes.get(i);
            if (node.isText()) {
                String t = node.getText().trim();
                if (t.startsWith("/@") && t.length() > 2 && t.indexOf('/', 2) < 0) {
                    addFilteredUserText(t.substring(1), videoId, out, seen);
                }
            } else if (node.children != null) {
                if (node.getFieldNumber() == BROWSE_ENDPOINT_FIELD_NUMBER || node.getFieldNumber() <= 10) {
                    collectChannelHandlesFromCommand(node.children, videoId, out, seen);
                }
            }
        }
    }

    private static void addFilteredUserText(@Nullable String rawText, @Nullable String videoId,
                                            List<String> out, Set<String> seen) {
        String cleaned = normalizeAndFilterUserText(rawText, videoId);
        if (cleaned != null && seen.add(cleaned)) {
            out.add(cleaned);
        }
    }

    public static String stripFormatCharacters(@Nullable CharSequence input) {
        if (input == null) return "";
        final int len = input.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (Character.getType(c) != Character.FORMAT) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    @Nullable
    public static String normalizeAndFilterUserText(@Nullable String rawText, @Nullable String videoId) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }
        char rawFirst = rawText.charAt(0);
        if (rawFirst == '\n' || rawFirst == '\r' || rawFirst == '\t'
                || rawText.indexOf('\r') >= 0 || rawText.indexOf('\t') >= 0) {
            return null;
        }
        String text = stripFormatCharacters(rawText);
        if (text.isEmpty()) {
            return null;
        }

        if (text.startsWith("/@") && text.length() > 2 && text.indexOf('/', 2) < 0 && !containsWhitespace(text)) {
            text = text.substring(1);
        }

        if (videoId != null && text.equals(videoId)) {
            return null;
        }

        if (COMMON_BUFFER_STRINGS.contains(text)
                || TECHNICAL_SINGLE_WORDS.contains(text)
                || COMMENT_UI_LABELS.contains(text.toLowerCase(Locale.ROOT))) {
            return null;
        }

        if (!containsLetter(text) || isDurationOrBadgeText(text)) {
            return null;
        }

        if (text.startsWith("http://")
                || text.startsWith("https://")
                || text.startsWith("android-app://")
                || text.startsWith("/")
                || text.startsWith("www.")
                || text.contains("://")
                || text.contains("googlevideo.com")
                || text.contains("ytimg.com")
                || text.contains("ggpht.com")
                || text.contains("youtube.com")
                || text.contains("/vi/")
                || text.contains("/vi_")
                || text.contains("initplayback")) {
            return null;
        }

        if (text.contains(".eml")
                || text.contains(".e|")
                || IDENTIFIER_PATTERN.matcher(text).matches()
                || ENTITY_KEY_PATTERN.matcher(text).matches()
                || text.startsWith("theme|")
                || text.startsWith("id.")
                || text.startsWith("yt_")
                || text.startsWith("ic_")
                || text.startsWith("quantum_ic_")
                || text.startsWith("shorts-shelf-item-")
                || text.startsWith("shorts-lockup")
                || text.startsWith("OMX.")
                || text.startsWith("c2.")
                || text.startsWith("YouTubeSans")
                || text.startsWith("Roboto-")
                || CHANNEL_ID_PATTERN.matcher(text).matches()) {
            return null;
        }

        if (isAllAscii(text) && looksLikeBinaryOrTechnicalAscii(text)) {
            return null;
        }

        return text;
    }

    /**
     * Filters out ASCII wire/binary fragments and technical tokens that ProtoNode.decodeText
     * can produce when all bytes of a sub-message fall within 0x20..0x7E.
     */
    private static boolean looksLikeBinaryOrTechnicalAscii(String text) {
        final int len = text.length();
        if (!containsWhitespace(text)) {
            if (text.charAt(0) == '@' || text.charAt(0) == '#') {
                if (len > 64) return true;
                for (int i = 1; i < len; i++) {
                    char c = text.charAt(i);
                    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                            || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) {
                        return true;
                    }
                }
                return false;
            }
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                    return true;
                }
            }
            if ((text.startsWith("FE") || text.startsWith("VL") || text.startsWith("PL") || text.startsWith("RD"))
                    && len > 4 && Character.isUpperCase(text.charAt(2))) {
                return true;
            }
            if (len >= 14 && hasInternalUppercaseTransition(text)) {
                return true;
            }
            return len == 11 && hasMultipleInternalUppercaseTransitions(text);
        }

        // Multi-token ASCII string: check for wire/binary symbols or Base64 chunks
        int openParen = 0;
        int quotes = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '`' || c == '~' || c == '^' || c == '\\' || c == '|'
                    || c == '{' || c == '}' || c == '<' || c == '>' || c == '=') {
                return true;
            }
            if (c == '(') openParen++;
            else if (c == ')') {
                openParen--;
                if (openParen < 0) return true;
            } else if (c == '"') {
                quotes++;
            }
        }
        return openParen != 0 || (quotes & 1) != 0;
    }

    private static boolean containsLetter(String text) {
        for (int i = 0, len = text.length(); i < len;) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0, len = text.length(); i < len; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u202F') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllAscii(String text) {
        for (int i = 0, len = text.length(); i < len; i++) {
            if (text.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    private static boolean hasInternalUppercaseTransition(String text) {
        boolean seenLower = false;
        for (int i = 1, len = text.length(); i < len; i++) {
            char c = text.charAt(i);
            if (Character.isLowerCase(c)) {
                seenLower = true;
            } else if (Character.isUpperCase(c) && seenLower) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMultipleInternalUppercaseTransitions(String text) {
        int transitions = 0;
        for (int i = 1, len = text.length(); i < len; i++) {
            boolean prevUpper = Character.isUpperCase(text.charAt(i - 1));
            boolean currUpper = Character.isUpperCase(text.charAt(i));
            if (prevUpper != currUpper) {
                transitions++;
                if (transitions >= 3) return true;
            }
        }
        return false;
    }

    private static boolean isDurationOrBadgeText(String text) {
        String trimmed = text.trim();
        if (trimmed.length() <= 12 && DURATION_BADGE_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.equals("LIVE") || trimmed.equals("4K") || trimmed.equals("HD") || trimmed.equals("CC");
    }

    // region Subtitle / Transcript Keyword Scanner (Native Description & Subtitle Info)

    private static final int SUBTITLE_CONNECT_TIMEOUT_MS = 8_000;
    private static final int SUBTITLE_READ_TIMEOUT_MS = 10_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final int MAX_SUBTITLE_MATCH_ITEMS = 5;
    private static final int MAX_TIMESTAMPS_PER_WORD = 3;
    private static final long MIN_TIMESTAMP_GAP_MS = 20_000L;
    private static final Pattern SUBTITLE_BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern SUBTITLE_PARENS_PATTERN = Pattern.compile("\\([^)]*\\)");

    public static final class SubtitleMatchItem {
        public final String matchedWord;
        public final String snippet;
        public final List<String> timestamps;
        long lastTimestampMs;

        SubtitleMatchItem(String matchedWord, String snippet, String firstTimestamp, long firstMs) {
            this.matchedWord = matchedWord;
            this.snippet = snippet;
            this.timestamps = new ArrayList<>(MAX_TIMESTAMPS_PER_WORD);
            this.timestamps.add(firstTimestamp);
            this.lastTimestampMs = firstMs;
        }
    }

    private static final class SubtitleScanState {
        volatile boolean pending = true;
        volatile List<SubtitleMatchItem> items = Collections.emptyList();
        @Nullable
        volatile String firstDescriptionSegment;
    }

    private static final Map<String, SubtitleScanState> subtitleScanCache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(80, true));

    private record SubtitleFormattedSpan(String videoId, int rulesVersion) {}

    private record PendingSubtitleRelayoutSpan(String cacheKey) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            SubtitleScanState state = subtitleScanCache.get(cacheKey);
            return state != null && !state.pending && !state.items.isEmpty();
        }
    }

    /**
     * Injection point called by {@code hookVideoId} when a video is opened.
     */
    public static void onVideoIdLoaded(@Nullable String videoId) {
        try {
            if (videoId == null || videoId.isEmpty() || !Settings.HIDE_KEYWORD_CONTENT_CHECK_SUBTITLES.get()) {
                return;
            }
            KeywordContentFilter filter = instance;
            if (filter == null) return;
            filter.reparseIfNeeded();
            List<KeywordRule> rules = filter.parsedRules;
            if (rules.isEmpty()) return;
            ensureSubtitleScanStarted(videoId, filter.currentRulesVersion, rules);
        } catch (Exception ex) {
            Logger.printException(() -> "KeywordContentFilter.onVideoIdLoaded failure", ex);
        }
    }

    private static SubtitleScanState ensureSubtitleScanStarted(String videoId, int rulesVer,
                                                               List<KeywordRule> rules) {
        String cacheKey = videoId + "|" + rulesVer;
        synchronized (subtitleScanCache) {
            SubtitleScanState existing = subtitleScanCache.get(cacheKey);
            if (existing != null) {
                return existing;
            }
            SubtitleScanState state = new SubtitleScanState();
            subtitleScanCache.put(cacheKey, state);
            Utils.runOnBackgroundThread(() -> runSubtitleScan(videoId, cacheKey, state, rules));
            return state;
        }
    }

    private static void runSubtitleScan(String videoId, String cacheKey,
                                        SubtitleScanState state, List<KeywordRule> rules) {
        try {
            List<SubtitleLine> lines = fetchSubtitleLines(videoId);
            if (lines.isEmpty()) {
                state.pending = false;
                return;
            }

            Map<String, SubtitleMatchItem> byWordKey = new LinkedHashMap<>();
            Set<String> seenSnippets = new HashSet<>();

            for (int i = 0, size = lines.size(); i < size; i++) {
                SubtitleLine line = lines.get(i);
                String text = line.text;
                if (text.length() < 2) continue;

                String matchedWord = findFirstRuleWordInSubtitle(text, rules);
                if (matchedWord == null) continue;

                String wordKey = matchedWord.toLowerCase(Locale.ROOT).trim();
                if (wordKey.isEmpty()) continue;

                SubtitleMatchItem existing = byWordKey.get(wordKey);
                if (existing != null) {
                    if (line.startMs - existing.lastTimestampMs >= MIN_TIMESTAMP_GAP_MS
                            && existing.timestamps.size() < MAX_TIMESTAMPS_PER_WORD) {
                        String ts = formatTimestamp(line.startMs);
                        if (!existing.timestamps.contains(ts)) {
                            existing.timestamps.add(ts);
                            existing.lastTimestampMs = line.startMs;
                        }
                    }
                    continue;
                }

                if (byWordKey.size() >= MAX_SUBTITLE_MATCH_ITEMS) {
                    continue;
                }

                String snippet = buildCleanSubtitleSnippet(text, matchedWord, 64);
                String snippetKey = snippet.toLowerCase(Locale.ROOT);
                if (!seenSnippets.add(snippetKey)) {
                    continue;
                }

                byWordKey.put(wordKey, new SubtitleMatchItem(
                        matchedWord,
                        snippet,
                        formatTimestamp(line.startMs),
                        line.startMs
                ));
            }

            if (!byWordKey.isEmpty()) {
                state.items = Collections.unmodifiableList(new ArrayList<>(byWordKey.values()));
                state.pending = false;
                LithoRelayoutPatch.relayoutOutdatedTexts();
            } else {
                state.pending = false;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitle scan failed for video: " + videoId, ex);
            state.pending = false;
        }
    }

    @Nullable
    private static String findFirstRuleWordInSubtitle(String text, List<KeywordRule> rules) {
        List<String> singleList = Collections.singletonList(text);
        for (int i = 0, size = rules.size(); i < size; i++) {
            List<MatchDetails> details = rules.get(i).matchAcrossTexts(singleList);
            if (details != null && !details.isEmpty()) {
                return details.get(0).matchedWord();
            }
        }
        return null;
    }

    private static String buildCleanSubtitleSnippet(String text, String matchedWord, int maxChars) {
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        int idx = cleaned.toLowerCase(Locale.ROOT).indexOf(matchedWord.toLowerCase(Locale.ROOT));
        if (idx < 0) {
            idx = 0;
        }
        int half = Math.max(10, (maxChars - matchedWord.length()) / 2);
        int start = Math.max(0, idx - half);
        int end = Math.min(cleaned.length(), start + maxChars);
        if (end == cleaned.length()) {
            start = Math.max(0, end - maxChars);
        }

        // Snap to whole-word boundaries so words are never cut in half
        if (start > 0) {
            int nextSpace = cleaned.indexOf(' ', start);
            if (nextSpace > start && nextSpace < idx) {
                start = nextSpace + 1;
            }
        }
        if (end < cleaned.length()) {
            int prevSpace = cleaned.lastIndexOf(' ', end);
            if (prevSpace > idx + matchedWord.length()) {
                end = prevSpace;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("…");
        sb.append(cleaned, start, end);
        if (end < cleaned.length()) sb.append("…");
        return sb.toString();
    }

    private static String formatTimestamp(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private record SubtitleLine(long startMs, String text) {}

    private static List<SubtitleLine> fetchSubtitleLines(String videoId) {
        Utils.verifyOffMainThread();
        String captionUrl = null;
        String poToken = null;
        try {
            String[] innertube = fetchCaptionUrlFromInnertube(videoId);
            captionUrl = innertube[0];
            poToken = innertube[1];
        } catch (Exception ex) {
            Logger.printDebug(() -> "Innertube caption lookup failed", ex);
        }

        if (captionUrl != null) {
            try {
                String baseUrl = captionUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
                if (poToken != null) baseUrl += "&pot=" + poToken;
                String json = fetchSubtitleHttp(baseUrl);
                List<SubtitleLine> parsed = parseJson3Subtitles(json);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Primary caption track fetch failed", ex);
            }
        }

        for (String lang : List.of("ru", "en", "en-US")) {
            try {
                String url = "https://www.youtube.com/api/timedtext?v=" + videoId
                        + "&lang=" + lang + "&kind=asr&fmt=json3";
                String json = fetchSubtitleHttp(url);
                if (!json.isEmpty()) {
                    List<SubtitleLine> parsed = parseJson3Subtitles(json);
                    if (!parsed.isEmpty()) {
                        return parsed;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return Collections.emptyList();
    }

    private static String[] fetchCaptionUrlFromInnertube(String videoId) throws Exception {
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\"}},"
                + "\"videoId\":\"" + videoId + "\"}";

        HttpURLConnection conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(SUBTITLE_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(SUBTITLE_READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent",
                "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
        conn.setRequestProperty("X-YouTube-Client-Name", "3");
        conn.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) {
            throw new Exception("Unexpected HTTP status: " + conn.getResponseCode());
        }

        String response = Requester.parseString(conn);
        return new String[]{selectBestCaptionTrackUrl(response), extractPoToken(response)};
    }

    @Nullable
    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) return null;
        idx += "\"poToken\":\"".length();
        int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    @Nullable
    private static String selectBestCaptionTrackUrl(String json) {
        int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) return null;

        String preferredLang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
        String firstUrl = null;
        String firstNonGemini = null;
        String preferredLangUrl = null;
        String ruUrl = null;
        int searchFrom = tracksIdx;

        while (true) {
            int baseUrlIdx = json.indexOf("\"baseUrl\":\"", searchFrom);
            if (baseUrlIdx < 0 || baseUrlIdx > tracksIdx + 50_000) break;
            baseUrlIdx += "\"baseUrl\":\"".length();

            int endIdx = json.indexOf('"', baseUrlIdx);
            if (endIdx < 0) break;

            String url = json.substring(baseUrlIdx, endIdx)
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("\\u003e", ">")
                    .replace("\\u003c", "<");

            if (firstUrl == null) firstUrl = url;
            boolean nonGemini = !url.contains("variant=gemini");
            if (firstNonGemini == null && nonGemini) firstNonGemini = url;

            String urlLang = extractLangParam(url).split("-")[0].toLowerCase(Locale.ROOT);
            if (preferredLangUrl == null && nonGemini && preferredLang.equals(urlLang)) {
                preferredLangUrl = url;
            }
            if (ruUrl == null && nonGemini && "ru".equals(urlLang)) {
                ruUrl = url;
            }

            searchFrom = endIdx + 1;
        }

        if (preferredLangUrl != null) return preferredLangUrl;
        if (ruUrl != null) return ruUrl;
        return firstNonGemini != null ? firstNonGemini : firstUrl;
    }

    private static String extractLangParam(String url) {
        for (String prefix : List.of("&lang=", "?lang=")) {
            int idx = url.indexOf(prefix);
            if (idx >= 0) {
                idx += prefix.length();
                int end = url.indexOf('&', idx);
                return end < 0 ? url.substring(idx) : url.substring(idx, end);
            }
        }
        return "";
    }

    private static String fetchSubtitleHttp(String urlStr) throws Exception {
        HttpURLConnection conn = Requester.openConnection(urlStr);
        conn.setConnectTimeout(SUBTITLE_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(SUBTITLE_READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", CaptionCookiesPatch.getUserAgent());
        String cookies = CaptionCookiesPatch.getCookies();
        if (!cookies.isEmpty()) {
            conn.setRequestProperty("Cookie", cookies);
        }
        Map<String, String> authHeaders = AuthUtils.getRequestHeader();
        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (conn.getResponseCode() != 200) {
            throw new Exception("HTTP " + conn.getResponseCode());
        }
        return Requester.parseString(conn);
    }

    private static List<SubtitleLine> parseJson3Subtitles(String json) throws Exception {
        List<SubtitleLine> lines = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null) return lines;

        String prevText = "";
        for (int i = 0, len = events.length(); i < len; i++) {
            JSONObject event = events.getJSONObject(i);
            if (event.optInt("aAppend", 0) == 1) continue;

            JSONArray segs = event.optJSONArray("segs");
            if (segs == null) continue;

            long startMs = event.optLong("tStartMs", -1);
            if (startMs < 0) continue;

            StringBuilder sb = new StringBuilder();
            for (int j = 0, sLen = segs.length(); j < sLen; j++) {
                sb.append(segs.getJSONObject(j).optString("utf8", ""));
            }

            String text = sb.toString()
                    .replace('\n', ' ')
                    .replace(">>", "")
                    .replace("♪", "")
                    .replace("♫", "")
                    .trim();
            text = SUBTITLE_BRACKETS_PATTERN.matcher(text).replaceAll("");
            text = SUBTITLE_PARENS_PATTERN.matcher(text).replaceAll("");
            text = text.replaceAll("\\s+", " ").trim();

            if (!text.isEmpty() && !text.equalsIgnoreCase(prevText)) {
                lines.add(new SubtitleLine(startMs, text));
                prevText = text;
            }
        }
        return lines;
    }

    @Nullable
    private static CharSequence decorateWithSubtitleMatchesIfNeeded(ContextInterface contextInterface,
                                                                    CharSequence original,
                                                                    KeywordContentFilter filter) {
        if (!Settings.HIDE_KEYWORD_CONTENT_CHECK_SUBTITLES.get()) {
            return null;
        }
        if (original instanceof Spanned spanned) {
            SubtitleFormattedSpan[] existing = spanned.getSpans(0, spanned.length(), SubtitleFormattedSpan.class);
            if (existing != null && existing.length > 0) {
                return original;
            }
        }
        if (contextInterface == null) {
            return null;
        }
        StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
        if (pathBuilder == null || pathBuilder.length() == 0) {
            return null;
        }
        String path = pathBuilder.toString();
        boolean isVideoSubtitleRow = path.contains("|video_subtitle.");
        boolean isDescriptionBody = path.startsWith("description_rich_text_list.e")
                || path.startsWith("cell_description_body.e");
        if (!isVideoSubtitleRow && !isDescriptionBody) {
            return null;
        }

        String videoId = VideoInformation.getStartingOrPlayingVideoId();
        if (videoId.isEmpty()) {
            videoId = VideoInformation.getVideoId();
        }
        if (videoId.isEmpty()) {
            return null;
        }

        List<KeywordRule> rules = filter.parsedRules;
        if (rules.isEmpty()) {
            return null;
        }

        int rulesVer = filter.currentRulesVersion;
        String cacheKey = videoId + "|" + rulesVer;
        SubtitleScanState state = ensureSubtitleScanStarted(videoId, rulesVer, rules);

        String cleanedOriginal = stripFormatCharacters(original);
        if (cleanedOriginal.isEmpty()) {
            return null;
        }

        if (isDescriptionBody) {
            if (state.firstDescriptionSegment == null) {
                state.firstDescriptionSegment = cleanedOriginal;
            } else if (!state.firstDescriptionSegment.equals(cleanedOriginal)) {
                return null; // Only prepend once to the first text segment of the description
            }
        }

        if (state.pending) {
            SpannableString pendingSpan = new SpannableString(original);
            pendingSpan.setSpan(new PendingSubtitleRelayoutSpan(cacheKey),
                    0, pendingSpan.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return pendingSpan;
        }

        List<SubtitleMatchItem> items = state.items;
        if (items.isEmpty()) {
            return null;
        }

        Spanned baseStyle = original instanceof Spanned s ? s : new SpannableString(original);

        if (isVideoSubtitleRow) {
            StringBuilder summary = new StringBuilder();
            int count = Math.min(3, items.size());
            for (int i = 0; i < count; i++) {
                if (i > 0) summary.append(", ");
                SubtitleMatchItem item = items.get(i);
                summary.append(item.matchedWord).append(" (").append(item.timestamps.get(0)).append(")");
            }
            if (items.size() > count) {
                summary.append(" +").append(items.size() - count);
            }
            String inlineNote = " • " + str("morphe_hide_keyword_subtitles_inline", summary.toString());
            if (original.toString().contains(inlineNote)) {
                return original;
            }

            SpannableString styledNote = newSpanUsingStylingOfAnotherSpan(baseStyle, inlineNote, true);
            SpannableStringBuilder combined = new SpannableStringBuilder(original);
            combined.append(styledNote);
            combined.setSpan(new SubtitleFormattedSpan(videoId, rulesVer),
                    0, combined.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return new SpannableString(combined);
        }

        // Full description panel body: prepend a native-styled, non-repetitive block at the top
        String headerTitle = str("morphe_hide_keyword_subtitles_header");
        if (original.toString().startsWith(headerTitle)) {
            return original;
        }

        StringBuilder blockText = new StringBuilder();
        List<int[]> boldRanges = new ArrayList<>();

        blockText.append(headerTitle).append("\n");
        boldRanges.add(new int[]{0, headerTitle.length()});

        for (int i = 0, size = items.size(); i < size; i++) {
            SubtitleMatchItem item = items.get(i);
            String tsJoined = String.join(", ", item.timestamps);
            int tsStart = blockText.length();
            blockText.append(tsJoined);
            boldRanges.add(new int[]{tsStart, blockText.length()});

            blockText.append(" — ");
            int snippetStart = blockText.length();
            blockText.append(item.snippet);

            int wordIdx = item.snippet.toLowerCase(Locale.ROOT)
                    .indexOf(item.matchedWord.toLowerCase(Locale.ROOT));
            if (wordIdx >= 0) {
                boldRanges.add(new int[]{snippetStart + wordIdx,
                        snippetStart + wordIdx + item.matchedWord.length()});
            }
            blockText.append("\n");
        }
        blockText.append("\n");

        SpannableString styledHeaderBlock = newSpanUsingStylingOfAnotherSpan(baseStyle, blockText.toString(), true);
        SpannableStringBuilder builder = new SpannableStringBuilder(styledHeaderBlock);
        for (int i = 0, size = boldRanges.size(); i < size; i++) {
            int[] r = boldRanges.get(i);
            if (r[1] > r[0] && r[1] <= builder.length()) {
                builder.setSpan(new StyleSpan(Typeface.BOLD), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        builder.append(original);
        builder.setSpan(new SubtitleFormattedSpan(videoId, rulesVer),
                0, builder.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return new SpannableString(builder);
    }

    // endregion

    // region Debug Mode hooks (Inline word highlighting & ComponentHost dimming)

    /**
     * Injection point called by {@code hookLithoSpannableString(KEYWORD_FILTER)}.
     * Handles native subtitle match display in descriptions and inline word highlighting in Debug Mode.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence original) {
        try {
            if (original == null) {
                return null;
            }

            KeywordContentFilter filter = instance;
            if (filter == null) {
                return original;
            }
            filter.reparseIfNeeded();

            // 1. Check native subtitle match display for video description / subtitle row
            CharSequence subtitleDecorated = decorateWithSubtitleMatchesIfNeeded(contextInterface, original, filter);
            if (subtitleDecorated != null) {
                return subtitleDecorated;
            }

            // 2. Debug Mode inline word highlighting
            if (!Settings.HIDE_KEYWORD_CONTENT_DEBUG.get()) {
                return original;
            }
            if (getDebugRuleSpan(original) != null) {
                return original;
            }

            List<KeywordRule> rules = filter.parsedRules;
            if (rules.isEmpty()) {
                return original;
            }

            if (!filter.isContextFilterableForDebug(contextInterface)) {
                return original;
            }

            String rawStr = original.toString();
            String cleaned = stripFormatCharacters(rawStr);
            if (cleaned.length() < 2
                    || isDurationOrBadgeText(cleaned)
                    || COMMENT_UI_LABELS.contains(cleaned.toLowerCase(Locale.ROOT))) {
                return original;
            }

            MatchDetails recorded = debugMatchedTextMap.get(cleaned);
            List<int[]> matchRanges = new ArrayList<>();
            String matchedWord = null;
            String matchedRuleDisplay = null;

            for (int i = 0, size = rules.size(); i < size; i++) {
                KeywordRule rule = rules.get(i);
                boolean allowPartialAnd = recorded != null && recorded.ruleDisplay().equals(rule.displayRule);
                List<int[]> ruleRanges = new ArrayList<>();
                rule.collectMatchRangesInText(rawStr, allowPartialAnd, ruleRanges);
                if (!ruleRanges.isEmpty()) {
                    matchRanges.addAll(ruleRanges);
                    if (matchedWord == null) {
                        int[] first = ruleRanges.get(0);
                        matchedWord = rawStr.substring(first[0], first[1]);
                        matchedRuleDisplay = rule.displayRule;
                    }
                    break;
                }
            }

            if (matchRanges.isEmpty() || matchedWord == null || matchedRuleDisplay == null) {
                return original;
            }

            if (recorded == null) {
                recordMatchLog(Source.HOME, matchedWord, cleaned, matchedRuleDisplay, null, true);
            }

            SpannableStringBuilder builder = new SpannableStringBuilder(original);
            for (int i = 0, size = matchRanges.size(); i < size; i++) {
                int[] range = matchRanges.get(i);
                int start = Math.max(0, Math.min(range[0], builder.length()));
                int end = Math.max(start, Math.min(range[1], builder.length()));
                if (end > start) {
                    builder.setSpan(new BackgroundColorSpan(0xFFD32F2F), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new ForegroundColorSpan(0xFFFFFFFF), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }

            builder.setSpan(new DebugRuleSpan(matchedWord, cleaned, matchedRuleDisplay),
                    0, builder.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            return new SpannableString(builder);
        } catch (Exception ex) {
            Logger.printException(() -> "KeywordContentFilter.onLithoTextLoaded failure", ex);
        }
        return original;
    }

    private boolean isContextFilterableForDebug(@Nullable ContextInterface contextInterface) {
        if (contextInterface == null) {
            return false;
        }
        StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
        if (pathBuilder == null || pathBuilder.length() == 0) {
            return false;
        }
        String path = pathBuilder.toString();
        if (path.contains("overflow_button.e")
                || path.contains("button.e")
                || path.contains("badge.e")
                || path.contains("avatar.e")
                || path.contains("thumbnail.e")) {
            return false;
        }
        if (path.contains(COMMENT_THREAD_PATH) || path.contains("|comment.e")) {
            return Settings.HIDE_KEYWORD_CONTENT_COMMENTS.get();
        }
        for (CharSequence prefix : startsWithFilter.filters) {
            if (path.startsWith(prefix.toString())) {
                return isActiveForFeedContext(contextInterface);
            }
        }
        for (CharSequence sub : containsFilter.filters) {
            if (path.contains(sub)) {
                return isActiveForFeedContext(contextInterface);
            }
        }
        return false;
    }

    @Nullable
    private static DebugRuleSpan getDebugRuleSpan(@Nullable CharSequence text) {
        if (text instanceof Spanned spanned) {
            DebugRuleSpan[] spans = spanned.getSpans(0, spanned.length(), DebugRuleSpan.class);
            if (spans != null && spans.length > 0) {
                return spans[0];
            }
        }
        return null;
    }

    /**
     * Injection point called at the end of {@code com.facebook.litho.ComponentHost.onLayout}.
     * When Debug Mode is enabled, dims matched video/comment cards. Restores recycled hosts when unmatched.
     */
    public static void onComponentHostLayout(ComponentHost host) {
        if (host == null) {
            return;
        }
        if (!Settings.HIDE_KEYWORD_CONTENT_DEBUG.get() && debugOverlays.isEmpty()) {
            return;
        }
        try {
            ComponentHost root = findRootHost(host);
            if (root == null) {
                return;
            }
            pendingHostsToUpdate.put(root, Boolean.TRUE);
            if (!hostsUpdateScheduled) {
                hostsUpdateScheduled = true;
                Utils.runOnMainThread(updateDebugHostsRunnable);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "KeywordContentFilter.onComponentHostLayout failure", ex);
        }
    }

    private static final Runnable updateDebugHostsRunnable = () -> {
        hostsUpdateScheduled = false;
        try {
            ComponentHost[] roots = pendingHostsToUpdate.keySet().toArray(new ComponentHost[0]);
            pendingHostsToUpdate.clear();

            final boolean debugEnabled = Settings.HIDE_KEYWORD_CONTENT_DEBUG.get();
            for (ComponentHost root : roots) {
                if (root == null) continue;
                if (!debugEnabled) {
                    detachDebugOverlay(root);
                    continue;
                }
                if (!root.isAttachedToWindow() || root.getParent() instanceof ComponentHost) {
                    continue;
                }
                if (hasHorizontalScrollingChild(root) || isHostInDialog(root)) {
                    detachDebugOverlay(root);
                    continue;
                }

                DebugRuleSpan matchedSpan = findDebugSpanInViewTree(root);
                if (matchedSpan != null) {
                    DebugCardOverlay overlay = debugOverlays.get(root);
                    if (overlay == null) {
                        overlay = new DebugCardOverlay(root);
                        debugOverlays.put(root, overlay);
                    }
                    overlay.bind(root);
                } else {
                    detachDebugOverlay(root);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateDebugHostsRunnable failure", ex);
        }
    };

    private static void detachDebugOverlay(ComponentHost root) {
        DebugCardOverlay existing = debugOverlays.remove(root);
        if (existing != null) {
            existing.detach(root);
        }
    }

    @Nullable
    private static DebugRuleSpan findDebugSpanInViewTree(View view) {
        if (view instanceof ComponentHost host) {
            TextContent textContent = host.getTextContent();
            if (textContent != null) {
                for (CharSequence item : textContent.getTextItems()) {
                    DebugRuleSpan span = getDebugRuleSpan(item);
                    if (span != null) {
                        return span;
                    }
                }
            }
        } else if (view instanceof TextView textView) {
            DebugRuleSpan span = getDebugRuleSpan(textView.getText());
            if (span != null) {
                return span;
            }
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && !isHorizontalScrollingView(child)) {
                    DebugRuleSpan found = findDebugSpanInViewTree(child);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static ComponentHost findRootHost(ComponentHost host) {
        ComponentHost root = host;
        ViewParent parent = host.getParent();
        while (parent instanceof ComponentHost parentHost) {
            root = parentHost;
            parent = parentHost.getParent();
        }
        return root;
    }

    private static boolean isHorizontalScrollingView(View view) {
        if (view instanceof ComponentHost) {
            return false;
        }
        String name = view.getClass().getName();
        return name.contains("RecyclerView")
                || name.contains("HorizontalScrollView")
                || name.contains("ViewPager")
                || view.canScrollHorizontally(1)
                || view.canScrollHorizontally(-1);
    }

    private static boolean hasHorizontalScrollingChild(ViewGroup group) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (isHorizontalScrollingView(child)
                    || (child instanceof ViewGroup childGroup && hasHorizontalScrollingChild(childGroup))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHostInDialog(ComponentHost host) {
        Context context = host.getContext();
        while (context instanceof ContextWrapper wrapper && !(context instanceof Activity)) {
            context = wrapper.getBaseContext();
        }
        if (context instanceof Activity activity && activity.getWindow() != null) {
            View decorView = activity.getWindow().peekDecorView();
            if (decorView != null && host.getRootView() != decorView) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drawable attached to {@link ComponentHost#getOverlay()} in Debug Mode.
     * Dims the matched card with a clean semi-transparent scrim and subtle border
     * (without drawing micro-text or repeating the title on top of the thumbnail,
     * since the matched word is highlighted directly inside the native text).
     */
    private static final class DebugCardOverlay extends Drawable {
        private static final int BORDER_COLOR = 0x99EF5350;

        private final Paint scrimPaint = new Paint();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cardRect = new RectF();
        private final float density;
        private boolean added;

        DebugCardOverlay(ComponentHost host) {
            density = host.getResources().getDisplayMetrics().density;
            borderPaint.setColor(BORDER_COLOR);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1.5f * density);
        }

        void bind(ComponentHost host) {
            setBounds(0, 0, host.getWidth(), host.getHeight());
            if (!added) {
                host.getOverlay().add(this);
                added = true;
            }
            invalidateSelf();
        }

        void detach(ComponentHost host) {
            if (added) {
                host.getOverlay().remove(this);
                added = false;
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;

            int appBg = ThemeUtils.getAppBackgroundColor() & 0x00FFFFFF;
            scrimPaint.setColor(appBg | 0x9E000000);
            cardRect.set(bounds);
            canvas.drawRect(cardRect, scrimPaint);

            float strokeInset = borderPaint.getStrokeWidth() / 2f;
            cardRect.inset(strokeInset, strokeInset);
            float cornerRadius = 8f * density;
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // endregion

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_broad", matched));
    }

    private void recordHide(Source source, @Nullable String extractedVideoId) {
        String videoId = source == Source.COMMENTS
                ? (VideoInformation.getVideoId().isEmpty() ? null : VideoInformation.getVideoId())
                : extractedVideoId;
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup, ContextInterface contextInterface) {
        if (matchedGroup == commentsFilter || matchedGroup == commentRepliesFilter) return Source.COMMENTS;
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationBar.NavigationButton nav = NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
        return nav == NavigationBar.NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
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
