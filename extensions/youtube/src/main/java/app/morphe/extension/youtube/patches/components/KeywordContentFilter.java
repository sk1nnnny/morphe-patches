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
import android.text.TextUtils;
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

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Allows hiding or dimming (in debug mode) home feed, subscriptions, search results, and comments
 * based on video title keywords, channel names/handles, or full regular expressions.
 * <p>
 * Extracts human-readable text fields from the Litho element protocol buffer (via {@link ProtoNode})
 * rather than scanning raw binary/Base64/URL bytes, preventing false-positive matches on inline
 * playback data, thumbnail signatures, video IDs, UI buttons, and internal tracking tokens.
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

    /** Litho component identifier pattern, such as 'video_lockup_with_attachment.eml-fe|c0c4a49b6544b5fb'. */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[^\\s|]+\\.[^\\s|]+\\|[0-9a-f]{1,16}$");

    /** YouTube channel ID pattern (UC followed by 22 base64-url chars). */
    private static final Pattern CHANNEL_ID_PATTERN =
            Pattern.compile("^UC[A-Za-z0-9_-]{22}$");

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

    private static final Set<String> COMMON_BUFFER_STRINGS;
    static {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, STRINGS_IN_EVERY_BUFFER);
        COMMON_BUFFER_STRINGS = Collections.unmodifiableSet(set);
    }

    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            "comment_thread.eml"
    );

    /** State of settings when rules were last parsed. */
    private volatile String lastKeywordPhrasesParsed;
    private volatile boolean lastUseAndOperator;
    private volatile boolean lastUseRegex;
    private volatile boolean lastCaseSensitive;

    /** Compiled keyword rules in user-specified order. */
    private volatile List<KeywordRule> parsedRules = Collections.emptyList();

    /** Active singleton instance so static hooks (onLithoTextLoaded / onComponentHostLayout) can access rules. */
    @Nullable
    private static volatile KeywordContentFilter instance;

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
     * Thread-safe LRU cache mapping the exact matched text (or videoId for multi-clause '&' rules)
     * to its {@link MatchDetails}. Never stores non-matching texts.
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

        /**
         * @return the exact matched word/substring inside {@code text}, or {@code null} if no match.
         */
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
            if (isWholeWord) {
                int idx = findWholeWordIndex(text, literalPhrase, caseSensitive, 0);
                return idx >= 0 ? text.substring(idx, idx + literalPhrase.length()) : null;
            }
            int idx = findSubstringIndex(text, literalPhrase, caseSensitive, 0);
            return idx >= 0 ? text.substring(idx, idx + literalPhrase.length()) : null;
        }

        /**
         * Appends all character index ranges {@code [start, end]} matched by this clause in {@code text}.
         */
        void collectMatchRanges(String text, List<int[]> rangesOut) {
            if (text.isEmpty()) return;
            if (isRegex && regexPattern != null) {
                Matcher m = regexPattern.matcher(text);
                while (m.find()) {
                    if (m.end() > m.start()) {
                        rangesOut.add(new int[]{m.start(), m.end()});
                    }
                }
                return;
            }
            if (isWholeWord) {
                final int len = literalPhrase.length();
                int idx = 0;
                while (idx <= text.length() - len) {
                    int found = findWholeWordIndex(text, literalPhrase, caseSensitive, idx);
                    if (found < 0) break;
                    rangesOut.add(new int[]{found, found + len});
                    idx = found + Math.max(1, len);
                }
                return;
            }
            final int len = literalPhrase.length();
            int idx = 0;
            while (idx <= text.length() - len) {
                int found = findSubstringIndex(text, literalPhrase, caseSensitive, idx);
                if (found < 0) break;
                rangesOut.add(new int[]{found, found + len});
                idx = found + Math.max(1, len);
            }
        }
    }

    /**
     * Represents a single rule line from {@link Settings#HIDE_KEYWORD_CONTENT_PHRASES}.
     * May contain multiple {@link Clause}s combined with the {@code &} (AND) operator.
     */
    private static final class KeywordRule {
        final String displayRule;
        final List<Clause> clauses;

        KeywordRule(String displayRule, List<Clause> clauses) {
            this.displayRule = displayRule;
            this.clauses = clauses;
        }

        /**
         * @return {@link MatchDetails} for each clause that matched if ALL clauses match across
         *         {@code texts}, or {@code null} if any clause did not match.
         */
        @Nullable
        List<MatchDetails> matchAcrossTexts(List<String> texts) {
            List<MatchDetails> matches = new ArrayList<>(clauses.size());
            for (int i = 0, size = clauses.size(); i < size; i++) {
                Clause clause = clauses.get(i);
                MatchDetails found = null;
                for (int j = 0, textCount = texts.size(); j < textCount; j++) {
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
                matches.add(found);
            }
            return matches;
        }

        /**
         * Collects all matched character ranges {@code [start, end]} in {@code text} for clauses
         * of this rule. For single-clause rules, checks that clause; for '&' rules, highlights
         * any clause that matches when the text was recorded as part of a matched card.
         */
        void collectMatchRangesInText(String text, boolean allowPartialAndClauses, List<int[]> rangesOut) {
            if (!allowPartialAndClauses && clauses.size() > 1) {
                for (int i = 0, size = clauses.size(); i < size; i++) {
                    if (clauses.get(i).findFirstMatchedWord(text) == null) {
                        return;
                    }
                }
            }
            for (int i = 0, size = clauses.size(); i < size; i++) {
                clauses.get(i).collectMatchRanges(text, rangesOut);
            }
        }
    }

    /**
     * Span attached to Litho texts that actually matched a keyword rule in Debug Mode.
     * Intentionally does NOT implement {@link android.text.NoCopySpan} so Litho preserves it
     * when copying text into {@link ComponentHost#getTextContent()}.
     */
    public static final class DebugRuleSpan {
        public final String matchedWord;
        public final String matchedText;
        public final String rule;

        DebugRuleSpan(String matchedWord, String matchedText, String rule) {
            this.matchedWord = matchedWord;
            this.matchedText = matchedText;
            this.rule = rule;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof DebugRuleSpan other
                    && Objects.equals(matchedWord, other.matchedWord)
                    && Objects.equals(matchedText, other.matchedText)
                    && Objects.equals(rule, other.rule);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matchedWord, matchedText, rule);
        }
    }

    private static boolean phraseUsesWholeWordSyntax(String phrase) {
        if (phrase.length() < 2) return false;
        char first = phrase.charAt(0);
        char last = phrase.charAt(phrase.length() - 1);
        return (first == '"' && last == '"')
                || (first == '«' && last == '»')
                || (first == '“' && last == '”');
    }

    private static String stripWholeWordSyntax(String phrase) {
        return phrase.substring(1, phrase.length() - 1);
    }

    private static int findSubstringIndex(String text, String sub, boolean caseSensitive, int fromIndex) {
        final int subLen = sub.length();
        final int max = text.length() - subLen;
        if (max < 0 || subLen == 0 || fromIndex > max) return -1;
        if (caseSensitive) {
            return text.indexOf(sub, Math.max(0, fromIndex));
        }
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (text.regionMatches(true, i, sub, 0, subLen)) {
                return i;
            }
        }
        return -1;
    }

    private static int findWholeWordIndex(String text, String word, boolean caseSensitive, int fromIndex) {
        final int wordLen = word.length();
        final int textLen = text.length();
        final int max = textLen - wordLen;
        if (max < 0 || wordLen == 0 || fromIndex > max) return -1;

        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (text.regionMatches(!caseSensitive, i, word, 0, wordLen)) {
                if (keywordMatchIsWholeWord(text, i, wordLen)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Rewrites {@code \w}, {@code \W}, {@code \b}, and {@code \B} in a regex pattern into
     * Unicode-aware {@code [\p{L}\p{M}\p{N}_]} equivalents so that Cyrillic and other non-ASCII
     * scripts are properly treated as word characters by Android's ICU regex engine.
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
            } else if (c == '[') {
                inCharClass++;
                out.append(c);
            } else if (c == ']' && inCharClass > 0) {
                inCharClass--;
                out.append(c);
            } else {
                out.append(c);
            }
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

        // 1. Check explicit /pattern/flags syntax or regex: prefix
        boolean explicitRegex = false;
        String regexBody = null;
        boolean forceCaseSensitive = caseSensitiveSetting;
        boolean forceCaseInsensitive = !caseSensitiveSetting;

        if (trimmed.length() >= 2 && trimmed.charAt(0) == '/') {
            int lastSlash = trimmed.lastIndexOf('/');
            if (lastSlash > 0) {
                String flagsStr = trimmed.substring(lastSlash + 1);
                if (flagsStr.matches("[iIcsSmMuU]*")) {
                    explicitRegex = true;
                    regexBody = trimmed.substring(1, lastSlash);
                    if (flagsStr.indexOf('c') >= 0 || flagsStr.indexOf('C') >= 0) {
                        forceCaseSensitive = true;
                        forceCaseInsensitive = false;
                    } else if (flagsStr.indexOf('i') >= 0 || flagsStr.indexOf('I') >= 0) {
                        forceCaseInsensitive = true;
                        forceCaseSensitive = false;
                    }
                }
            }
        } else if (trimmed.regionMatches(true, 0, "regex:", 0, 6)) {
            explicitRegex = true;
            regexBody = trimmed.substring(6).trim();
        } else if (trimmed.regionMatches(true, 0, "re:", 0, 3)) {
            explicitRegex = true;
            regexBody = trimmed.substring(3).trim();
        }

        if (explicitRegex) {
            if (regexBody == null || regexBody.isEmpty()) {
                return null;
            }
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

        // 2. Check whole-word quoted syntax: "word"
        if (phraseUsesWholeWordSyntax(trimmed)) {
            String stripped = stripWholeWordSyntax(trimmed);
            if (stripped.isEmpty()) {
                return null;
            }
            return new Clause(trimmed, false, true, caseSensitiveSetting, null, stripped);
        }

        // 3. Check implicit regex when useRegexSetting is enabled and metacharacters are present
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

        // 4. Plain substring keyword
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

            List<Clause> clauses = new ArrayList<>();
            boolean isExplicitRegexLine = line.startsWith("/")
                    || line.regionMatches(true, 0, "regex:", 0, 6)
                    || line.regionMatches(true, 0, "re:", 0, 3);

            if (useAnd && !isExplicitRegexLine && line.indexOf('&') >= 0) {
                String[] parts = splitByAndOutsideQuotes(line);
                boolean validAll = true;
                for (String part : parts) {
                    Clause c = parseSingleClause(part, useRegex, caseSensitive);
                    if (c == null) {
                        validAll = false;
                        break;
                    }
                    clauses.add(c);
                }
                if (!validAll || clauses.isEmpty()) {
                    continue;
                }
            } else {
                Clause c = parseSingleClause(line, useRegex, caseSensitive);
                if (c == null) {
                    continue;
                }
                clauses.add(c);
            }

            StringBuilder canonicalKey = new StringBuilder();
            for (Clause c : clauses) {
                if (canonicalKey.length() > 0) canonicalKey.append(" & ");
                canonicalKey.append(c.isRegex ? "R:" : (c.isWholeWord ? "W:" : "S:"));
                canonicalKey.append(caseSensitive ? c.literalPhrase : c.literalPhrase.toLowerCase(Locale.ROOT));
            }
            if (seenCanonicalRules.containsKey(canonicalKey.toString())) {
                continue;
            }
            seenCanonicalRules.put(canonicalKey.toString(), line);
            rules.add(new KeywordRule(line, clauses));
        }

        parsedRules = rules;
        lastKeywordPhrasesParsed = rawKeywords;
        lastUseAndOperator = useAnd;
        lastUseRegex = useRegex;
        lastCaseSensitive = caseSensitive;

        debugMatchedTextMap.clear();

        Logger.printDebug(() -> "Parsed " + rules.size() + " keyword filter rules");
    }

    /**
     * Splits a line on {@code &} only when {@code &} is outside double quotes.
     */
    private static String[] splitByAndOutsideQuotes(String line) {
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        int start = 0;
        for (int i = 0, len = line.length(); i < len; i++) {
            char ch = line.charAt(i);
            if (ch == '"' || ch == '«' || ch == '»' || ch == '“' || ch == '”') {
                inQuotes = !inQuotes;
            } else if (ch == '&' && !inQuotes) {
                parts.add(line.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(line.substring(start));
        return parts.toArray(new String[0]);
    }

    public KeywordContentFilter() {
        super();
        instance = this;
        addPathCallbacks(commentsFilter);
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

        final boolean hideHome = Settings.HIDE_KEYWORD_CONTENT_HOME.get();
        final boolean hideSubscriptions = Settings.HIDE_KEYWORD_CONTENT_SUBSCRIPTIONS.get();
        if (!hideHome && !hideSubscriptions) {
            return false;
        }

        NavigationBar.NavigationButton selectedNavButton =
                NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
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

        String videoId = extractVideoIdFromBuffer(buffer);
        List<String> searchableTexts = extractSearchableTexts(buffer, accessibility, videoId);
        if (searchableTexts.isEmpty()) {
            return null;
        }

        for (int i = 0, size = rules.size(); i < size; i++) {
            KeywordRule rule = rules.get(i);
            List<MatchDetails> matchedClauses = rule.matchAcrossTexts(searchableTexts);
            if (matchedClauses != null && !matchedClauses.isEmpty()) {
                MatchDetails primary = matchedClauses.get(0);
                Source source = detectSource(matchedGroup, contextInterface);
                final boolean debug = isDebugModeEnabled();

                // RecordONLY the specific texts that actually matched a clause!
                for (int j = 0, cSize = matchedClauses.size(); j < cSize; j++) {
                    MatchDetails detail = matchedClauses.get(j);
                    debugMatchedTextMap.put(detail.matchedText(), detail);
                }

                // Always log the match to Logcat and to the in-app Recent Matches Log!
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

    private static void recordMatchLog(Source source, String matchedWord, String matchedText,
                                       String ruleDisplay, @Nullable String videoId, boolean debugMode) {
        Logger.printInfo(() -> "[KeywordFilter] " + (debugMode ? "DIMMED" : "HIDDEN")
                + " | Word: '" + matchedWord + "'"
                + " | Text: '" + matchedText + "'"
                + " | Rule: '" + ruleDisplay + "'"
                + " | Source: " + source
                + (videoId != null ? " | VideoId: " + videoId : ""));

        String dedupKey = (videoId != null ? videoId : matchedText) + "|" + matchedWord;
        synchronized (recentMatchLogs) {
            if (!recentLoggedKeys.add(dedupKey)) {
                return;
            }
            if (recentMatchLogs.size() >= MAX_MATCH_LOG_ENTRIES) {
                MatchLogEntry removed = recentMatchLogs.removeLast();
                String removedKey = (removed.videoId() != null ? removed.videoId() : removed.matchedText())
                        + "|" + removed.matchedWord();
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

    /**
     * @return a snapshot of recent keyword filter matches (newest first) for the UI log dialog.
     */
    public static List<MatchLogEntry> getRecentMatchLogs() {
        synchronized (recentMatchLogs) {
            return new ArrayList<>(recentMatchLogs);
        }
    }

    /**
     * Clears the recent match history log.
     */
    public static void clearRecentMatchLogs() {
        synchronized (recentMatchLogs) {
            recentMatchLogs.clear();
            recentLoggedKeys.clear();
        }
    }

    /**
     * Extracts only human-readable visible content texts (video title, channel name/metadata row,
     * @handle, or comment body) from the protocol buffer.
     * <p>
     * When {@code LockupMetadataViewModel} (field 8178) is present on a video card, only its
     * {@code title} (field 1) and {@code metadata} (field 3) are extracted, completely avoiding
     * UI button labels ("Действия", "Перейти на канал", "Комментарии"), thumbnail badges, and
     * verbose screen-reader templates.
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
                    // Structured video card: extract strictly Title (1) and Channel/Metadata (3) + @handle
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
                    // Comment threads, Shorts cells, or older layouts without LockupMetadataViewModel
                    collectReadableProtoTexts(root, videoId, results, seen);
                }
            } else {
                collectFallbackUtf8Strings(buffer, videoId, results, seen);
            }
        }

        // Only fall back to accessibility text if the buffer itself yielded no readable texts
        // (e.g. a Shorts cell whose text is only in accessibility).
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

            // Skip commands and UI button models so button labels ("Действия", "Ответить", "Перевести")
            // are never treated as video/comment content.
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
                    || fieldNumber == RENDERER_CONTEXT_FIELD_NUMBER) {
                continue;
            }

            if (node.isText()) {
                addFilteredUserText(node.getText(), videoId, out, seen);
                continue;
            }

            if (node.children != null) {
                collectReadableProtoTexts(node.children, videoId, out, seen);
            } else {
                String multiLine = node.decodeText();
                if (multiLine != null && multiLine.indexOf('\n') >= 0) {
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

    private static void collectFallbackUtf8Strings(byte[] buffer, @Nullable String videoId,
                                                   List<String> out, Set<String> seen) {
        int start = -1;
        for (int i = 0; i < buffer.length; i++) {
            byte b = buffer[i];
            boolean printable = (b >= 0x20 && b <= 0x7E) || (b & 0x80) != 0 || b == '\n';
            if (printable) {
                if (start < 0) start = i;
            } else {
                if (start >= 0 && i - start >= 2) {
                    String candidate = new String(buffer, start, i - start, StandardCharsets.UTF_8);
                    if (candidate.indexOf('\uFFFD') < 0) {
                        addFilteredUserText(candidate, videoId, out, seen);
                    }
                }
                start = -1;
            }
        }
        if (start >= 0 && buffer.length - start >= 2) {
            String candidate = new String(buffer, start, buffer.length - start, StandardCharsets.UTF_8);
            if (candidate.indexOf('\uFFFD') < 0) {
                addFilteredUserText(candidate, videoId, out, seen);
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

    /**
     * Strips invisible format characters (such as U+200B..U+200D, U+2060..U+2064, U+FEFF)
     * and trims leading/trailing whitespace.
     */
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

    /**
     * Returns the normalized human-readable text if {@code rawText} is a visible UI text candidate,
     * or {@code null} if it is a URL, file/template identifier, Base64 entity key, video/channel ID,
     * codec/font identifier, or other non-visible technical token.
     */
    @Nullable
    public static String normalizeAndFilterUserText(@Nullable String rawText, @Nullable String videoId) {
        if (rawText == null || rawText.isEmpty()) {
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

        if (COMMON_BUFFER_STRINGS.contains(text) || TECHNICAL_SINGLE_WORDS.contains(text)) {
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

        if (!containsWhitespace(text)) {
            if (text.charAt(0) == '@' || text.charAt(0) == '#') {
                if (text.length() > 64
                        || text.indexOf('=') >= 0
                        || text.indexOf('/') >= 0
                        || text.indexOf('|') >= 0
                        || text.indexOf('%') >= 0) {
                    return null;
                }
                return text;
            }

            if (isAllAscii(text)) {
                for (int i = 0, len = text.length(); i < len; i++) {
                    char c = text.charAt(i);
                    if (c == '_' || c == '.' || c == '/' || c == ':' || c == '='
                            || c == '|' || c == '+' || c == '%' || c == '-') {
                        return null;
                    }
                }

                if (containsAsciiDigit(text)) {
                    return null;
                }

                if ((text.startsWith("FE") || text.startsWith("VL") || text.startsWith("PL") || text.startsWith("RD"))
                        && text.length() > 4 && Character.isUpperCase(text.charAt(2))) {
                    return null;
                }
                if (text.length() >= 14 && hasInternalUppercaseTransition(text)) {
                    return null;
                }
                if (text.length() == 11 && hasMultipleInternalUppercaseTransitions(text)) {
                    return null;
                }
            }
        }

        return text;
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

    private static boolean containsAsciiDigit(String text) {
        for (int i = 0, len = text.length(); i < len; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
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

    // region Debug Mode hooks (Inline word highlighting & ComponentHost dimming/overlay)

    /**
     * Injection point called by {@code hookLithoSpannableString(KEYWORD_FILTER)}.
     * In Debug Mode, finds the exact character ranges of the matched word(s) inside {@code original}
     * and highlights those exact words in bright red right inside the title, channel, or comment text
     * (without prepending the raw regex string or pushing the title out of its 2-line container).
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence original) {
        try {
            if (original == null || !Settings.HIDE_KEYWORD_CONTENT_DEBUG.get()) {
                return original;
            }
            if (getDebugRuleSpan(original) != null) {
                return original;
            }

            KeywordContentFilter filter = instance;
            if (filter == null) {
                return original;
            }
            filter.reparseIfNeeded();
            List<KeywordRule> rules = filter.parsedRules;
            if (rules.isEmpty()) {
                return original;
            }

            if (!filter.isContextFilterableForDebug(contextInterface)) {
                return original;
            }

            String rawStr = original.toString();
            String cleaned = stripFormatCharacters(rawStr);
            if (cleaned.length() < 2 || isDurationOrBadgeText(cleaned)) {
                return original;
            }

            // Check if this specific text was recorded as a matched clause or directly matches a rule.
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
            // Dim the overall text slightly so the highlighted trigger word pops out clearly.
            builder.setSpan(new ForegroundColorSpan(0x99AAAAAA), 0, builder.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Highlight each matched word range inline in bright red with bold white text!
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
        if (path.contains("comment_thread.eml") || path.contains("|comment.e")) {
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
     * When Debug Mode is enabled, dims matched video/comment cards and draws a badge showing
     * the exact word that triggered the match. Restores recycled hosts when unmatched.
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
                    overlay.bind(root, matchedSpan);
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
     * Draws a semi-transparent background-colored scrim over the entire card and renders
     * a full-opacity pill badge at the top-left showing the exact word that triggered the match.
     */
    private static final class DebugCardOverlay extends Drawable {
        private static final int BADGE_BG_COLOR = 0xE6C62828;
        private static final int BORDER_COLOR = 0xB3EF5350;
        private static final int TEXT_COLOR = 0xFFFFFFFF;

        private final Paint scrimPaint = new Paint();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF cardRect = new RectF();
        private final RectF badgeRect = new RectF();
        private final float density;

        private String badgeLabel = "";
        private boolean added;

        DebugCardOverlay(ComponentHost host) {
            density = host.getResources().getDisplayMetrics().density;
            float fontScale = host.getResources().getConfiguration().fontScale;

            borderPaint.setColor(BORDER_COLOR);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(1.5f * density);

            badgeBgPaint.setColor(BADGE_BG_COLOR);
            badgeBgPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(TEXT_COLOR);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textPaint.setTextSize(12.0f * density * fontScale);
        }

        void bind(ComponentHost host, DebugRuleSpan span) {
            String snippet = span.matchedText;
            if (snippet.length() > 36) {
                snippet = snippet.substring(0, 34) + "…";
            }
            badgeLabel = str("morphe_hide_keyword_debug_badge", "«" + span.matchedWord + "» (" + snippet + ")");
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
            scrimPaint.setColor(appBg | 0xA8000000);
            cardRect.set(bounds);
            canvas.drawRect(cardRect, scrimPaint);

            float strokeInset = borderPaint.getStrokeWidth() / 2f;
            cardRect.inset(strokeInset, strokeInset);
            float cornerRadius = 8f * density;
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, borderPaint);

            if (badgeLabel != null && !badgeLabel.isEmpty()) {
                float margin = 8f * density;
                float padH = 8f * density;
                float padV = 4f * density;
                float maxBadgeWidth = Math.max(60f * density, bounds.width() - 2f * margin);
                float maxTextWidth = maxBadgeWidth - 2f * padH;

                CharSequence ellipsized = TextUtils.ellipsize(
                        badgeLabel,
                        new android.text.TextPaint(textPaint),
                        maxTextWidth,
                        TextUtils.TruncateAt.END
                );
                String display = ellipsized.toString();
                float textWidth = textPaint.measureText(display);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textHeight = fm.descent - fm.ascent;

                float badgeWidth = textWidth + 2f * padH;
                float badgeHeight = textHeight + 2f * padV;
                badgeRect.set(margin, margin, margin + badgeWidth, margin + badgeHeight);

                float badgeRadius = 6f * density;
                canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint);

                float textX = badgeRect.left + padH;
                float textY = badgeRect.centerY() - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(display, textX, textY, textPaint);
            }
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
        if (matchedGroup == commentsFilter) return Source.COMMENTS;
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

    /** Returns the total 24h hide count across all sources. */
    public static int hidesInLast24Hours() {
        return sharedTracker.totalSize(System.currentTimeMillis());
    }

    /** Returns the 24h hide count for a given source. */
    public static int hidesInLast24Hours(Source source) {
        return sharedTracker.sourceSize(source, System.currentTimeMillis());
    }

    /** Clears the 24h tracker. Called from the reset dialogs. */
    public static void resetHidesTracker() {
        sharedTracker.reset();
    }

    /** Shared static reference so the UI can query without holding a filter instance. */
    private static final BufferHideStatsTracker sharedTracker =
            new BufferHideStatsTracker(Settings.KEYWORD_HIDES_24H);
}
